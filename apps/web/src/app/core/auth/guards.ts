import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { loginQueryParams } from './return-url';
import { SessionStore } from './session.store';

/**
 * Lets signed-in users through; everyone else goes to `/auth/login?returnUrl=…`.
 * Waits for the session restore that runs on app start. The API re-checks every request.
 * Protected routes use `runGuardsAndResolvers: 'always'` so the shell can re-run these guards
 * for a page that is already mounted when the session changes.
 */
export const authGuard: CanActivateFn = async (_route, state) => {
  const session = inject(SessionStore);
  const router = inject(Router);
  await session.whenReady();
  return session.isAuthenticated()
    ? true
    : router.createUrlTree(['/auth/login'], { queryParams: loginQueryParams(state.url) });
};

/**
 * Lets users with role ADMIN through, decided only by a successful `/me` of the current session
 * generation (a token response alone is not enough, and a failed or pending `/me` never admits).
 * Anonymous visitors go to the login page, everyone else to the home page. The API re-checks
 * every request.
 */
export const adminGuard: CanActivateFn = async (_route, state) => {
  const session = inject(SessionStore);
  const router = inject(Router);
  await session.whenReady();
  if (!session.isAuthenticated()) {
    return router.createUrlTree(['/auth/login'], { queryParams: loginQueryParams(state.url) });
  }
  const confirmed = await session.ensureMe();
  if (!session.isAuthenticated()) {
    return router.createUrlTree(['/auth/login'], { queryParams: loginQueryParams(state.url) });
  }
  return confirmed && session.me()?.role === 'ADMIN' ? true : router.createUrlTree(['/']);
};
