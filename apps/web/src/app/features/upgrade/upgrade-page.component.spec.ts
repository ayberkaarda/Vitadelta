import { computed, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import accountEn from '../../../../public/i18n/account/en.json';
import accountTr from '../../../../public/i18n/account/tr.json';
import upgradeEn from '../../../../public/i18n/upgrade/en.json';
import upgradeTr from '../../../../public/i18n/upgrade/tr.json';
import { ApiClient } from '../../core/api/api-client';
import { ApiProblem } from '../../core/api/api-problem';
import { PlanCode, PlanList } from '../../core/api/api-types';
import { SessionStore } from '../../core/auth/session.store';
import { CurrencyStore } from '../../core/state/currency.store';
import { FX_IDENTITY_TRY, FX_STALE_EUR } from '../../testing/api-fixtures';
import { provideTestLocale } from '../../testing/test-providers';
import { EN, translocoTesting } from '../../testing/transloco-testing';
import { UpgradePageComponent } from './upgrade-page.component';

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

describe('UpgradePageComponent', () => {
  let fixture: ComponentFixture<UpgradePageComponent>;
  let listPlans: ReturnType<typeof vi.fn>;
  let requestCurrency: ReturnType<typeof signal<string | undefined>>;
  let recordMeta: ReturnType<typeof vi.fn>;

  const host = () => fixture.nativeElement as HTMLElement;
  const card = (code: PlanCode) => host().querySelector(`[data-plan="${code}"]`);
  const settle = async () => {
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));
    await fixture.whenStable();
  };

  async function setup(authenticated: boolean, plan: PlanCode = 'FREE'): Promise<void> {
    listPlans = vi.fn((query: { displayCurrency?: string }) =>
      of(query.displayCurrency === 'EUR' ? PLANS_EUR : PLANS_TRY),
    );
    requestCurrency = signal<string | undefined>(undefined);
    recordMeta = vi.fn();
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
        provideRouter([]),
        provideTestLocale('en-US'),
        { provide: ApiClient, useValue: { listPlans, redeemPromoCode: vi.fn() } },
        {
          provide: SessionStore,
          useValue: {
            isAuthenticated: signal(authenticated),
            plan: computed(() => (authenticated ? plan : null)),
            reloadMe: vi.fn(async () => undefined),
          },
        },
        { provide: CurrencyStore, useValue: { requestCurrency, recordMeta } },
      ],
    });
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
    expect(buttons.some((text) => /checkout|buy|pay/i.test(text))).toBe(false);
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
});
