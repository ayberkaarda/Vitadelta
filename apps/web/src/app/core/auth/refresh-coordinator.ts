import { DestroyRef, Injectable, InjectionToken, inject } from '@angular/core';
import { Observable, Subject, firstValueFrom } from 'rxjs';

import { ApiClient } from '../api/api-client';
import { ApiProblem } from '../api/api-problem';
import { AuthTokenResponse } from '../api/api-types';

/** Name of the Web Lock that serialises refresh calls across tabs. */
export const REFRESH_LOCK_NAME = 'vd-refresh';
/** Name of the BroadcastChannel that shares session changes across tabs. */
export const SESSION_CHANNEL_NAME = 'vd-session';

/** Options of a Web Lock request the coordinator uses. */
export interface LockRequestOptions {
  /** Grant the lock only when it is free right now; the callback gets `null` otherwise. */
  readonly ifAvailable?: boolean;
}

/** The part of the Web Locks API the coordinator uses. */
export interface LockManagerLike {
  request<T>(
    name: string,
    options: LockRequestOptions,
    callback: (lock: unknown) => Promise<T>,
  ): Promise<T>;
}

/** The part of `BroadcastChannel` the coordinator uses. */
export interface SessionChannelLike {
  postMessage(message: unknown): void;
  addEventListener(type: 'message', listener: (event: MessageEvent<unknown>) => void): void;
  close(): void;
}

export const WEB_LOCKS = new InjectionToken<LockManagerLike | null>('WEB_LOCKS', {
  providedIn: 'root',
  factory: () => {
    const locks = typeof navigator === 'undefined' ? undefined : navigator.locks;
    return locks
      ? {
          request: <T>(
            name: string,
            options: LockRequestOptions,
            callback: (lock: unknown) => Promise<T>,
          ) => locks.request(name, options, callback) as Promise<T>,
        }
      : null;
  },
});

export const SESSION_CHANNEL = new InjectionToken<SessionChannelLike | null>('SESSION_CHANNEL', {
  providedIn: 'root',
  factory: () =>
    typeof BroadcastChannel === 'undefined' ? null : new BroadcastChannel(SESSION_CHANNEL_NAME),
});

/**
 * How long a tab that waited for the refresh lock waits for the outcome report of the tab that
 * held it. Without a report (the holder was closed mid-refresh) the tab refreshes itself.
 */
export const REFRESH_HANDSHAKE_TIMEOUT_MS = new InjectionToken<number>(
  'REFRESH_HANDSHAKE_TIMEOUT_MS',
  { providedIn: 'root', factory: () => 2000 },
);

/**
 * Session change announced by another tab. `at` is the sender's clock (milliseconds since the
 * epoch): for a session, when its login or refresh request started; for a sign-out, when it
 * happened. Tabs share the clock of one machine, so the values are comparable.
 */
export type SessionMessage =
  | { readonly type: 'session'; readonly response: AuthTokenResponse; readonly at: number }
  | { readonly type: 'signed-out'; readonly at: number };

/** How a refresh ended: a session, a refusal of the refresh cookie, or no answer. */
type RefreshOutcome = 'ok' | 'rejected' | 'error';

/** Report of a tab that held the refresh lock, posted before it releases the lock. */
interface RefreshReport {
  readonly type: 'refresh-done';
  readonly completedAt: number;
  readonly outcome: RefreshOutcome;
  readonly response: AuthTokenResponse | null;
}

interface LockResult {
  readonly outcome: RefreshOutcome;
  readonly response: AuthTokenResponse | null;
}

function isTokenResponse(value: unknown): value is AuthTokenResponse {
  const response = value as Partial<AuthTokenResponse> | null;
  return (
    typeof response?.accessToken === 'string' &&
    typeof response.user === 'object' &&
    response.user !== null &&
    typeof response.user.id === 'string'
  );
}

function isSessionMessage(value: unknown): value is SessionMessage {
  if (typeof value !== 'object' || value === null || !('type' in value) || !('at' in value)) {
    return false;
  }
  if (typeof value.at !== 'number' || !Number.isFinite(value.at)) {
    return false;
  }
  if (value.type === 'signed-out') {
    return true;
  }
  return value.type === 'session' && 'response' in value && isTokenResponse(value.response);
}

