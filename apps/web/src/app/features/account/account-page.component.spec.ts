import { computed, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import accountEn from '../../../../public/i18n/account/en.json';
import accountTr from '../../../../public/i18n/account/tr.json';
import { ApiClient } from '../../core/api/api-client';
import { ApiProblem } from '../../core/api/api-problem';
import { Me, SubscriptionActivation } from '../../core/api/api-types';
import { SessionStore } from '../../core/auth/session.store';
import { CurrencyStore } from '../../core/state/currency.store';
import { RegionStore } from '../../core/state/region.store';
import { FIXTURE_COUNTRIES, fixtureMe } from '../../testing/api-fixtures';
import { EN, translocoTesting } from '../../testing/transloco-testing';
import { AccountPageComponent } from './account-page.component';

const PRO_ME: Me = fixtureMe({
  plan: {
    code: 'PRO',
    name: 'Pro',
    savedProductsLimit: null,
    alertSubscriptionsLimit: null,
    notificationsPerMonthLimit: null,
  },
  subscription: {
    id: '5e6f7a8b-9c0d-4e1f-8a2b-3c4d5e6f7a8b',
    planCode: 'PRO',
    status: 'ACTIVE',
    source: 'PROMO',
    startedAt: '2026-11-25T10:00:00Z',
    currentPeriodEnd: '2026-12-25T10:00:00Z',
    canceledAt: null,
  },
  usage: {
    savedProducts: { used: 8, limit: null },
    alertSubscriptions: { used: 4, limit: null },
    notificationsThisMonth: { used: 12, limit: null, periodMonth: '2026-11-01' },
  },
});

const ACTIVATION: SubscriptionActivation = { plan: 'PRO', subscription: PRO_ME.subscription };

describe('AccountPageComponent', () => {
  let fixture: ComponentFixture<AccountPageComponent>;
  let me: ReturnType<typeof signal<Me>>;
  let api: Record<string, ReturnType<typeof vi.fn>>;
  let session: Record<string, unknown>;
  let currency: { setDisplayCurrency: ReturnType<typeof vi.fn> };
  let region: { setLang: ReturnType<typeof vi.fn> };
  let navigate: ReturnType<typeof vi.spyOn>;

  const host = () => fixture.nativeElement as HTMLElement;
  const settle = async () => {
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));
    await fixture.whenStable();
  };
  const select = (id: string, value: string) => {
    const element = host().querySelector<HTMLSelectElement>(`#${id}`);
    if (!element) {
      throw new Error(`missing #${id}`);
    }
    element.value = value;
    element.dispatchEvent(new Event('change'));
  };
  const type = (selector: string, value: string) => {
    const input = document.querySelector<HTMLInputElement>(selector);
    if (!input) {
      throw new Error(`missing ${selector}`);
    }
    input.value = value;
    input.dispatchEvent(new Event('input'));
  };
  const meters = () =>
    Array.from(host().querySelectorAll('[role="meter"]')).map((meter) => ({
      label: meter.getAttribute('aria-label'),
      text: meter.getAttribute('aria-valuetext'),
      max: meter.getAttribute('aria-valuemax'),
    }));

  async function setup(initial: Me = fixtureMe()): Promise<void> {
    me = signal(initial);
    api = {
      updateMe: vi.fn((body: object) => of({ ...me(), ...body })),
      resendVerification: vi.fn(() => of({ messageKey: 'auth.verify.resent' })),
      redeemPromoCode: vi.fn(() => of(ACTIVATION)),
      deleteMe: vi.fn(() => of(undefined)),
    };
    session = {
      me,
      user: me,
      usage: computed(() => me().usage),
      plan: computed(() => me().plan.code),
      isAuthenticated: () => true,
      reloadMe: vi.fn(async () => undefined),
      applyMe: vi.fn((next: Me) => me.set(next)),
      signOut: vi.fn(async () => undefined),
      forget: vi.fn(async () => undefined),
    };
    currency = { setDisplayCurrency: vi.fn() };
    region = { setLang: vi.fn(async () => undefined) };
    TestBed.configureTestingModule({
      imports: [
        AccountPageComponent,
        translocoTesting({ scopes: { account: { en: accountEn, tr: accountTr } } }),
      ],
      providers: [
        provideRouter([]),
        { provide: ApiClient, useValue: api },
        { provide: SessionStore, useValue: session },
        {
          provide: RegionStore,
          useValue: {
            ...region,
            intlLocale: signal('en-US'),
            countries: signal(FIXTURE_COUNTRIES),
            countryCode: signal('TR'),
            loadCountries: vi.fn(async () => undefined),
          },
        },
        {
          provide: CurrencyStore,
          useValue: {
            ...currency,
            currencies: signal([
              { code: 'EUR', asOf: '2026-11-24', stale: false },
              { code: 'USD', asOf: '2026-11-24', stale: false },
            ]),
            loadCurrencies: vi.fn(async () => undefined),
          },
        },
      ],
    });
    navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    fixture = TestBed.createComponent(AccountPageComponent);
    document.body.appendChild(fixture.nativeElement);
    await settle();
  }

  afterEach(() => (fixture.nativeElement as HTMLElement).remove());

  it('saves the preferences with PATCH /me and applies the answer', async () => {
    await setup();
    select('pref-country', 'DE');
    select('pref-locale', 'tr');
    select('pref-currency', 'EUR');
    host().querySelector<HTMLButtonElement>('#email-alerts')?.click();
    await settle();
    expect(host().querySelector('#email-alerts')?.getAttribute('aria-checked')).toBe('false');
    host()
      .querySelector('form')
      ?.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle();

    expect(api['updateMe']).toHaveBeenCalledWith({
      locale: 'tr',
      displayCurrency: 'EUR',
      emailAlertsEnabled: false,
      defaultCountryCode: 'DE',
    });
    expect(session['applyMe']).toHaveBeenCalled();
    expect(currency.setDisplayCurrency).toHaveBeenCalledWith('EUR');
    expect(region.setLang).toHaveBeenCalledWith('tr');
    expect(host().querySelector('[role="status"]')?.textContent).toContain(accountEn.prefs.saved);
  });

  it('sends null for the country currency and shows field errors from the server', async () => {
    await setup();
    api['updateMe'].mockReturnValue(
      throwError(
        () => new ApiProblem({ kind: 'problem', status: 422, code: 'fx_unsupported_currency' }),
      ),
    );
    host()
      .querySelector('form')
      ?.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle();
    expect(api['updateMe']).toHaveBeenCalledWith(
      expect.objectContaining({ displayCurrency: null }),
    );
    expect(host().querySelector('#pref-currency')?.getAttribute('aria-invalid')).toBe('true');
  });

  it('renders the FREE usage meters from /me', async () => {
    await setup();
    expect(meters()).toEqual([
      { label: 'Saved products', text: '2 of 5', max: '5' },
      { label: 'Active alerts', text: '1 of 3', max: '3' },
      { label: 'Notifications in November', text: '4 of 30', max: '30' },
    ]);
    expect(host().textContent).toContain('Resets on Dec 1, 2026 (UTC)');
    expect(host().querySelector('[data-plan]')?.textContent?.trim()).toBe('FREE');
  });

  it('renders unlimited meters and the PRO period', async () => {
    await setup(PRO_ME);
    expect(meters().map((meter) => meter.text)).toEqual([
      '8 · unlimited',
      '4 · unlimited',
      '12 · unlimited',
    ]);
    expect(meters().every((meter) => meter.max === null)).toBe(true);
    expect(host().querySelector('[data-period]')?.textContent).toContain('Dec 25, 2026');
    expect(host().querySelector('[data-period]')?.textContent).toContain(
      accountEn.plan.source.PROMO,
    );
    expect(host().textContent).not.toContain('Resets on');
  });

  it('redeems a promo code and reloads /me', async () => {
    await setup();
    type('vd-promo-form input', ' launch-7k3m9qxt2b4n6prw ');
    host().querySelector('vd-promo-form form')?.dispatchEvent(new Event('submit'));
    await settle();
    expect(api['redeemPromoCode']).toHaveBeenCalledWith({ code: 'launch-7k3m9qxt2b4n6prw' });
    expect(session['reloadMe']).toHaveBeenCalled();
    expect(host().querySelector('vd-promo-form')?.textContent).toContain(
      'PRO is active until Dec 25, 2026',
    );
  });

  it('shows one uniform message for every rejected promo code', async () => {
    await setup();
    api['redeemPromoCode'].mockReturnValue(
      throwError(() => new ApiProblem({ kind: 'problem', status: 400, code: 'promo_invalid' })),
    );
    type('vd-promo-form input', 'UNKNOWN-0000000000000000');
    host().querySelector('vd-promo-form form')?.dispatchEvent(new Event('submit'));
    await settle();
    const input = host().querySelector('vd-promo-form input');
    expect(input?.getAttribute('aria-invalid')).toBe('true');
    expect(host().querySelector('vd-promo-form .err')?.textContent).toContain(
      EN.problem.promo_invalid.body,
    );
  });

  it.each([
    ['email_not_verified', 403, EN.problem.email_not_verified.title],
    ['rate_limited', 429, EN.problem.rate_limited.title],
  ] as const)('renders %s from the promo endpoint as a notice', async (code, status, text) => {
    await setup();
    api['redeemPromoCode'].mockReturnValue(
      throwError(() => new ApiProblem({ kind: 'problem', status, code })),
    );
    type('vd-promo-form input', 'LAUNCH-7K3M9QXT2B4N6PRW');
    host().querySelector('vd-promo-form form')?.dispatchEvent(new Event('submit'));
    await settle();
    expect(host().querySelector('vd-promo-form vd-problem-notice')?.textContent).toContain(text);
  });

  it('shows the verification reminder and resends the e-mail', async () => {
    await setup(fixtureMe({ emailVerified: false }));
    const notice = host().querySelector('[data-notice="verify"]');
    expect(notice?.textContent).toContain(accountEn.verify.title);
    notice?.querySelector('button')?.click();
    await settle();
    expect(api['resendVerification']).toHaveBeenCalledWith({ email: 'user@example.com' });
    expect(host().textContent).toContain(EN.auth.verify.resent);
  });

  it('deletes the account only after the password confirmation in the dialog', async () => {
    await setup();
    const opener = Array.from(host().querySelectorAll('button')).find((button) =>
      button.textContent?.includes(accountEn.delete.open),
    );
    opener?.focus();
    opener?.click();
    await settle();
    const dialog = document.querySelector('[role="dialog"]');
    expect(dialog?.getAttribute('aria-modal')).toBe('true');
    expect(dialog?.textContent).toContain(accountEn.delete.irreversible);
    expect(document.activeElement?.id).toBe('delete-password');

    dialog?.querySelector('form')?.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle();
    expect(api['deleteMe']).not.toHaveBeenCalled();

    api['deleteMe'].mockReturnValueOnce(
      throwError(
        () => new ApiProblem({ kind: 'problem', status: 401, code: 'invalid_credentials' }),
      ),
    );
    type('#delete-password', 'wrong-password');
    dialog?.querySelector('form')?.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle();
    expect(document.querySelector('#delete-password-error')?.textContent).toContain(
      accountEn.delete.wrong_password,
    );
    expect(session['forget']).not.toHaveBeenCalled();

    type('#delete-password', 'correct-horse-battery');
    document
      .querySelector('[role="dialog"] form')
      ?.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle();
    expect(api['deleteMe']).toHaveBeenLastCalledWith({ password: 'correct-horse-battery' });
    expect(session['forget']).toHaveBeenCalled();
    expect(session['signOut']).not.toHaveBeenCalled();
    expect(navigate).toHaveBeenCalledWith('/');
    expect(document.querySelector('[role="dialog"]')).toBeNull();
  });

  it('closes the dialog with Escape without deleting', async () => {
    await setup();
    const opener = Array.from(host().querySelectorAll('button')).find((button) =>
      button.textContent?.includes(accountEn.delete.open),
    );
    opener?.click();
    await settle();
    document
      .querySelector('[role="dialog"]')
      ?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await settle();
    expect(document.querySelector('[role="dialog"]')).toBeNull();
    expect(api['deleteMe']).not.toHaveBeenCalled();
  });
});
