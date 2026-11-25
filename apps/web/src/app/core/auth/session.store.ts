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
 * Who the user is, as the server says (project specification §10.2, §10.4).
 *
 * Holds the access token in memory only, the profile, the `/me` plan and usage summary and,
 * for anonymous visitors, the trial counters. Every value comes from an API response; the
 * client never derives an entitlement from them.
 */
@Injectable({ providedIn: 'root' })
export class SessionStore {
  private readonly api = inject(ApiClient);
  private readonly coordinator = inject(RefreshCoordinator);

  private token: string | null = null;
  private bootstrapPromise: Promise<void> | null = null;

  private readonly statusSignal = signal<SessionStatus>('pending');
  private readonly userSignal = signal<UserProfile | null>(null);
  private readonly meSignal = signal<Me | null>(null);
  private readonly quotaSignal = signal<AnonymousQuota | null>(null);

  readonly status = this.statusSignal.asReadonly();
  /** Profile from the last login, refresh or `/me` response. */
  readonly user = this.userSignal.asReadonly();
  /** Full `/me` payload (profile, plan limits, subscription, usage); `null` until loaded. */
  readonly me = this.meSignal.asReadonly();
  /** Anonymous trial counters from `/session/quota` or the last query; `null` when signed in. */
  readonly anonymousQuota = this.quotaSignal.asReadonly();

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
   * `/session/quota` for the anonymous counters. Idempotent.
   */
  bootstrap(): Promise<void> {
    this.bootstrapPromise ??= this.restore();
    return this.bootstrapPromise;
  }

  /** Resolves once the initial session state is known. */
  whenReady(): Promise<void> {
    return this.bootstrap();
  }

  /** Applies a successful login, tells other tabs and loads `/me`. */
  async signIn(response: AuthTokenResponse): Promise<void> {
    this.applyTokenResponse(response);
    this.coordinator.announceSession(response);
    await this.reloadMe();
  }

  /** Logs out on the server (best effort), clears the session everywhere and reloads the quota. */
  async signOut(): Promise<void> {
    await firstValueFrom(this.api.logout()).catch((error: unknown) => ApiProblem.from(error));
    await this.forget();
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
   * is cleared and `false` is returned.
   */
  async refreshAccessToken(): Promise<boolean> {
    const response = await this.coordinator.refresh();
    if (response) {
      this.applyTokenResponse(response);
      return true;
    }
    this.clear();
    return false;
  }

  /** Forgets token, profile and usage (no server call). */
  clear(): void {
    this.token = null;
    this.userSignal.set(null);
    this.meSignal.set(null);
    this.statusSignal.set('anonymous');
  }

  /** Reloads `/me` (after plan, usage or profile changes). */
  async reloadMe(): Promise<void> {
    try {
      const me = await firstValueFrom(this.api.getMe());
      this.applyMe(me);
    } catch (error: unknown) {
      if (ApiProblem.from(error).status === 401) {
        this.clear();
      }
    }
  }

  /** Reloads the anonymous trial counters from `/session/quota`. */
  async reloadQuota(): Promise<void> {
    try {
      const quota = await firstValueFrom(this.api.getSessionQuota());
      if (quota.principal === 'ANONYMOUS') {
        this.quotaSignal.set(quota);
      } else {
        this.quotaSignal.set(null);
      }
    } catch {
      this.quotaSignal.set(null);
    }
  }

  /** Stores a `/me` (or `PATCH /me`) response. */
  applyMe(me: Me): void {
    this.meSignal.set(me);
    this.userSignal.set(me);
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
    const response = await this.coordinator.refresh();
    if (response) {
      this.applyTokenResponse(response);
      await this.reloadMe();
      return;
    }
    this.clear();
    await this.reloadQuota();
  }

  private applyTokenResponse(response: AuthTokenResponse): void {
    this.token = response.accessToken;
    const previous = this.userSignal();
    if (previous && previous.id !== response.user.id) {
      this.meSignal.set(null);
    }
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
    const sameUser = this.userSignal()?.id === message.response.user.id;
    this.applyTokenResponse(message.response);
    if (!sameUser || !this.meSignal()) {
      void this.reloadMe();
    }
  }
}
