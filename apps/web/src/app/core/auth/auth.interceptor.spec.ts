import {
  HttpClient,
  HttpErrorResponse,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { authInterceptor } from './auth.interceptor';
import { SessionStore } from './session.store';

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

describe('authInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let token: string | null;
  let refreshResult: boolean;
  let session: { accessToken: () => string | null; refreshAccessToken: ReturnType<typeof vi.fn> };
  let navigate: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    token = 'old-token';
    refreshResult = true;
    session = {
      accessToken: () => token,
      refreshAccessToken: vi.fn(async () => {
        if (refreshResult) {
          token = 'new-token';
          return true;
        }
        token = null;
        return false;
      }),
    };
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: SessionStore, useValue: session },
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  });

  afterEach(() => backend.verify());

  it('attaches the bearer token to API requests', async () => {
    const result = firstValueFrom(http.get('/api/v1/me'));
    const request = backend.expectOne('/api/v1/me');
    expect(request.request.headers.get('Authorization')).toBe('Bearer old-token');
    request.flush({ id: 1 });
    await expect(result).resolves.toEqual({ id: 1 });
  });

  it('sends no Authorization header without a token', async () => {
    token = null;
    const result = firstValueFrom(http.get('/api/v1/countries'));
    const request = backend.expectOne('/api/v1/countries');
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush({ items: [] });
    await result;
  });

  it('leaves non-API requests untouched', async () => {
    const result = firstValueFrom(http.get('/i18n/en.json'));
    const request = backend.expectOne('/i18n/en.json');
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush({});
    await result;
  });

  it('adds the CSRF header to refresh and logout but no token to auth endpoints', async () => {
    const refresh = firstValueFrom(http.post('/api/v1/auth/refresh', {}));
    const logout = firstValueFrom(http.post('/api/v1/auth/logout', {}));
    const login = firstValueFrom(http.post('/api/v1/auth/login', {}));
    const refreshRequest = backend.expectOne('/api/v1/auth/refresh');
    const logoutRequest = backend.expectOne('/api/v1/auth/logout');
    const loginRequest = backend.expectOne('/api/v1/auth/login');
    expect(refreshRequest.request.headers.get('X-Requested-With')).toBe('vitadelta');
    expect(logoutRequest.request.headers.get('X-Requested-With')).toBe('vitadelta');
    expect(loginRequest.request.headers.has('X-Requested-With')).toBe(false);
    for (const request of [refreshRequest, logoutRequest, loginRequest]) {
      expect(request.request.headers.has('Authorization')).toBe(false);
      request.flush({});
    }
    await Promise.all([refresh, logout, login]);
  });

  it('refreshes once on 401 and retries the request once with the new token', async () => {
    const result = firstValueFrom(http.get('/api/v1/me/alerts'));
    backend.expectOne('/api/v1/me/alerts').flush(null, { status: 401, statusText: 'Unauthorized' });
    await flush();
    expect(session.refreshAccessToken).toHaveBeenCalledTimes(1);

    const retry = backend.expectOne('/api/v1/me/alerts');
    expect(retry.request.headers.get('Authorization')).toBe('Bearer new-token');
    retry.flush({ items: [] });
    await expect(result).resolves.toEqual({ items: [] });
  });

  it('does not refresh again when the retried request also gets 401', async () => {
    const result = firstValueFrom(http.get('/api/v1/me'));
    backend.expectOne('/api/v1/me').flush(null, { status: 401, statusText: 'Unauthorized' });
    await flush();
    backend.expectOne('/api/v1/me').flush(null, { status: 401, statusText: 'Unauthorized' });
    const error = await result.catch((e: unknown) => e);
    expect(error).toBeInstanceOf(HttpErrorResponse);
    expect((error as HttpErrorResponse).status).toBe(401);
    expect(session.refreshAccessToken).toHaveBeenCalledTimes(1);
  });

  it('retries without refreshing when another request already refreshed the token', async () => {
    const result = firstValueFrom(http.get('/api/v1/me'));
    const first = backend.expectOne('/api/v1/me');
    token = 'rotated-elsewhere';
    first.flush(null, { status: 401, statusText: 'Unauthorized' });
    const retry = backend.expectOne('/api/v1/me');
    expect(retry.request.headers.get('Authorization')).toBe('Bearer rotated-elsewhere');
    retry.flush({ ok: true });
    await expect(result).resolves.toEqual({ ok: true });
    expect(session.refreshAccessToken).not.toHaveBeenCalled();
  });

  it('routes to the login page with returnUrl when the refresh fails', async () => {
    refreshResult = false;
    const result = firstValueFrom(http.get('/api/v1/me/saved-products?country=TR'));
    backend
      .expectOne('/api/v1/me/saved-products?country=TR')
      .flush(null, { status: 401, statusText: 'Unauthorized' });
    const error = await result.catch((e: unknown) => e);
    expect((error as HttpErrorResponse).status).toBe(401);
    expect(navigate).toHaveBeenCalledWith(['/auth/login'], {
      queryParams: { reason: 'session_expired' },
    });
  });

  it('does not refresh for 401 responses of auth endpoints or other errors', async () => {
    const login = firstValueFrom(http.post('/api/v1/auth/login', {}));
    backend
      .expectOne('/api/v1/auth/login')
      .flush(null, { status: 401, statusText: 'Unauthorized' });
    await expect(login).rejects.toBeInstanceOf(HttpErrorResponse);

    const forbidden = firstValueFrom(http.get('/api/v1/admin/users'));
    backend.expectOne('/api/v1/admin/users').flush(null, { status: 403, statusText: 'Forbidden' });
    await expect(forbidden).rejects.toBeInstanceOf(HttpErrorResponse);
    expect(session.refreshAccessToken).not.toHaveBeenCalled();
  });
});
