import { HttpBackend, provideHttpClient, withInterceptors } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, TitleStrategy, provideRouter, withComponentInputBinding } from '@angular/router';
import { provideTransloco } from '@jsverse/transloco';

import authEn from '../../public/i18n/auth/en.json';
import authTr from '../../public/i18n/auth/tr.json';
import privacyTr from '../../public/i18n/privacy/tr.json';
import resultsTr from '../../public/i18n/results/tr.json';
import { routes } from './app.routes';
import { authInterceptor } from './core/auth/auth.interceptor';
import { SESSION_CHANNEL, WEB_LOCKS } from './core/auth/refresh-coordinator';
import { I18nTitleStrategy } from './core/i18n/i18n-title.strategy';
import { TranslocoHttpLoader } from './core/i18n/transloco-http-loader';
import { UnreadCountStore } from './core/state/unread-count.store';
import { ShellComponent } from './shell/shell.component';
import { EN, TR } from './testing/transloco-testing';
import { ANONYMOUS_VISIBLE, FakeBackend } from './testing/fake-backend';

// Cross-feature flows against the stateful fake API: real routes and lazy features, real
// stores, the real auth interceptor and translation loading. Only the network is replaced.

describe('application flows', () => {
  let backend: FakeBackend;
  let fixture: ComponentFixture<ShellComponent>;
  let router: Router;

  const host = () => fixture.nativeElement as HTMLElement;
  const $ = <T extends Element = HTMLElement>(selector: string) =>
    host().querySelector<T>(selector);
  const $$ = (selector: string) => Array.from(host().querySelectorAll(selector));
  const text = (element: Element | null) => element?.textContent?.replace(/\s+/g, ' ').trim();

  /** Lets every pending fake response, lazy chunk and render settle. */
  async function settle(rounds = 12): Promise<void> {
    for (let index = 0; index < rounds; index++) {
      await new Promise((resolve) => setTimeout(resolve, 0));
      await fixture.whenStable();
    }
  }

  async function go(url: string): Promise<void> {
    await router.navigateByUrl(url);
    await settle();
  }

  function type(selector: string, value: string): void {
    const input = $<HTMLInputElement>(selector);
    if (!input) {
      throw new Error(`No input ${selector}`);
    }
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  async function submit(selector = 'form'): Promise<void> {
    $(selector)?.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle();
  }

  async function click(selector: string): Promise<void> {
    const element = $(selector);
    if (!element) {
      throw new Error(`Nothing matches ${selector}`);
    }
    element.click();
    await settle();
  }

  async function start(seed?: (backend: FakeBackend) => void): Promise<void> {
    localStorage.clear();
    localStorage.setItem('vd.lang', 'en');
    localStorage.setItem('vd.country', 'TR');
    window.history.replaceState(null, '', '/');
    backend = new FakeBackend();
    seed?.(backend);
    TestBed.configureTestingModule({
      providers: [
        { provide: FakeBackend, useValue: backend },
        provideHttpClient(withInterceptors([authInterceptor])),
        { provide: HttpBackend, useValue: backend },
        provideRouter(routes, withComponentInputBinding()),
        { provide: TitleStrategy, useExisting: I18nTitleStrategy },
        provideTransloco({
          config: {
            availableLangs: ['en', 'tr'],
            defaultLang: 'en',
            fallbackLang: 'en',
            missingHandler: { logMissingKey: false, useFallbackTranslation: true },
            reRenderOnLangChange: true,
            prodMode: true,
          },
          loader: TranslocoHttpLoader,
        }),
        { provide: SESSION_CHANNEL, useValue: null },
        { provide: WEB_LOCKS, useValue: null },
      ],
    });
    router = TestBed.inject(Router);
    fixture = TestBed.createComponent(ShellComponent);
    await settle();
  }

  afterEach(() => {
    fixture?.destroy();
    localStorage.clear();
  });

  it('runs from an anonymous query through sign-up, verification and login back to the results, then save, alert, account pages and the bell', async () => {
    await start();
    await go('/results?country=TR');

    // Anonymous: three visible rows, locked rows from meta.visibility only, no token sent.
    expect($$('vd-results-table [data-product]')).toHaveLength(ANONYMOUS_VISIBLE);
    expect(
      $$('vd-locked-results li.locked-row:not(.tail)').map((row) => row.getAttribute('aria-label')),
    ).toEqual(['Result 4, locked', 'Result 5, locked', 'Result 6, locked']);
    const anonymousQuery = backend.requests.find((r) => r.path === '/recommendations/query');
    expect(anonymousQuery?.authorization).toBeNull();
    expect(text($('vd-quota-chip'))).toContain('2/3');

    // Register from the locked-results call to action.
    await click('vd-locked-results a[href="/auth/register"]');
    expect(router.url).toBe('/auth/register');
    type('#register-email', 'new.user@example.com');
    type('#register-password', 'a-long-enough-password');
    await submit();
    expect(backend.count('POST', '/auth/register')).toBe(1);
    expect(text(host())).toContain(authEn.register.sent_title);

    // Verify through the e-mail link: the fragment token is posted and removed from the URL.
    // The link opens a fresh page: the fragment is in the address bar before the app routes.
    const token = backend.lastVerificationToken();
    window.history.replaceState(null, '', `/auth/verify#token=${token}`);
    await go(`/auth/verify#token=${token}`);
    const verify = backend.requests.find((r) => r.path === '/auth/verify-email');
    expect(verify?.body).toEqual({ token });
    expect(window.location.hash).toBe('');
    expect(window.location.href).not.toContain(token ?? 'missing');
    expect(text(host())).toContain(authEn.verify.success_title);

    // Log in with a return URL: back to the same results, now complete and authorised.
    await go('/auth/login?returnUrl=%2Fresults%3Fcountry%3DTR');
    type('#login-email', 'new.user@example.com');
    type('#login-password', 'a-long-enough-password');
    await submit();
    expect(router.url).toBe('/results?country=TR');
    expect($$('vd-results-table [data-product]')).toHaveLength(6);
    expect($('vd-locked-results li')).toBeNull();
    const signedQuery = backend.requests.filter((r) => r.path === '/recommendations/query').at(-1);
    expect(signedQuery?.authorization).toMatch(/^Bearer access-\d+$/);
    expect(localStorage.getItem('vd.token')).toBeNull();
    for (let index = 0; index < localStorage.length; index++) {
      const key = localStorage.key(index) ?? '';
      expect(localStorage.getItem(key)).not.toMatch(/access-\d+/);
    }

    // Save one product and create a price alert for another from the result rows.
    await click('[data-product="GOLD-A"] button[data-action="save"]');
    expect(backend.count('POST', '/me/saved-products')).toBe(1);
    expect(
      $('[data-product="GOLD-A"] button[data-action="save"]')?.getAttribute('aria-pressed'),
    ).toBe('true');
    await click('[data-product="DAILY-60"] button[data-action="alert"]');
    expect(
      backend.requests.find((r) => r.path === '/me/alerts' && r.method === 'POST')?.body,
    ).toEqual(expect.objectContaining({ trigger: 'PRICE_DROP', countryCode: 'TR' }));

    // The account pages reflect both.
    await go('/account/saved');
    const savedText = text($('main'));
    expect(savedText).toContain('Gold Multi A');
    expect(savedText).toContain('Daily Complete 60');
    await go('/account/alerts');
    expect(text($('main'))).toContain('Daily Complete 60');

    // A new notification shows up in the bell count.
    expect($('vd-notification-bell .count')).toBeNull();
    backend.addUnread(2);
    await TestBed.inject(UnreadCountStore).refresh();
    await settle();
    expect(text($('vd-notification-bell .count'))).toBe('2');
    expect($('vd-notification-bell a')?.getAttribute('aria-label')).toContain('2');
  });

  it('keeps signed-in users without the ADMIN role out of the admin area', async () => {
    await start((b) => b.seedSession('USER'));
    await go('/admin/imports');
    expect(router.url).toBe('/');
    expect(backend.requests.some((r) => r.path.startsWith('/admin/'))).toBe(false);
  });

  it('sends anonymous visitors from the admin area to the login page', async () => {
    await start();
    await go('/admin/users');
    expect(router.url).toBe('/auth/login?returnUrl=%2Fadmin%2Fusers');
  });

  it('opens the admin area for an ADMIN', async () => {
    await start((b) => b.seedSession('ADMIN'));
    await go('/admin/promo-codes');
    expect(router.url).toBe('/admin/promo-codes');
    expect(backend.count('GET', '/admin/promo-codes')).toBe(1);
  });

  it('keeps the chosen language across lazy features and reloads', async () => {
    await start();
    await go('/results?country=TR');
    expect(text($('h1'))).toContain('products ranked');

    await click('.lang button[lang="tr"]');
    expect(document.documentElement.lang).toBe('tr');
    expect(localStorage.getItem('vd.lang')).toBe('tr');
    expect(text($('h1'))).toBe(resultsTr.title.replace('{{ total }}', '6'));
    expect(text($('footer'))).toContain(TR.disclaimer.footer);

    await go('/privacy');
    expect(text($('h1'))).toBe(privacyTr.title);
    await go('/auth/login');
    expect(text($('h1'))).toBe(authTr.login.title);
    expect(document.title).toBe(`${authTr.login.page_title} · VitaDelta`);
    expect(text($('footer'))).not.toContain(EN.disclaimer.footer);
  });

  it('re-renders money everywhere when the display currency changes', async () => {
    await start((b) => b.seedSession('USER'));
    await go('/results?country=TR');
    const cost = () => text($('[data-product="GOLD-A"] vd-money'));
    expect(cost()).toMatch(/^TRY 13.33/);
    await click('[data-product="GOLD-A"] button[data-action="save"]');

    const select = $<HTMLSelectElement>('header select[id^="vd-currency-"]');
    expect(select).not.toBeNull();
    if (select) {
      select.value = 'EUR';
      select.dispatchEvent(new Event('change'));
    }
    await settle();
    const query = backend.requests.filter((r) => r.path === '/recommendations/query').at(-1);
    expect(query?.body).toEqual(expect.objectContaining({ displayCurrency: 'EUR' }));
    // Converted amount first; the native amount stays available in the tooltip.
    expect(cost()).toMatch(/^€0.30/);
    expect(cost()).toContain('TRY 13.33');
    expect(text($('vd-results-page .toolbar'))).toContain('€');

    await go('/account/saved');
    const saved = backend.requests.filter((r) => r.path === '/me/saved-products').at(-1);
    expect(saved?.query['displayCurrency']).toBe('EUR');
    expect(text($('main'))).toContain('€');
    expect(localStorage.getItem('vd.currency')).toBe('EUR');
  });

  it('refreshes an expired access token once and retries the requests', async () => {
    await start((b) => b.seedSession('USER'));
    await go('/account');
    const refreshesBefore = backend.count('POST', '/auth/refresh');
    backend.expireAccessTokens();

    await go('/account/alerts');
    expect(backend.count('POST', '/auth/refresh')).toBe(refreshesBefore + 1);
    const alertCalls = backend.requests.filter((r) => r.path === '/me/alerts');
    expect(alertCalls.map((r) => r.authorization)).toHaveLength(2);
    expect(alertCalls[0].authorization).not.toBe(alertCalls[1].authorization);
    expect(router.url).toBe('/account/alerts');
    expect($('vd-empty-state')).not.toBeNull();
  });

  it('sends the user to the login page when the refresh fails', async () => {
    await start((b) => b.seedSession('USER'));
    await go('/account');
    backend.expireAccessTokens();
    backend.breakRefresh();

    await go('/account/alerts');
    expect(router.url).toBe('/auth/login?returnUrl=%2Faccount%2Falerts&reason=session_expired');
    expect(text(host())).toContain(authEn.login.session_ended);
    expect($('vd-notification-bell')).toBeNull();
  });
});
