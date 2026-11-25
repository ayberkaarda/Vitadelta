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
  /** Outcome of `/me` for the current session generation. */
  let currentMe: Promise<boolean>;
  let ensureMe: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    authenticated = false;
    admin = false;
    currentMe = Promise.resolve(true);
    ensureMe = vi.fn(() => currentMe);
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: SessionStore,
          useValue: {
            whenReady: () => Promise.resolve(),
            isAuthenticated: () => authenticated,
            isAdmin: () => admin,
            ensureMe,
            me: () => (admin ? { role: 'ADMIN' } : { role: 'USER' }),
          },
        },
      ],
    });
  });

  it('adminGuard refuses an admin token when /me of the current session fails', async () => {
    authenticated = true;
    admin = true;
    currentMe = Promise.resolve(false);
    expect(serialize(await run(adminGuard, '/admin'))).toBe('/');
    expect(ensureMe).toHaveBeenCalled();
  });

  it('adminGuard waits for a pending /me of the current session before admitting', async () => {
    authenticated = true;
    admin = true;
    let finish: (ok: boolean) => void = () => undefined;
    currentMe = new Promise<boolean>((resolve) => (finish = resolve));
    let settled = false;
    const result = Promise.resolve(run(adminGuard, '/admin')).then((value) => {
      settled = true;
      return value;
    });
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(settled).toBe(false);
    finish(true);
    await expect(result).resolves.toBe(true);
  });

  it('protected areas re-run their guards when the session changes', async () => {
    const account = (await import('../../features/account/account.routes')).default;
    const adminRoutes = (await import('../../features/admin/admin.routes')).default;
    const guarded = [...account, ...adminRoutes].flatMap((route) =>
      route.children ? route.children : [route],
    );
    const withGuards = guarded.filter((route) => (route.canActivate ?? []).length > 0);
    expect(withGuards).toHaveLength(2);
    for (const route of withGuards) {
      expect(route.runGuardsAndResolvers).toBe('always');
    }
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
