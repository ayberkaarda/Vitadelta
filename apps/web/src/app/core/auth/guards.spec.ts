import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  Router,
  RouterStateSnapshot,
  UrlTree,
  provideRouter,
} from '@angular/router';

import { adminGuard, authGuard } from './guards';
import { SessionStore } from './session.store';

function run(guard: typeof authGuard, url: string) {
  return TestBed.runInInjectionContext(() =>
    guard({} as ActivatedRouteSnapshot, { url } as RouterStateSnapshot),
  );
}

describe('route guards', () => {
  let authenticated: boolean;
  let admin: boolean;

  beforeEach(() => {
    authenticated = false;
    admin = false;
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: SessionStore,
          useValue: {
            whenReady: () => Promise.resolve(),
            isAuthenticated: () => authenticated,
            isAdmin: () => admin,
          },
        },
      ],
    });
  });

  const serialize = (tree: unknown) => TestBed.inject(Router).serializeUrl(tree as UrlTree);

  it('authGuard sends anonymous visitors to login with returnUrl', async () => {
    const result = await run(authGuard, '/account/saved');
    expect(serialize(result)).toBe('/auth/login?returnUrl=%2Faccount%2Fsaved');
  });

  it('authGuard lets signed-in users through', async () => {
    authenticated = true;
    await expect(run(authGuard, '/account')).resolves.toBe(true);
  });

  it('adminGuard sends anonymous visitors to login', async () => {
    const result = await run(adminGuard, '/admin/users');
    expect(serialize(result)).toBe('/auth/login?returnUrl=%2Fadmin%2Fusers');
  });

  it('adminGuard sends signed-in users without the role home', async () => {
    authenticated = true;
    expect(serialize(await run(adminGuard, '/admin'))).toBe('/');
  });

  it('adminGuard lets admins through', async () => {
    authenticated = true;
    admin = true;
    await expect(run(adminGuard, '/admin')).resolves.toBe(true);
  });
});
