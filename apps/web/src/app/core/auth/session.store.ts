import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../api/api-client';
import { ApiProblem } from '../api/api-problem';
import {
  AnonymousQuota,
  AuthTokenResponse,
  Me,
  PrincipalSummary,
  Role,
  Usage,
  UserProfile,
} from '../api/api-types';
import { RefreshCoordinator, SessionMessage } from './refresh-coordinator';

export type SessionStatus = 'pending' | 'anonymous' | 'authenticated';

/**
 * Result of {@link SessionStore.refreshAccessToken}: a new token for the same session, no
 * session any more, or a session change while the refresh ran (another account, a sign-out or a
 * sign-in), after which the caller's request must not be repeated.
 */
export type RefreshOutcome = 'refreshed' | 'failed' | 'superseded';

/**
 * Who the user is, as the server says (project specification §10.2, §10.4).
 *
 * Holds the access token in memory only, the profile, the `/me` plan and usage summary and,
 * for anonymous visitors, the trial counters. Every value comes from an API response; the
 * client never derives an entitlement from them.
 *
 * Session generations: {@link generation} advances whenever the identity behind the session
 * changes (sign-in, sign-out, an account switch or sign-out in another tab, a failed refresh,
 * account deletion), never on a plain token rotation. Answers of `/auth/refresh` and `/me`
 * requested under an older generation are discarded, and user-scoped state elsewhere keys on the
 * generation so it is cleared or reloaded when it changes.
 */
@Injectable({ providedIn: 'root' })
export class SessionStore {
  private readonly api = inject(ApiClient);
  private readonly coordinator = inject(RefreshCoordinator);

  private token: string | null = null;
  private bootstrapPromise: Promise<void> | null = null;
  /** User id of the current generation (`null` while anonymous or unknown). */
  private identity: string | null = null;
  private meSequence = 0;

  private readonly statusSignal = signal<SessionStatus>('pending');
  private readonly userSignal = signal<UserProfile | null>(null);
  private readonly meSignal = signal<Me | null>(null);
  private readonly quotaSignal = signal<AnonymousQuota | null>(null);
  private readonly generationSignal = signal(0);
  /** Generation whose `/me` was loaded successfully; `null` before any. */
  private readonly meGeneration = signal<number | null>(null);

  readonly status = this.statusSignal.asReadonly();
  /** Profile from the last login, refresh or `/me` response. */
  readonly user = this.userSignal.asReadonly();
  /** Full `/me` payload (profile, plan limits, subscription, usage); `null` until loaded. */
  readonly me = this.meSignal.asReadonly();
  /** Anonymous trial counters from `/session/quota` or the last query; `null` when signed in. */
  readonly anonymousQuota = this.quotaSignal.asReadonly();
  /** Session generation; changes with the identity behind the session. */
  readonly generation = this.generationSignal.asReadonly();

  readonly isAuthenticated = computed(() => this.statusSignal() === 'authenticated');
  readonly role = computed<Role | null>(() => this.userSignal()?.role ?? null);
  readonly isAdmin = computed(() => this.role() === 'ADMIN');
  /** `ANONYMOUS`, `USER` or `ADMIN`; `null` while the session is being restored. */
  readonly principal = computed<'ANONYMOUS' | Role | null>(() => {
    switch (this.statusSignal()) {
      case 'pending':
        return null;
      case 'anonymous':
        return 'ANONYMOUS';
      case 'authenticated':
        return this.role();
    }
  });
  readonly plan = computed(() => this.meSignal()?.plan.code ?? null);
  readonly usage = computed(() => this.meSignal()?.usage ?? null);
  /** Whether `/me` answered successfully for the current generation. */
  readonly hasCurrentMe = computed(
    () =>
      this.isAuthenticated() &&
      this.meSignal() !== null &&
      this.meGeneration() === this.generationSignal(),
  );

  constructor() {
    const subscription = this.coordinator.remoteChanges$.subscribe((message) =>
      this.onRemoteChange(message),
    );
    inject(DestroyRef).onDestroy(() => subscription.unsubscribe());
  }

  /** Current access token (memory only) for the auth interceptor. */
  accessToken(): string | null {
    return this.token;
  }

  /**
   * Restores the session on app start: `/auth/refresh`, then `/me` on success, otherwise
   * `/session/quota` for the anonymous counters. A `204` (no refresh cookie) is the ordinary
   * anonymous start, not an error. Idempotent.
   */
  bootstrap(): Promise<void> {
    this.bootstrapPromise ??= this.restore();
    return this.bootstrapPromise;
  }

  /** Resolves once the initial session state is known. */
  whenReady(): Promise<void> {
    return this.bootstrap();
  }

  /** Applies a successful login (a new generation), tells other tabs and loads `/me`. */
  async signIn(response: AuthTokenResponse): Promise<void> {
    this.enter(response.user.id, true);
    this.applyTokenResponse(response);
    this.coordinator.announceSession(response);
    await this.reloadMe();
  }

  /** Clears the session everywhere, logs out on the server (best effort) and reloads the quota. */
  async signOut(): Promise<void> {
    this.clear();
    this.coordinator.announceSignedOut();
    await firstValueFrom(this.api.logout()).catch((error: unknown) => ApiProblem.from(error));
    await this.reloadQuota();
  }

  /**
   * Clears the session in this and every other tab and reloads the anonymous quota, without
   * calling `/auth/logout`: used after `DELETE /me`, when the server has already revoked every
   * session of the deleted account.
   */
  async forget(): Promise<void> {
    this.clear();
    this.coordinator.announceSignedOut();
    await this.reloadQuota();
  }