function isRefreshReport(value: unknown): value is RefreshReport {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const report = value as Partial<RefreshReport>;
  return (
    report.type === 'refresh-done' &&
    typeof report.completedAt === 'number' &&
    (report.outcome === 'ok' || report.outcome === 'rejected' || report.outcome === 'error') &&
    (report.outcome === 'ok' ? isTokenResponse(report.response) : report.response === null)
  );
}

/**
 * Single-flight access-token refresh (project specification §10.4).
 *
 * - Within a tab, concurrent callers share one in-flight promise.
 * - Across tabs, the API call runs inside `navigator.locks.request('vd-refresh')`. Every tab that
 *   holds the lock posts a `refresh-done` report with its outcome over
 *   `BroadcastChannel('vd-session')` before it releases the lock. A tab that found the lock taken
 *   therefore knows a report is due: it waits for the report of a refresh that completed after
 *   it started waiting (bounded by {@link REFRESH_HANDSHAKE_TIMEOUT_MS}) and reuses that session,
 *   or returns no session when the refresh cookie was refused, instead of calling the API again.
 *   Without a report it refreshes itself.
 * - Without Web Locks the call goes straight to the API; the server's 20-second grace window
 *   returns the same successor token to concurrent tabs.
 * - {@link invalidate} (a session change in this tab) and a sign-out in another tab end the
 *   current epoch: a refresh started before is neither announced nor reused, and a session
 *   announced by another tab for a refresh that started before a sign-out is ignored.
 */
@Injectable({ providedIn: 'root' })
export class RefreshCoordinator {
  private readonly api = inject(ApiClient);
  private readonly locks = inject(WEB_LOCKS);
  private readonly channel = inject(SESSION_CHANNEL);
  private readonly handshakeTimeout = inject(REFRESH_HANDSHAKE_TIMEOUT_MS);
  private readonly remoteSubject = new Subject<SessionMessage>();

  private inFlight: Promise<AuthTokenResponse | null> | null = null;
  private epoch = 0;
  private remoteSequence = 0;
  private lastSignedOutAt = Number.NEGATIVE_INFINITY;
  private lastRemoteSession: {
    readonly sequence: number;
    readonly response: AuthTokenResponse;
  } | null = null;
  private lastReport: RefreshReport | null = null;
  private readonly reportWaiters = new Set<(report: RefreshReport) => void>();

  /** Session changes made by other tabs. */
  readonly remoteChanges$: Observable<SessionMessage> = this.remoteSubject.asObservable();

  constructor() {
    const channel = this.channel;
    if (channel) {
      channel.addEventListener('message', (event) => this.onRemoteMessage(event.data));
      inject(DestroyRef).onDestroy(() => channel.close());
    }
  }

  /** Refreshes the session; resolves `null` when no session can be obtained. */
  refresh(): Promise<AuthTokenResponse | null> {
    if (!this.inFlight) {
      const epoch = this.epoch;
      const startedAt = this.remoteSequence;
      const run: Promise<AuthTokenResponse | null> = this.runExclusive(startedAt, epoch).finally(
        () => {
          if (this.inFlight === run) {
            this.inFlight = null;
          }
        },
      );
      this.inFlight = run;
    }
    return this.inFlight;
  }

  /**
   * Ends the current epoch after a session change in this tab: the refresh in flight is no
   * longer shared with new callers, and its answer is neither announced nor reused.
   */
  invalidate(): void {
    this.epoch += 1;
    this.inFlight = null;
    this.lastRemoteSession = null;
  }

  /** Tells other tabs about a session obtained by login in this tab. */
  announceSession(response: AuthTokenResponse): void {
    this.publish({ type: 'session', response, at: Date.now() });
  }

  /** Tells other tabs that this tab logged out. */
  announceSignedOut(): void {
    const at = Date.now();
    this.lastSignedOutAt = Math.max(this.lastSignedOutAt, at);
    this.invalidate();
    this.publish({ type: 'signed-out', at });
  }

