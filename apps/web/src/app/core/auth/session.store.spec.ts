import { TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';

import {
  fixtureAnonymousQuota,
  fixtureMe,
  fixtureTokenResponse,
  fixtureUser,
} from '../../testing/api-fixtures';
import { ApiClient } from '../api/api-client';
import { ApiProblem } from '../api/api-problem';
import { AuthTokenResponse } from '../api/api-types';
import { RefreshCoordinator, SessionMessage } from './refresh-coordinator';
import { SessionStore } from './session.store';

function problem(status: number, code: ApiProblem['code'] = null): ApiProblem {
  return new ApiProblem({ kind: code ? 'problem' : 'http', status, code });
}

describe('SessionStore', () => {
  let api: {
    getMe: ReturnType<typeof vi.fn>;
    getSessionQuota: ReturnType<typeof vi.fn>;
    logout: ReturnType<typeof vi.fn>;
  };
  let coordinator: {
    refresh: ReturnType<typeof vi.fn>;
    announceSession: ReturnType<typeof vi.fn>;
    announceSignedOut: ReturnType<typeof vi.fn>;
    remoteChanges$: Subject<SessionMessage>;
  };
  let store: SessionStore;

  beforeEach(() => {
    api = {
      getMe: vi.fn(() => of(fixtureMe())),
      getSessionQuota: vi.fn(() => of(fixtureAnonymousQuota(1))),
      logout: vi.fn(() => of(undefined)),
    };
    coordinator = {
      refresh: vi.fn(async (): Promise<AuthTokenResponse | null> => null),
      announceSession: vi.fn(),
      announceSignedOut: vi.fn(),
      remoteChanges$: new Subject<SessionMessage>(),
    };
    TestBed.configureTestingModule({
      providers: [
        { provide: ApiClient, useValue: api },
        { provide: RefreshCoordinator, useValue: coordinator },
      ],
    });
    store = TestBed.inject(SessionStore);
  });

  it('starts pending with no principal', () => {
    expect(store.status()).toBe('pending');
    expect(store.principal()).toBeNull();
    expect(store.accessToken()).toBeNull();
  });

  it('bootstrap: a successful refresh loads /me and signs the user in', async () => {
    coordinator.refresh.mockResolvedValue(fixtureTokenResponse('tok-1'));
    await store.bootstrap();

    expect(store.status()).toBe('authenticated');
    expect(store.accessToken()).toBe('tok-1');
    expect(api.getMe).toHaveBeenCalledTimes(1);
    expect(api.getSessionQuota).not.toHaveBeenCalled();
    expect(store.plan()).toBe('FREE');
    expect(store.usage()?.savedProducts).toEqual({ used: 2, limit: 5 });
    expect(store.principal()).toBe('USER');
    expect(store.anonymousQuota()).toBeNull();
  });

  it('bootstrap: a failed refresh loads the anonymous quota', async () => {
    await store.bootstrap();

    expect(store.status()).toBe('anonymous');
    expect(store.principal()).toBe('ANONYMOUS');
    expect(store.accessToken()).toBeNull();
    expect(api.getMe).not.toHaveBeenCalled();
    expect(api.getSessionQuota).toHaveBeenCalledTimes(1);
    expect(store.anonymousQuota()).toEqual(fixtureAnonymousQuota(1));
  });

  it('bootstrap runs once and whenReady waits for it', async () => {
    const first = store.bootstrap();
    const ready = store.whenReady();
    expect(ready).toBe(first);
    await ready;
    expect(coordinator.refresh).toHaveBeenCalledTimes(1);
  });

  it('bootstrap: a failing /me keeps the session from the refresh', async () => {
    coordinator.refresh.mockResolvedValue(fixtureTokenResponse('tok-1'));
    api.getMe.mockReturnValue(throwError(() => problem(500, 'internal_error')));
    await store.bootstrap();
    expect(store.status()).toBe('authenticated');
    expect(store.user()?.email).toBe('user@example.com');
    expect(store.me()).toBeNull();
  });

  it('bootstrap: a 401 from /me clears the session', async () => {
    coordinator.refresh.mockResolvedValue(fixtureTokenResponse('tok-1'));
    api.getMe.mockReturnValue(throwError(() => problem(401, 'unauthorized')));
    await store.bootstrap();
    expect(store.status()).toBe('anonymous');
    expect(store.accessToken()).toBeNull();
  });

  it('bootstrap: an unavailable quota endpoint leaves the counters unknown', async () => {
    api.getSessionQuota.mockReturnValue(throwError(() => problem(429, 'rate_limited')));
    await store.bootstrap();
    expect(store.status()).toBe('anonymous');
    expect(store.anonymousQuota()).toBeNull();
  });

  it('signIn applies the token, announces it to other tabs and loads /me', async () => {
    await store.bootstrap();
    api.getMe.mockReturnValue(of(fixtureMe({ role: 'ADMIN' })));
    const response = fixtureTokenResponse('login-token', fixtureUser({ role: 'ADMIN' }));
    await store.signIn(response);
    expect(store.accessToken()).toBe('login-token');
    expect(coordinator.announceSession).toHaveBeenCalledWith(response);
    expect(store.isAdmin()).toBe(true);
    expect(store.anonymousQuota()).toBeNull();
  });

  it('signOut clears the session even when the server call fails', async () => {
    coordinator.refresh.mockResolvedValue(fixtureTokenResponse('tok-1'));
    await store.bootstrap();
    api.logout.mockReturnValue(throwError(() => problem(500)));
    await store.signOut();
    expect(store.status()).toBe('anonymous');
    expect(store.accessToken()).toBeNull();
    expect(store.me()).toBeNull();
    expect(coordinator.announceSignedOut).toHaveBeenCalled();
    expect(api.getSessionQuota).toHaveBeenCalled();
  });

  it('forget clears the session everywhere without calling /auth/logout', async () => {
    coordinator.refresh.mockResolvedValue(fixtureTokenResponse('tok-1'));
    await store.bootstrap();
    await store.forget();
    expect(api.logout).not.toHaveBeenCalled();
    expect(store.status()).toBe('anonymous');
    expect(store.accessToken()).toBeNull();
    expect(coordinator.announceSignedOut).toHaveBeenCalledTimes(1);
    expect(store.anonymousQuota()).toEqual(fixtureAnonymousQuota(1));
  });

  it('refreshAccessToken stores a new token or clears the session', async () => {
    coordinator.refresh.mockResolvedValue(fixtureTokenResponse('fresh'));
    await expect(store.refreshAccessToken()).resolves.toBe(true);
    expect(store.accessToken()).toBe('fresh');

    coordinator.refresh.mockResolvedValue(null);
    await expect(store.refreshAccessToken()).resolves.toBe(false);
    expect(store.accessToken()).toBeNull();
    expect(store.status()).toBe('anonymous');
  });

  it('applies a session refreshed or signed in by another tab', async () => {
    await store.bootstrap();
    coordinator.remoteChanges$.next({ type: 'session', response: fixtureTokenResponse('remote') });
    expect(store.accessToken()).toBe('remote');
    expect(store.isAuthenticated()).toBe(true);
    await Promise.resolve();
    expect(api.getMe).toHaveBeenCalled();
  });

  it('applies a sign-out made in another tab', async () => {
    coordinator.refresh.mockResolvedValue(fixtureTokenResponse('tok-1'));
    await store.bootstrap();
    coordinator.remoteChanges$.next({ type: 'signed-out' });
    expect(store.status()).toBe('anonymous');
    expect(store.accessToken()).toBeNull();
  });

  it('updates anonymous counters from query meta and quota problems only', async () => {
    await store.bootstrap();
    store.applyPrincipal({ type: 'ANONYMOUS', queriesUsed: 2, queriesLimit: 3 });
    expect(store.anonymousQuota()).toEqual({
      principal: 'ANONYMOUS',
      queriesUsed: 2,
      queriesLimit: 3,
      visibleResults: 3,
    });

    store.applyPrincipal({ type: 'USER', plan: 'FREE' });
    expect(store.anonymousQuota()?.queriesUsed).toBe(2);

    store.applyQuotaProblem(
      new ApiProblem({ kind: 'problem', status: 403, code: 'quota_exceeded', used: 3, limit: 3 }),
    );
    expect(store.anonymousQuota()?.queriesUsed).toBe(3);

    store.applyQuotaProblem(problem(403, 'forbidden'));
    expect(store.anonymousQuota()?.queriesUsed).toBe(3);
  });

  it('updates usage after a change reported by the server', async () => {
    coordinator.refresh.mockResolvedValue(fixtureTokenResponse('tok-1'));
    await store.bootstrap();
    const usage = { ...fixtureMe().usage, savedProducts: { used: 5, limit: 5 } };
    store.applyUsage(usage);
    expect(store.usage()?.savedProducts).toEqual({ used: 5, limit: 5 });
  });
});