  /**
   * Obtains a new access token through the single-flight coordinator. On failure the session
   * is cleared. A refresh that ends after the session changed is discarded (`superseded`); a
   * refresh that returns another account starts a new generation for it (`superseded` too).
   */
  async refreshAccessToken(): Promise<RefreshOutcome> {
    const generation = this.generationSignal();
    const response = await this.coordinator.refresh();
    if (generation !== this.generationSignal()) {
      return 'superseded';
    }
    if (!response) {
      this.clear();
      return 'failed';
    }
    if (response.user.id !== this.identity) {
      this.enter(response.user.id);
      this.applyTokenResponse(response);
      void this.reloadMe();
      return 'superseded';
    }
    this.applyTokenResponse(response);
    return 'refreshed';
  }

  /** Forgets token, profile and usage (no server call); a new generation when signed in. */
  clear(): void {
    this.enter(null);
    this.token = null;
    this.userSignal.set(null);
    this.meSignal.set(null);
    this.statusSignal.set('anonymous');
  }

  /**
   * Resolves `true` once `/me` answered successfully for the current generation, loading it
   * when needed; `false` when signed out or when `/me` fails.
   */
  async ensureMe(): Promise<boolean> {
    if (!this.isAuthenticated()) {
      return false;
    }
    if (!this.hasCurrentMe()) {
      await this.reloadMe();
    }
    return this.hasCurrentMe();
  }

  /** Reloads `/me` (after plan, usage or profile changes). */
  async reloadMe(): Promise<void> {
    const generation = this.generationSignal();
    const sequence = ++this.meSequence;
    try {
      const me = await firstValueFrom(this.api.getMe());
      if (generation === this.generationSignal() && sequence === this.meSequence) {
        this.applyMe(me);
      }
    } catch (error: unknown) {
      if (generation === this.generationSignal() && ApiProblem.from(error).status === 401) {
        this.clear();
      }
    }
  }

  /** Reloads the anonymous trial counters from `/session/quota`. */
  async reloadQuota(): Promise<void> {
    const generation = this.generationSignal();
    try {
      const quota = await firstValueFrom(this.api.getSessionQuota());
      if (generation !== this.generationSignal()) {
        return;
      }
      if (quota.principal === 'ANONYMOUS') {
        this.quotaSignal.set(quota);
      } else {
        this.quotaSignal.set(null);
      }
    } catch {
      if (generation === this.generationSignal()) {
        this.quotaSignal.set(null);
      }
    }
  }

  /**
   * Stores a `/me` (or `PATCH /me`) response of the signed-in account. A profile of any other
   * account (a late answer from before an account switch) is ignored.
   */
  applyMe(me: Me): void {
    if (this.identity === null || me.id !== this.identity) {
      return;
    }
    this.meSignal.set(me);
    this.userSignal.set(me);
    this.meGeneration.set(this.generationSignal());
    this.statusSignal.set('authenticated');
  }

  /** Updates the plan usage from a response that carries it. */
  applyUsage(usage: Usage): void {
    const me = this.meSignal();
    if (me) {
      this.meSignal.set({ ...me, usage });
    }
  }

  /** Updates the anonymous counters from `meta.principal` of a query response. */
  applyPrincipal(principal: PrincipalSummary): void {
    if (principal.type !== 'ANONYMOUS') {
      return;
    }
    const current = this.quotaSignal();
    this.quotaSignal.set({
      principal: 'ANONYMOUS',
      queriesUsed: principal.queriesUsed,
      queriesLimit: principal.queriesLimit,
      visibleResults: current?.visibleResults ?? 0,
    });
  }

  /** Updates the anonymous counters from a `403 quota_exceeded` problem. */
  applyQuotaProblem(problem: ApiProblem): void {
    if (problem.code !== 'quota_exceeded' || problem.used === null || problem.limit === null) {
      return;
    }
    const current = this.quotaSignal();
    this.quotaSignal.set({
      principal: 'ANONYMOUS',
      queriesUsed: problem.used,
      queriesLimit: problem.limit,
      visibleResults: current?.visibleResults ?? 0,
    });
  }

  private async restore(): Promise<void> {
    const generation = this.generationSignal();
    const response = await this.coordinator.refresh();
    if (generation !== this.generationSignal()) {
      // A sign-in or a change from another tab decided the session meanwhile.
      return;
    }
    if (response) {
      this.enter(response.user.id);
      this.applyTokenResponse(response);
      await this.reloadMe();
      return;
    }
    // No session (204 without a refresh cookie, or a refused cookie): nothing was held yet, so
    // there is nothing to clear or announce; the visitor is anonymous.
    this.statusSignal.set('anonymous');
    await this.reloadQuota();
  }

  /**
   * Starts a new generation when the identity changes (or always, for a fresh login): profile
   * and `/me` of the previous one are dropped and pending refresh work is invalidated.
   */
  private enter(userId: string | null, force = false): void {
    if (!force && userId === this.identity) {
      return;
    }
    this.identity = userId;
    this.generationSignal.update((generation) => generation + 1);
    this.meSequence += 1;
    this.meSignal.set(null);
    this.coordinator.invalidate();
  }

  private applyTokenResponse(response: AuthTokenResponse): void {
    this.token = response.accessToken;
    this.userSignal.set(response.user);
    this.quotaSignal.set(null);
    this.statusSignal.set('authenticated');
  }

  private onRemoteChange(message: SessionMessage): void {
    if (message.type === 'signed-out') {
      if (this.isAuthenticated()) {
        this.clear();
        void this.reloadQuota();
      }
      return;
    }
    const sameUser = this.identity === message.response.user.id;
    this.enter(message.response.user.id);
    this.applyTokenResponse(message.response);
    if (!sameUser || !this.meSignal()) {
      void this.reloadMe();
    }
  }
}
