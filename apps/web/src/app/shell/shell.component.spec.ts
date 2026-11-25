import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { TranslocoService } from '@jsverse/transloco';
import { Subject, of } from 'rxjs';

import {
  FIXTURE_COUNTRIES,
  fixtureAnonymousQuota,
  fixtureMe,
  fixtureTokenResponse,
  fixtureUser,
} from '../testing/api-fixtures';
import { EN, translocoTesting } from '../testing/transloco-testing';
import { ApiClient } from '../core/api/api-client';
import { AuthTokenResponse } from '../core/api/api-types';
import { authGuard } from '../core/auth/guards';
import { RefreshCoordinator, SessionMessage } from '../core/auth/refresh-coordinator';
import { CurrencyStore } from '../core/state/currency.store';
import { QueryStore } from '../core/state/query.store';
import { RegionStore } from '../core/state/region.store';
import { UNREAD_POLL_INTERVAL_MS } from '../core/state/unread-count.store';
import { ShellComponent } from './shell.component';

const settle = async (fixture: ComponentFixture<unknown>) => {
  for (let i = 0; i < 3; i++) {
    await Promise.resolve();
    await fixture.whenStable();
  }
};

describe('ShellComponent', () => {
  let api: Record<string, ReturnType<typeof vi.fn>>;
  let refreshResult: AuthTokenResponse | null;
  let fixture: ComponentFixture<ShellComponent>;

  const host = () => fixture.nativeElement as HTMLElement;
  const text = () => host().textContent?.replace(/\s+/g, ' ') ?? '';

  async function render(): Promise<void> {
    TestBed.configureTestingModule({
      imports: [ShellComponent, translocoTesting()],
      providers: [
        provideRouter([]),
        { provide: ApiClient, useValue: api },
        {
          provide: RefreshCoordinator,
          useValue: {
            refresh: vi.fn(async () => refreshResult),
            announceSession: vi.fn(),
            announceSignedOut: vi.fn(),
            invalidate: vi.fn(),
            remoteChanges$: new Subject<SessionMessage>(),
          },
        },
      ],
    });
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    fixture = TestBed.createComponent(ShellComponent);
    await settle(fixture);
  }

  beforeEach(() => {
    refreshResult = null;
    try {
      localStorage.clear();
    } catch {
      // Storage is optional in the test environment as well.
    }
    api = {
      listCountries: vi.fn(() => of({ items: [...FIXTURE_COUNTRIES] })),
      listFxCurrencies: vi.fn(() =>
        of({
          items: [
            { code: 'EUR', asOf: '2026-11-24', stale: false },
            { code: 'TRY', asOf: '2026-11-24', stale: false },
            { code: 'USD', asOf: '2026-11-10', stale: true },
          ],
        }),
      ),
      getSessionQuota: vi.fn(() => of(fixtureAnonymousQuota(1))),
      getMe: vi.fn(() => of(fixtureMe())),
      getUnreadNotificationCount: vi.fn(() => of({ unreadCount: 4 })),
      updateMe: vi.fn(() => of(fixtureMe({ locale: 'tr' }))),
      logout: vi.fn(() => of(undefined)),
    };
  });

  afterEach(() => vi.useRealTimers());

  it('renders the anonymous header, quota chip, auth links and footer', async () => {
    await render();
    expect(host().querySelector('.skip-link')?.textContent?.trim()).toBe(EN.shell.skip_link);
    expect(text()).toContain(EN.shell.nav.find);
    expect(text()).toContain(EN.shell.nav.pro);
    expect(text()).toContain('2/3 free queries left');
    expect(text()).toContain(EN.shell.auth.login);
    expect(text()).toContain(EN.shell.auth.register);
    expect(text()).toContain(EN.disclaimer.footer);
    expect(host().querySelector('footer a[href="/privacy"]')?.textContent?.trim()).toBe(
      EN.legal.privacy,
    );
    expect(host().querySelector('vd-notification-bell')).toBeNull();

    const country = host().querySelector<HTMLSelectElement>('select[id^="vd-country"]');
    // The browser language (en) picks the first country whose default locale is en.
    expect(country?.value).toBe('DE');
    expect(Array.from(country?.options ?? []).map((option) => option.value)).toEqual([
      'TR',
      'DE',
      'US',
    ]);
    const currency = host().querySelector<HTMLSelectElement>('select[id^="vd-currency"]');
    expect(Array.from(currency?.options ?? []).map((option) => option.textContent?.trim())).toEqual(
      ['EUR', 'TRY', 'USD'],
    );
    expect(host().querySelector('.ctl-meta')?.textContent?.trim()).toBe(EN.shell.currency.native);
  });

  it('renders the signed-in header with bell, account menu and member navigation', async () => {
    refreshResult = fixtureTokenResponse('tok', fixtureUser({ role: 'ADMIN' }));
    api['getMe'] = vi.fn(() => of(fixtureMe({ role: 'ADMIN' })));
    await render();
    expect(text()).toContain(EN.shell.nav.saved);
    expect(text()).toContain(EN.shell.nav.alerts);
    expect(text()).toContain(EN.shell.nav.admin);
    expect(text()).not.toContain(EN.shell.auth.register);
    const bell = host().querySelector('vd-notification-bell a');
    expect(bell?.getAttribute('aria-label')).toBe('Notifications, 4 unread');
    expect(bell?.textContent?.trim()).toBe('4');
    expect(host().querySelector('vd-user-menu button')?.getAttribute('aria-haspopup')).toBe('menu');
    expect(host().querySelector('vd-quota-chip')).toBeNull();
  });

  it('polls the unread count every 60 seconds while signed in', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    refreshResult = fixtureTokenResponse();
    await render();
    expect(api['getUnreadNotificationCount']).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(UNREAD_POLL_INTERVAL_MS);
    expect(api['getUnreadNotificationCount']).toHaveBeenCalledTimes(2);
    vi.advanceTimersByTime(UNREAD_POLL_INTERVAL_MS);
    expect(api['getUnreadNotificationCount']).toHaveBeenCalledTimes(3);
  });

  it('switches the language with the toggle', async () => {
    await render();
    const tr = host().querySelector<HTMLButtonElement>('.lang button[lang="tr"]');
    expect(tr?.getAttribute('aria-pressed')).toBe('false');
    tr?.click();
    await settle(fixture);
    expect(TestBed.inject(TranslocoService).getActiveLang()).toBe('tr');
    expect(document.documentElement.lang).toBe('tr');
    expect(tr?.getAttribute('aria-pressed')).toBe('true');
  });

  it('updates region, query and currency stores from the header selects', async () => {
    await render();
    const country = host().querySelector<HTMLSelectElement>('select[id^="vd-country"]');
    if (!country) {
      throw new Error('country select missing');
    }
    country.value = 'TR';
    country.dispatchEvent(new Event('change'));
    await settle(fixture);
    expect(TestBed.inject(RegionStore).countryCode()).toBe('TR');
    expect(TestBed.inject(QueryStore).state().country).toBe('TR');

    const currency = host().querySelector<HTMLSelectElement>('select[id^="vd-currency"]');
    if (!currency) {
      throw new Error('currency select missing');
    }
    currency.value = 'USD';
    currency.dispatchEvent(new Event('change'));
    await settle(fixture);
    expect(TestBed.inject(CurrencyStore).displayCurrency()).toBe('USD');
    expect(host().querySelector('.ctl-meta')?.getAttribute('data-state')).toBe('stale');
  });

  it('moves focus to the main region from the skip link', async () => {
    await render();
    host().querySelector<HTMLAnchorElement>('.skip-link')?.click();
    expect(document.activeElement?.id).toBe('main');
  });

  it('opens and closes the mobile menu sheet', async () => {
    await render();
    const button = host().querySelector<HTMLButtonElement>('.menu-btn');
    if (!button) {
      throw new Error('menu button missing');
    }
    // The button is hidden by the desktop layout; show it so it can take focus.
    button.style.display = 'inline-flex';
    button.focus();
    button?.click();
    await settle(fixture);
    const dialog = host().querySelector<HTMLElement>('[role="dialog"]');
    expect(dialog).not.toBeNull();
    expect(dialog?.getAttribute('aria-labelledby')).toBe('vd-sheet-title');
    expect(document.activeElement?.getAttribute('aria-label')).toBe(EN.shell.menu.close);
    expect(host().querySelector('header')?.inert).toBe(true);

    dialog?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await settle(fixture);
    expect(host().querySelector('[role="dialog"]')).toBeNull();
    expect(host().querySelector('header')?.inert).toBe(false);
    expect(document.activeElement).toBe(button);
  });

  describe('after a session change in another tab', () => {
    let remote: Subject<SessionMessage>;
    const ACCOUNT_B = fixtureUser({
      id: '0b0b0b0b-0000-4000-8000-00000000000b',
      email: 'other@example.com',
    });

    async function renderWithRoutes(): Promise<Router> {
      remote = new Subject<SessionMessage>();
      TestBed.configureTestingModule({
        imports: [ShellComponent, translocoTesting()],
        providers: [
          provideRouter([
            {
              path: 'account',
              canActivate: [authGuard],
              runGuardsAndResolvers: 'always',
              children: [],
            },
            { path: 'auth/login', children: [] },
          ]),
          { provide: ApiClient, useValue: api },
          {
            provide: RefreshCoordinator,
            useValue: {
              refresh: vi.fn(async () => refreshResult),
              announceSession: vi.fn(),
              announceSignedOut: vi.fn(),
              invalidate: vi.fn(),
              remoteChanges$: remote,
            },
          },
        ],
      });
      fixture = TestBed.createComponent(ShellComponent);
      await settle(fixture);
      const router = TestBed.inject(Router);
      await router.navigateByUrl('/account');
      await settle(fixture);
      return router;
    }

    it('re-runs the guards of the mounted page and sends it to login after a sign-out', async () => {
      refreshResult = fixtureTokenResponse('tok-a');
      const router = await renderWithRoutes();
      expect(router.url).toBe('/account');

      remote.next({ type: 'signed-out', at: Date.now() });
      await settle(fixture);
      await settle(fixture);
      expect(router.url).toBe('/auth/login?returnUrl=%2Faccount');
    });

    it('reloads the unread count for the account signed in elsewhere', async () => {
      refreshResult = fixtureTokenResponse('tok-a');
      await renderWithRoutes();
      expect(api['getUnreadNotificationCount']).toHaveBeenCalledTimes(1);

      api['getUnreadNotificationCount'] = vi.fn(() => of({ unreadCount: 9 }));
      api['getMe'] = vi.fn(() => of(fixtureMe({ ...ACCOUNT_B })));
      remote.next({
        type: 'session',
        response: fixtureTokenResponse('tok-b', ACCOUNT_B),
        at: Date.now(),
      });
      await settle(fixture);
      expect(api['getUnreadNotificationCount']).toHaveBeenCalledTimes(1);
      expect(host().querySelector('vd-notification-bell a')?.textContent?.trim()).toBe('9');
    });
  });
});
