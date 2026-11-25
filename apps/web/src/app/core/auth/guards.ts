import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { loginQueryParams } from './return-url';
import { SessionStore } from './session.store';

/**
 * Lets signed-in users through; everyone else goes to `/auth/login?returnUrl=…`.
 * Waits for the session restore that runs on app start. The API re-checks every request.
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
 * Lets users with role ADMIN (from the `/me` payload) through. Anonymous visitors go to the
 * login page, signed-in users without the role to the home page. The API re-checks every request.
 */
export const adminGuard: CanActivateFn = async (_route, state) => {
  const session = inject(SessionStore);
  const router = inject(Router);
  await session.whenReady();
  if (!session.isAuthenticated()) {
    return router.createUrlTree(['/auth/login'], { queryParams: loginQueryParams(state.url) });
  }
  return session.isAdmin() ? true : router.createUrlTree(['/']);
};
