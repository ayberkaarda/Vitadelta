import { computed, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, Routes, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { NEVER, Observable, Subject, of, throwError } from 'rxjs';

import accountEn from '../../../../public/i18n/account/en.json';
import accountTr from '../../../../public/i18n/account/tr.json';
import upgradeEn from '../../../../public/i18n/upgrade/en.json';
import upgradeTr from '../../../../public/i18n/upgrade/tr.json';
import { ApiClient } from '../../core/api/api-client';
import { ApiProblem } from '../../core/api/api-problem';
import {
  BillingRedirect,
  Me,
  PlanCode,
  PlanList,
  SubscriptionSource,
} from '../../core/api/api-types';
import { SessionStore } from '../../core/auth/session.store';
import { ExternalNavigation } from '../../core/billing/billing-navigator';
import { CurrencyStore } from '../../core/state/currency.store';
import { FX_IDENTITY_TRY, FX_STALE_EUR, fixtureMe } from '../../testing/api-fixtures';
import { provideTestLocale } from '../../testing/test-providers';
import { EN, translocoTesting } from '../../testing/transloco-testing';
import { CHECKOUT_CONFIRM_DELAYS } from './components/checkout-return.component';
import { UpgradePageComponent } from './upgrade-page.component';

const CHECKOUT_URL = 'https://checkout.stripe.com/c/pay/cs_test_a1B2c3D4e5F6g7H8';
const PORTAL_URL = 'https://billing.stripe.com/p/session/test_YWNjdF8xUXdFcnR5';

const PLANS_TRY: PlanList = {
  meta: { nativeCurrency: 'TRY', currency: 'TRY', fx: FX_IDENTITY_TRY },
  items: [
    {
      code: 'FREE',
      name: 'Free',
      savedProductsLimit: 5,
      alertSubscriptionsLimit: 3,
      notificationsPerMonthLimit: 30,
      price: null,
      checkoutAvailable: false,
    },
    {
      code: 'PRO',
      name: 'Pro',
      savedProductsLimit: null,
      alertSubscriptionsLimit: null,
      notificationsPerMonthLimit: null,
      price: {
        amount: 199,
        amountNative: 199,
        currency: 'TRY',
        nativeCurrency: 'TRY',
        billingPeriod: 'MONTHLY',
      },
      checkoutAvailable: false,
    },
  ],
};

const PLANS_EUR: PlanList = {
  meta: { nativeCurrency: 'TRY', currency: 'EUR', fx: FX_STALE_EUR },
  items: [
    PLANS_TRY.items[0],
    {
      ...PLANS_TRY.items[1],
      price: {
        amount: 4.4775,
        amountNative: 199,
        currency: 'EUR',
        nativeCurrency: 'TRY',
        billingPeriod: 'MONTHLY',
      },
    },
  ],
};

/** The plans as a server with a configured payment provider reports them. */
function withCheckout(list: PlanList): PlanList {
  return {
    ...list,
    items: list.items.map((plan) => ({ ...plan, checkoutAvailable: plan.code === 'PRO' })),
  };
}

function meFor(plan: PlanCode, source: SubscriptionSource, emailVerified = true): Me {
  const base = fixtureMe({ emailVerified });
  if (plan === 'FREE') {
    return { ...base, subscription: { ...base.subscription, source } };
  }
  return {
    ...base,
    plan: {
      code: 'PRO',
      name: 'Pro',
      savedProductsLimit: null,
      alertSubscriptionsLimit: null,
      notificationsPerMonthLimit: null,
    },
    subscription: {
      ...base.subscription,
      planCode: 'PRO',
      source,
      currentPeriodEnd: '2026-12-25T10:00:00Z',
    },
  };
}

interface SetupOptions {
  /** `Plan.checkoutAvailable` of PRO (a configured payment provider). */
  readonly checkout?: boolean;
  readonly source?: SubscriptionSource;
  readonly emailVerified?: boolean;
  readonly routes?: Routes;
}

describe('UpgradePageComponent', () => {
  let fixture: ComponentFixture<UpgradePageComponent>;
  let listPlans: ReturnType<typeof vi.fn>;
  let requestCurrency: ReturnType<typeof signal<string | undefined>>;
  let recordMeta: ReturnType<typeof vi.fn>;
  let createCheckoutSession: ReturnType<typeof vi.fn<() => Observable<BillingRedirect>>>;
  let createBillingPortalSession: ReturnType<typeof vi.fn<() => Observable<BillingRedirect>>>;
  let assign: ReturnType<typeof vi.fn>;
  let reloadMe: ReturnType<typeof vi.fn<() => Promise<void>>>;
  let me: ReturnType<typeof signal<Me | null>>;

  const host = () => fixture.nativeElement as HTMLElement;
  const card = (code: PlanCode) => host().querySelector(`[data-plan="${code}"]`);
  const settle = async () => {
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));
    await fixture.whenStable();
  };

  function configure(authenticated: boolean, plan: PlanCode, options: SetupOptions): void {
    const { checkout = false, source = 'MANUAL', emailVerified = true, routes = [] } = options;
    const plans = (list: PlanList) => (checkout ? withCheckout(list) : list);
    listPlans = vi.fn((query: { displayCurrency?: string }) =>
      of(plans(query.displayCurrency === 'EUR' ? PLANS_EUR : PLANS_TRY)),
    );
    requestCurrency = signal<string | undefined>(undefined);
    recordMeta = vi.fn();
    createCheckoutSession = vi.fn(() => of({ url: CHECKOUT_URL }));
    createBillingPortalSession = vi.fn(() => of({ url: PORTAL_URL }));
    assign = vi.fn();
    me = signal<Me | null>(authenticated ? meFor(plan, source, emailVerified) : null);
    reloadMe = vi.fn(async () => undefined);
    TestBed.configureTestingModule({
      imports: [
        UpgradePageComponent,
        translocoTesting({
          scopes: {
            upgrade: { en: upgradeEn, tr: upgradeTr },
            account: { en: accountEn, tr: accountTr },
          },
        }),
      ],
      providers: [
        provideRouter(routes),
        provideTestLocale('en-US'),
        {
          provide: ApiClient,
          useValue: {
            listPlans,
            redeemPromoCode: vi.fn(),
            createCheckoutSession,
            createBillingPortalSession,
          },
        },
        {
          provide: SessionStore,
          useValue: {
            isAuthenticated: signal(authenticated),
            me,
            plan: computed(() => me()?.plan.code ?? null),
            reloadMe,
            whenReady: async () => undefined,
          },
        },
        { provide: CurrencyStore, useValue: { requestCurrency, recordMeta } },
        { provide: ExternalNavigation, useValue: { assign } },
        { provide: CHECKOUT_CONFIRM_DELAYS, useValue: [0, 0] },
      ],
    });
  }

  async function setup(
    authenticated: boolean,
    plan: PlanCode = 'FREE',
    options: SetupOptions = {},
  ): Promise<void> {
    configure(authenticated, plan, options);
    fixture = TestBed.createComponent(UpgradePageComponent);
    await settle();
  }

  it('compares FREE and PRO from GET /plans with unlimited limits', async () => {
    await setup(true);
    expect(listPlans).toHaveBeenCalledWith({});
    expect(recordMeta).toHaveBeenCalledWith(PLANS_TRY.meta);
    const free = card('FREE');
    const pro = card('PRO');
    expect(free?.querySelector('.price')?.textContent).toContain('TRY');
    expect(free?.querySelector('.price')?.textContent).toContain('0.00');
    expect(
      Array.from(free?.querySelectorAll('dd') ?? []).map((dd) => dd.textContent?.trim()),
    ).toEqual(['Unlimited', '5', '3', '30']);
    expect(
      Array.from(pro?.querySelectorAll('dd') ?? []).map((dd) => dd.textContent?.trim()),
    ).toEqual(['Unlimited', 'Unlimited', 'Unlimited', 'Unlimited']);
    expect(pro?.querySelector('vd-money')?.textContent).toContain('199.00');
    expect(pro?.textContent).toContain(upgradeEn.per_month);
    expect(free?.querySelector('[data-current]')?.textContent).toContain(upgradeEn.your_plan);
    expect(pro?.querySelector('[data-current]')).toBeNull();
    expect(host().textContent).toContain(upgradeEn.how_to_get_pro);
    expect(host().querySelector('vd-promo-form')?.textContent).toContain(
      accountEn.promo.title_redeem,
    );
    const buttons = Array.from(host().querySelectorAll('button')).map((b) => b.textContent ?? '');
    expect(buttons.some((text) => /checkout|subscri|buy|pay/i.test(text))).toBe(false);
  });

  it('marks PRO as the current plan for a PRO user', async () => {
    await setup(true, 'PRO');
    expect(card('PRO')?.querySelector('[data-current]')).not.toBeNull();
    expect(card('FREE')?.querySelector('[data-current]')).toBeNull();
  });

  it('shows the converted price with its native amount when a display currency is chosen', async () => {
    await setup(true);
    requestCurrency.set('EUR');
    await settle();
    expect(listPlans).toHaveBeenLastCalledWith({ displayCurrency: 'EUR' });
    const money = card('PRO')?.querySelector('vd-money')?.textContent ?? '';
    expect(money).toContain('€4.48');
    expect(money).toContain('TRY');
    expect(host().textContent).toContain(EN.flag.FX_STALE);
  });

  it('offers sign-up and log-in instead of the promo form to anonymous visitors', async () => {
    await setup(false);
    expect(host().querySelector('vd-promo-form')).toBeNull();
    const prompt = host().querySelector('[data-signin]');
    expect(prompt?.querySelector('a[href="/auth/register"]')).not.toBeNull();
    expect(prompt?.querySelector('a[href="/auth/login?returnUrl=%2Fupgrade"]')).not.toBeNull();
    expect(host().querySelector('[data-current]')).toBeNull();
  });

  it('shows a retryable problem when the plans cannot be loaded', async () => {
    await setup(false);
    listPlans.mockReturnValueOnce(
      throwError(() => new ApiProblem({ kind: 'network', status: 0, code: null })),
    );
    requestCurrency.set('USD');
    await settle();
    expect(host().textContent).toContain(EN.problem.network.title);
    host().querySelector<HTMLButtonElement>('vd-problem-notice button')?.click();
    await settle();
    expect(card('PRO')).not.toBeNull();
  });

  describe('online payment', () => {
    const checkoutButton = () =>
      card('PRO')?.querySelector<HTMLButtonElement>('button[data-action="checkout"]') ?? null;

    async function failCheckout(problem: ApiProblem): Promise<void> {
      createCheckoutSession.mockReturnValueOnce(throwError(() => problem));
      checkoutButton()?.click();
      await settle();
    }

    it('offers the hosted checkout only when the server reports checkoutAvailable', async () => {
      await setup(true, 'FREE', { checkout: true });
      const button = checkoutButton();
      expect(button?.textContent).toContain(upgradeEn.checkout.button);
      expect(button?.getAttribute('aria-describedby')).toBe('checkout-note');
      expect(card('PRO')?.querySelector('#checkout-note')?.textContent).toContain(
        upgradeEn.checkout.note,
      );
      expect(card('FREE')?.querySelector('[data-billing]')).toBeNull();
      expect(host().querySelector('[data-how]')?.textContent).toContain(
        upgradeEn.how_to_get_pro_online,
      );
      // The promo-code route stays next to the checkout.
      expect(host().querySelector('vd-promo-form')).not.toBeNull();
      expect(createCheckoutSession).not.toHaveBeenCalled();
    });

    it('has no checkout button without a payment provider', async () => {
      await setup(true, 'FREE', { checkout: false });
      expect(host().querySelector('[data-billing]')).toBeNull();
      expect(host().querySelector('vd-checkout-panel')?.childElementCount).toBe(0);
      expect(host().querySelector('[data-how]')?.textContent).toContain(upgradeEn.how_to_get_pro);
    });

    it('stays busy and ignores further clicks while the session is created', async () => {
      await setup(true, 'FREE', { checkout: true });
      createCheckoutSession.mockReturnValueOnce(NEVER);
      checkoutButton()?.click();
      await settle();
      expect(checkoutButton()?.getAttribute('aria-busy')).toBe('true');
      expect(checkoutButton()?.getAttribute('aria-disabled')).toBe('true');
      expect(card('PRO')?.querySelector('[role="status"]')?.textContent).toContain(
        upgradeEn.checkout.busy,
      );
      checkoutButton()?.click();
      await settle();
      expect(createCheckoutSession).toHaveBeenCalledTimes(1);
      expect(assign).not.toHaveBeenCalled();
    });

    it('creates a checkout session and sends the browser to the provider', async () => {
      await setup(true, 'FREE', { checkout: true });
      checkoutButton()?.click();
      await settle();
      expect(createCheckoutSession).toHaveBeenCalledTimes(1);
      expect(assign).toHaveBeenCalledWith(CHECKOUT_URL);
      // The browser is leaving the page: the button stays busy.
      expect(checkoutButton()?.getAttribute('aria-busy')).toBe('true');
    });

    it('drops the busy state when the page comes back from the back-forward cache', async () => {
      await setup(true, 'FREE', { checkout: true });
      checkoutButton()?.click();
      await settle();
      const restored = new Event('pageshow') as PageTransitionEvent;
      Object.defineProperty(restored, 'persisted', { value: true });
      window.dispatchEvent(restored);
      await settle();
      expect(checkoutButton()?.getAttribute('aria-busy')).toBe('false');
    });

    it('never follows a session URL that is not a provider page and never renders it', async () => {
      await setup(true, 'FREE', { checkout: true });
      createCheckoutSession.mockReturnValueOnce(of({ url: 'https://phish.example.com/pay' }));
      checkoutButton()?.click();
      await settle();
      expect(assign).not.toHaveBeenCalled();
      expect(host().innerHTML).not.toContain('phish.example.com');
      expect(card('PRO')?.querySelector('[data-code="http"]')?.textContent).toContain(
        EN.problem.generic.title,
      );
      expect(checkoutButton()?.getAttribute('aria-busy')).toBe('false');
    });

    it('sends visitors to the login page and back to /upgrade', async () => {
      await setup(false, 'FREE', { checkout: true });
      expect(checkoutButton()).toBeNull();
      const link = card('PRO')?.querySelector('a[data-action="checkout-login"]');
      expect(link?.getAttribute('href')).toBe('/auth/login?returnUrl=%2Fupgrade');
      expect(link?.textContent).toContain(upgradeEn.checkout.signin);
    });

    it('shows a note instead of a button when PRO comes from a promo code', async () => {
      await setup(true, 'PRO', { checkout: true, source: 'PROMO' });
      expect(checkoutButton()).toBeNull();
      expect(card('PRO')?.querySelector('[data-checkout-note]')?.textContent).toContain(
        upgradeEn.checkout.already_pro,
      );
      expect(card('PRO')?.querySelector('[data-action="manage-subscription"]')).toBeNull();
    });

    it('offers "Manage subscription" for PRO paid through the provider', async () => {
      await setup(true, 'PRO', { checkout: false, source: 'PROVIDER' });
      const manage = card('PRO')?.querySelector<HTMLButtonElement>(
        'button[data-action="manage-subscription"]',
      );
      expect(manage?.textContent).toContain(accountEn.billing.manage);
      expect(checkoutButton()).toBeNull();
      manage?.click();
      await settle();
      expect(createBillingPortalSession).toHaveBeenCalledTimes(1);
      expect(assign).toHaveBeenCalledWith(PORTAL_URL);
    });

    it('sends the user to the login page when the session has ended (401)', async () => {
      await setup(true, 'FREE', { checkout: true });
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
      await failCheckout(new ApiProblem({ kind: 'problem', status: 401, code: 'unauthorized' }));
      expect(navigate).toHaveBeenCalledWith(['/auth/login'], {
        queryParams: { returnUrl: '/upgrade' },
      });
      expect(card('PRO')?.querySelector('vd-problem-notice, vd-notice')).toBeNull();
      expect(assign).not.toHaveBeenCalled();
    });

    it('points to the account page when the e-mail is not verified (403)', async () => {
      await setup(true, 'FREE', { checkout: true, emailVerified: false });
      await failCheckout(
        new ApiProblem({ kind: 'problem', status: 403, code: 'email_not_verified' }),
      );
      const notice = card('PRO')?.querySelector('[data-code="email_not_verified"]');
      expect(notice?.textContent).toContain(EN.problem.email_not_verified.title);
      expect(notice?.querySelector('[role="alert"]')).not.toBeNull();
      expect(notice?.querySelector('a[href="/account"]')?.textContent).toContain(
        upgradeEn.checkout.verify_link,
      );
    });

    it('reloads /me and explains when PRO is already active (409)', async () => {
      await setup(true, 'FREE', { checkout: true });
      reloadMe.mockImplementationOnce(async () => me.set(meFor('PRO', 'MANUAL')));
      await failCheckout(
        new ApiProblem({ kind: 'problem', status: 409, code: 'plan_already_active' }),
      );
      expect(reloadMe).toHaveBeenCalledTimes(1);
      const notice = card('PRO')?.querySelector('[data-code="plan_already_active"]');
      expect(notice?.textContent).toContain(EN.problem.plan_already_active.title);
      expect(notice?.querySelector('a[href="/account"]')?.textContent).toContain(
        upgradeEn.checkout.account_link,
      );
      expect(card('PRO')?.querySelector('[data-current]')).not.toBeNull();
      expect(checkoutButton()).toBeNull();
    });

    it('offers the billing portal when a provider subscription has not ended (409)', async () => {
      // The summary still says FREE (for example a past-due subscription after the downgrade),
      // but the server refuses a second subscription: it is settled in the billing portal.
      await setup(true, 'FREE', { checkout: true });
      await failCheckout(
        new ApiProblem({ kind: 'problem', status: 409, code: 'plan_already_active' }),
      );
      expect(reloadMe).toHaveBeenCalledTimes(1);
      const notice = card('PRO')?.querySelector('[data-code="plan_already_active"]');
      expect(notice?.textContent).toContain(upgradeEn.checkout.unsettled_title);
      expect(notice?.textContent).toContain(upgradeEn.checkout.unsettled_text);
      const manage = notice?.querySelector<HTMLButtonElement>(
        'button[data-action="manage-subscription"]',
      );
      expect(manage?.textContent).toContain(accountEn.billing.manage);
      expect(checkoutButton()).not.toBeNull();
      manage?.click();
      await settle();
      expect(createBillingPortalSession).toHaveBeenCalledTimes(1);
      expect(assign).toHaveBeenCalledWith(PORTAL_URL);
    });

    it('switches to "Manage subscription" when /me now shows a provider subscription (409)', async () => {
      await setup(true, 'FREE', { checkout: true });
      reloadMe.mockImplementationOnce(async () => me.set(meFor('PRO', 'PROVIDER')));
      await failCheckout(
        new ApiProblem({ kind: 'problem', status: 409, code: 'plan_already_active' }),
      );
      expect(checkoutButton()).toBeNull();
      expect(
        card('PRO')?.querySelector('button[data-action="manage-subscription"]'),
      ).not.toBeNull();
    });

    it('counts down the rate limit before another try (429)', async () => {
      await setup(true, 'FREE', { checkout: true });
      await failCheckout(
        new ApiProblem({
          kind: 'problem',
          status: 429,
          code: 'rate_limited',
          retryAfterSeconds: 30,
        }),
      );
      const notice = card('PRO')?.querySelector('[data-code="rate_limited"]');
      expect(notice?.textContent).toContain(EN.problem.rate_limited.title);
      const retry = notice?.querySelector<HTMLButtonElement>('button');
      expect(retry?.textContent).toContain('Try again in 30 s');
      expect(retry?.disabled).toBe(true);
      // The primary button waits for the same countdown; it cannot bypass the limit.
      expect(checkoutButton()?.getAttribute('aria-disabled')).toBe('true');
      checkoutButton()?.click();
      await settle();
      expect(createCheckoutSession).toHaveBeenCalledTimes(1);
    });

    it('enables the checkout button again when the rate-limit countdown ends', async () => {
      await setup(true, 'FREE', { checkout: true });
      await failCheckout(
        new ApiProblem({
          kind: 'problem',
          status: 429,
          code: 'rate_limited',
          retryAfterSeconds: 1,
        }),
      );
      expect(checkoutButton()?.getAttribute('aria-disabled')).toBe('true');
      await new Promise((resolve) => setTimeout(resolve, 1100));
      await settle();
      expect(checkoutButton()?.getAttribute('aria-disabled')).toBe('false');
      checkoutButton()?.click();
      await settle();
      expect(createCheckoutSession).toHaveBeenCalledTimes(2);
      expect(assign).toHaveBeenCalledWith(CHECKOUT_URL);
    });

    it('never sends the browser to the provider after the page was left', async () => {
      await setup(true, 'FREE', { checkout: true });
      const answer = new Subject<BillingRedirect>();
      createCheckoutSession.mockReturnValueOnce(answer);
      checkoutButton()?.click();
      await settle();
      expect(answer.observed).toBe(true);
      fixture.destroy();
      expect(answer.observed).toBe(false);
      answer.next({ url: CHECKOUT_URL });
      await new Promise((resolve) => setTimeout(resolve, 0));
      expect(assign).not.toHaveBeenCalled();
    });

    it('explains an unavailable payment service and retries (503)', async () => {
      await setup(true, 'FREE', { checkout: true });
      await failCheckout(
        new ApiProblem({ kind: 'problem', status: 503, code: 'billing_unavailable' }),
      );
      const notice = card('PRO')?.querySelector('[data-code="billing_unavailable"]');
      expect(notice?.textContent).toContain(EN.problem.billing_unavailable.title);
      expect(notice?.textContent).toContain(EN.problem.billing_unavailable.body);
      notice?.querySelector<HTMLButtonElement>('button')?.click();
      await settle();
      expect(createCheckoutSession).toHaveBeenCalledTimes(2);
      expect(assign).toHaveBeenCalledWith(CHECKOUT_URL);
    });
  });

  describe('return from the provider', () => {
    const routes: Routes = [{ path: 'upgrade', component: UpgradePageComponent }];

    async function arrive(url: string): Promise<HTMLElement> {
      const harness = await RouterTestingHarness.create();
      await harness.navigateByUrl(url, UpgradePageComponent);
      for (let round = 0; round < 6; round++) {
        await new Promise((resolve) => setTimeout(resolve, 0));
        harness.detectChanges();
        await harness.fixture.whenStable();
      }
      return harness.routeNativeElement as HTMLElement;
    }

    it('says that a canceled checkout charged nothing and drops the parameter', async () => {
      configure(true, 'FREE', { checkout: true, routes });
      const page = await arrive('/upgrade?checkout=canceled');
      expect(page.querySelector('[data-return="canceled"]')?.textContent).toContain(
        upgradeEn.return.canceled_title,
      );
      expect(TestBed.inject(Router).url).toBe('/upgrade');
      expect(reloadMe).not.toHaveBeenCalled();
    });

    it('waits for the server to report PRO after a successful checkout', async () => {
      configure(true, 'FREE', { checkout: true, routes });
      reloadMe.mockImplementationOnce(async () => undefined);
      reloadMe.mockImplementationOnce(async () => me.set(meFor('PRO', 'PROVIDER')));
      const page = await arrive('/upgrade?checkout=success');
      expect(reloadMe).toHaveBeenCalledTimes(2);
      expect(page.querySelector('[data-return="active"]')?.textContent).toContain(
        'PRO is active until Dec 25, 2026.',
      );
      expect(TestBed.inject(Router).url).toBe('/upgrade');
      expect(
        page.querySelector('[data-plan="PRO"] [data-action="manage-subscription"]'),
      ).not.toBeNull();
    });

    it('ignores an unknown checkout parameter', async () => {
      configure(true, 'FREE', { checkout: true, routes });
      const page = await arrive('/upgrade?checkout=paid');
      expect(page.querySelector('vd-checkout-return')).toBeNull();
      expect(TestBed.inject(Router).url).toBe('/upgrade?checkout=paid');
    });
  });
});
