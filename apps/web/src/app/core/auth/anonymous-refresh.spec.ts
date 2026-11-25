import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { fixtureAnonymousQuota, fixtureMe, fixtureTokenResponse } from '../../testing/api-fixtures';
import { ApiClient } from '../api/api-client';
import { ApiProblem } from '../api/api-problem';
import { authInterceptor } from './auth.interceptor';
import { SESSION_CHANNEL, SessionChannelLike, WEB_LOCKS } from './refresh-coordinator';
import { SessionStore } from './session.store';

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

/** Records what this tab would post to other tabs. */
class RecordingChannel implements SessionChannelLike {
  readonly sent: unknown[] = [];
  postMessage(message: unknown): void {
    this.sent.push(message);
  }
  addEventListener(): void {
    // Messages from other tabs are not part of these tests.
  }
  close(): void {
    this.sent.length = 0;
  }
}

const UNAUTHORIZED = {
  type: 'https://vitadelta.dev/problems/unauthorized',
  title: 'Unauthorized',
  status: 401,
};

/**
 * Session start and silent refresh against the HTTP layer: `204` from `POST /auth/refresh` (no
 * refresh cookie) is the ordinary anonymous answer, `401` stays a refused session.
 */
describe('anonymous refresh (204) and refused refresh (401)', () => {
  let http: HttpTestingController;
  let channel: RecordingChannel;
  let store: SessionStore;
  let navigate: ReturnType<typeof vi.spyOn>;
  let consoleError: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    channel = new RecordingChannel();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: WEB_LOCKS, useValue: null },
        { provide: SESSION_CHANNEL, useValue: channel },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(SessionStore);
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    consoleError = vi.spyOn(console, 'error');
  });

  afterEach(() => {
    http.verify();
    consoleError.mockRestore();
  });

  async function answerRefresh(
    body: object | null,
    init: { status: number; statusText: string },
  ): Promise<void> {
    await flush();
    const request = http.expectOne('/api/v1/auth/refresh');
    expect(request.request.method).toBe('POST');
    expect(request.request.withCredentials).toBe(true);
    request.flush(body, init);
    await flush();
  }

  async function answerQuota(): Promise<void> {
    await flush();
    http.expectOne('/api/v1/session/quota').flush(fixtureAnonymousQuota(1));
    await flush();
  }

  it('bootstrap: 204 starts an anonymous session without clearing, announcing or logging', async () => {
    const generation = store.generation();
    const ready = store.bootstrap();
    await answerRefresh(null, { status: 204, statusText: 'No Content' });
    await answerQuota();
    await ready;

    expect(store.status()).toBe('anonymous');
    expect(store.principal()).toBe('ANONYMOUS');
    expect(store.accessToken()).toBeNull();
    expect(store.anonymousQuota()).toEqual(fixtureAnonymousQuota(1));
    expect(store.generation()).toBe(generation);
    expect(channel.sent).toEqual([]);
    expect(consoleError).not.toHaveBeenCalled();
    // No retry, no logout, no /me.
    http.expectNone('/api/v1/auth/refresh');
    http.expectNone('/api/v1/auth/logout');
    http.expectNone('/api/v1/me');
  });

  it('bootstrap: a refused cookie (401) also ends anonymous, as before', async () => {
    const ready = store.bootstrap();
    await answerRefresh(UNAUTHORIZED, { status: 401, statusText: 'Unauthorized' });
    await answerQuota();
    await ready;

    expect(store.status()).toBe('anonymous');
    expect(store.accessToken()).toBeNull();
    expect(channel.sent).toEqual([]);
    http.expectNone('/api/v1/auth/refresh');
  });

  it('a protected call refused while anonymous fails once after a 204, then routes to login', async () => {
    const ready = store.bootstrap();
    await answerRefresh(null, { status: 204, statusText: 'No Content' });
    await answerQuota();
    await ready;

    const api = TestBed.inject(ApiClient);
    const result = firstValueFrom(api.getMe()).catch((error: unknown) => error);
    await flush();
    http.expectOne('/api/v1/me').flush(UNAUTHORIZED, { status: 401, statusText: 'Unauthorized' });
    await answerRefresh(null, { status: 204, statusText: 'No Content' });

    const error = await result;
    expect(error).toBeInstanceOf(ApiProblem);
    expect((error as ApiProblem).status).toBe(401);
    // One refresh, no replay of /me, no loop.
    http.expectNone('/api/v1/auth/refresh');
    http.expectNone('/api/v1/me');
    expect(navigate).toHaveBeenCalledTimes(1);
    expect(navigate.mock.calls[0][0]).toEqual(['/auth/login']);
    expect(store.status()).toBe('anonymous');
    expect(channel.sent).toEqual([]);
  });

  it('a signed-in tab whose refresh is refused (401) still ends the session and asks to log in', async () => {
    const ready = store.bootstrap();
    await answerRefresh(fixtureTokenResponse('tok-1'), { status: 200, statusText: 'OK' });
    await flush();
    http.expectOne('/api/v1/me').flush(fixtureMe());
    await ready;
    expect(store.status()).toBe('authenticated');
    const signedIn = store.generation();
    channel.sent.length = 0;

    const refreshing = store.refreshAccessToken();
    await answerRefresh(UNAUTHORIZED, { status: 401, statusText: 'Unauthorized' });
    await expect(refreshing).resolves.toBe('failed');
    expect(store.status()).toBe('anonymous');
    expect(store.accessToken()).toBeNull();
    expect(store.generation()).toBe(signedIn + 1);
    expect(channel.sent).toEqual([]);
  });
});