  private async runExclusive(startedAt: number, epoch: number): Promise<AuthTokenResponse | null> {
    const locks = this.locks;
    if (!locks) {
      return (await this.callApi(epoch)).response;
    }
    const queuedAt = Date.now();
    let granted: { readonly result: LockResult } | null;
    try {
      granted = await locks.request(REFRESH_LOCK_NAME, { ifAvailable: true }, async (lock) =>
        lock ? { result: await this.holding(() => this.reuseOrCall(startedAt, epoch)) } : null,
      );
      if (!granted) {
        // Another tab holds the lock: its report decides whether a refresh is still needed.
        granted = await locks.request(REFRESH_LOCK_NAME, {}, async () => ({
          result: await this.holding(() => this.afterWaiting(startedAt, queuedAt, epoch)),
        }));
      }
    } catch {
      return (await this.callApi(epoch)).response;
    }
    return granted.result.response;
  }

  /** Runs `work` while holding the lock and reports its outcome before the lock is released. */
  private async holding(work: () => Promise<LockResult>): Promise<LockResult> {
    const result = await work();
    const report: RefreshReport = {
      type: 'refresh-done',
      completedAt: Date.now(),
      outcome: result.outcome,
      response: result.outcome === 'ok' ? result.response : null,
    };
    this.channel?.postMessage(report);
    return result;
  }

  private reuseOrCall(startedAt: number, epoch: number): Promise<LockResult> {
    const remote = this.lastRemoteSession;
    if (remote && remote.sequence > startedAt && epoch === this.epoch) {
      return Promise.resolve({ outcome: 'ok', response: remote.response });
    }
    return this.callApi(epoch);
  }

  private async afterWaiting(
    startedAt: number,
    queuedAt: number,
    epoch: number,
  ): Promise<LockResult> {
    const report = this.reportSince(queuedAt) ?? (await this.nextReport(queuedAt));
    if (epoch !== this.epoch) {
      return { outcome: 'error', response: null };
    }
    if (report?.outcome === 'ok' && report.response) {
      return { outcome: 'ok', response: report.response };
    }
    if (report?.outcome === 'rejected') {
      return { outcome: 'rejected', response: null };
    }
    return this.reuseOrCall(startedAt, epoch);
  }

  private reportSince(queuedAt: number): RefreshReport | null {
    const report = this.lastReport;
    return report && report.completedAt >= queuedAt ? report : null;
  }

  private nextReport(queuedAt: number): Promise<RefreshReport | null> {
    return new Promise((resolve) => {
      const waiter = (report: RefreshReport): void => {
        if (report.completedAt >= queuedAt) {
          clearTimeout(timer);
          this.reportWaiters.delete(waiter);
          resolve(report);
        }
      };
      const timer = setTimeout(() => {
        this.reportWaiters.delete(waiter);
        resolve(null);
      }, this.handshakeTimeout);
      this.reportWaiters.add(waiter);
    });
  }

  private async callApi(epoch: number): Promise<LockResult> {
    const at = Date.now();
    try {
      const response = await firstValueFrom(this.api.refreshSession());
      if (epoch !== this.epoch) {
        // The session changed while the call ran: the answer is not applied, nor announced.
        return { outcome: 'error', response: null };
      }
      this.publish({ type: 'session', response, at });
      return { outcome: 'ok', response };
    } catch (error: unknown) {
      const status = ApiProblem.from(error).status;
      return { outcome: status === 401 || status === 403 ? 'rejected' : 'error', response: null };
    }
  }

  private publish(message: SessionMessage): void {
    this.channel?.postMessage(message);
  }

  private onRemoteMessage(data: unknown): void {
    if (isRefreshReport(data)) {
      if (!this.lastReport || data.completedAt >= this.lastReport.completedAt) {
        this.lastReport = data;
      }
      for (const waiter of [...this.reportWaiters]) {
        waiter(data);
      }
      return;
    }
    if (!isSessionMessage(data)) {
      return;
    }
    if (data.type === 'signed-out') {
      this.lastSignedOutAt = Math.max(this.lastSignedOutAt, data.at);
      this.invalidate();
      this.remoteSequence += 1;
      this.remoteSubject.next(data);
      return;
    }
    if (data.at < this.lastSignedOutAt) {
      // A refresh that started before a sign-out never revives the signed-out session.
      return;
    }
    this.remoteSequence += 1;
    this.lastRemoteSession = { sequence: this.remoteSequence, response: data.response };
    this.remoteSubject.next(data);
  }
}
