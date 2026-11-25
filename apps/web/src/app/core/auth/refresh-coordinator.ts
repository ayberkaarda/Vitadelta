import { DestroyRef, Injectable, InjectionToken, inject } from '@angular/core';
import { Observable, Subject, firstValueFrom } from 'rxjs';

import { ApiClient } from '../api/api-client';
import { AuthTokenResponse } from '../api/api-types';

/** Name of the Web Lock that serialises refresh calls across tabs. */
export const REFRESH_LOCK_NAME = 'vd-refresh';
/** Name of the BroadcastChannel that shares session changes across tabs. */
export const SESSION_CHANNEL_NAME = 'vd-session';

/** The part of the Web Locks API the coordinator uses. */
export interface LockManagerLike {
  request<T>(name: string, callback: () => Promise<T>): Promise<T>;
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
          request: <T>(name: string, callback: () => Promise<T>) =>
            locks.request(name, callback) as Promise<T>,
        }
      : null;
  },
});

export const SESSION_CHANNEL = new InjectionToken<SessionChannelLike | null>('SESSION_CHANNEL', {
  providedIn: 'root',
  factory: () =>
    typeof BroadcastChannel === 'undefined' ? null : new BroadcastChannel(SESSION_CHANNEL_NAME),
});

/** Session change announced by another tab. */
export type SessionMessage =
  | { readonly type: 'session'; readonly response: AuthTokenResponse }
  | { readonly type: 'signed-out' };

function isSessionMessage(value: unknown): value is SessionMessage {
  if (typeof value !== 'object' || value === null || !('type' in value)) {
    return false;
  }
  if (value.type === 'signed-out') {
    return true;
  }
  if (value.type !== 'session' || !('response' in value)) {
    return false;
  }
  const response = value.response as Partial<AuthTokenResponse> | null;
  return (
    typeof response?.accessToken === 'string' &&
    typeof response.user === 'object' &&
    response.user !== null
  );
}

/**
 * Single-flight access-token refresh (project specification §10.4).
 *
 * - Within a tab, concurrent callers share one in-flight promise.
 * - Across tabs, the API call runs inside `navigator.locks.request('vd-refresh')`. A tab that
 *   obtains the lock after another tab refreshed (announced over `BroadcastChannel('vd-session')`
 *   while it waited) reuses that session instead of calling the API again.
 * - Without Web Locks the call goes straight to the API; the server's 20-second grace window
 *   returns the same successor token to concurrent tabs.
 */
@Injectable({ providedIn: 'root' })
export class RefreshCoordinator {
  private readonly api = inject(ApiClient);
  private readonly locks = inject(WEB_LOCKS);
  private readonly channel = inject(SESSION_CHANNEL);
  private readonly remoteSubject = new Subject<SessionMessage>();

  private inFlight: Promise<AuthTokenResponse | null> | null = null;
  private remoteSequence = 0;
  private lastRemoteSession: {
    readonly sequence: number;
    readonly response: AuthTokenResponse;
  } | null = null;

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
      const startedAt = this.remoteSequence;
      this.inFlight = this.runExclusive(startedAt).finally(() => {
        this.inFlight = null;
      });
    }
    return this.inFlight;
  }

  /** Tells other tabs about a session obtained by login in this tab. */
  announceSession(response: AuthTokenResponse): void {
    this.publish({ type: 'session', response });
  }

  /** Tells other tabs that this tab logged out. */
  announceSignedOut(): void {
    this.lastRemoteSession = null;
    this.publish({ type: 'signed-out' });
  }

  private async runExclusive(startedAt: number): Promise<AuthTokenResponse | null> {
    const locks = this.locks;
    if (!locks) {
      return this.callApi();
    }
    let lockFailed = false;
    const result = await locks
      .request(REFRESH_LOCK_NAME, () => this.underLock(startedAt))
      .catch(() => {
        lockFailed = true;
        return null;
      });
    return lockFailed ? this.callApi() : result;
  }

  private underLock(startedAt: number): Promise<AuthTokenResponse | null> {
    const remote = this.lastRemoteSession;
    if (remote && remote.sequence > startedAt) {
      return Promise.resolve(remote.response);
    }
    return this.callApi();
  }

  private async callApi(): Promise<AuthTokenResponse | null> {
    try {
      const response = await firstValueFrom(this.api.refreshSession());
      this.announceSession(response);
      return response;
    } catch {
      return null;
    }
  }

  private publish(message: SessionMessage): void {
    this.channel?.postMessage(message);
  }

  private onRemoteMessage(data: unknown): void {
    if (!isSessionMessage(data)) {
      return;
    }
    this.remoteSequence += 1;
    this.lastRemoteSession =
      data.type === 'session' ? { sequence: this.remoteSequence, response: data.response } : null;
    this.remoteSubject.next(data);
  }
}
