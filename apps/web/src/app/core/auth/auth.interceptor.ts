import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, from, switchMap, throwError } from 'rxjs';

import { API_BASE, CSRF_HEADER, CSRF_VALUE } from '../api/api-client';
import { loginQueryParams } from './return-url';
import { SessionStore } from './session.store';

const AUTH_PREFIX = `${API_BASE}/auth/`;
const CSRF_PATHS: ReadonlySet<string> = new Set([
  `${API_BASE}/auth/refresh`,
  `${API_BASE}/auth/logout`,
]);

function withBearer(request: HttpRequest<unknown>, token: string | null): HttpRequest<unknown> {
  return token ? request.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : request;
}

/**
 * Project specification §10.4:
 * - attaches the in-memory access token to API requests (never to `/auth/*`);
 * - sends `X-Requested-With: vitadelta` on `/auth/refresh` and `/auth/logout`;
 * - on a `401` from a non-auth endpoint performs one silent refresh (single-flight) and retries
 *   the request once; when the refresh fails the session is cleared and the user is routed to
 *   `/auth/login` with a `returnUrl`;
 * - a request remembers the session generation it was sent under and is never retried after
 *   the generation changed (sign-out, sign-in or account switch, also in another tab).
 */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  if (!request.url.startsWith(`${API_BASE}/`)) {
    return next(request);
  }

  if (request.url.startsWith(AUTH_PREFIX)) {
    const path = request.url.split('?')[0];
    return next(
      CSRF_PATHS.has(path) ? request.clone({ setHeaders: { [CSRF_HEADER]: CSRF_VALUE } }) : request,
    );
  }

  const session = inject(SessionStore);
  const router = inject(Router);
  const generation = session.generation();
  const sentToken = session.accessToken();

  return next(withBearer(request, sentToken)).pipe(
    catchError((error: unknown) => {
      if (!(error instanceof HttpErrorResponse) || error.status !== 401) {
        return throwError(() => error);
      }
      if (session.generation() !== generation) {
        // The identity changed while the request was in flight: never replay it for another
        // account (or after a sign-out).
        return throwError(() => error);
      }
      const current = session.accessToken();
      if (current && current !== sentToken) {
        // Another caller refreshed the token of the same session while this request was in flight.
        return next(withBearer(request, current));
      }
      return from(session.refreshAccessToken()).pipe(
        switchMap((outcome) => {
          if (outcome === 'refreshed' && session.generation() === generation) {
            return next(withBearer(request, session.accessToken()));
          }
          if (outcome === 'failed') {
            void router.navigate(['/auth/login'], {
              queryParams: loginQueryParams(router.url, sentToken ? 'session_expired' : undefined),
            });
          }
          return throwError(() => error);
        }),
      );
    }),
  );
};
