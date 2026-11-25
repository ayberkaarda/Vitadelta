import { computed, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Observable, Subject, of, throwError } from 'rxjs';

import accountEn from '../../../../public/i18n/account/en.json';
import accountTr from '../../../../public/i18n/account/tr.json';
import { ApiClient } from '../../core/api/api-client';
import { ApiProblem } from '../../core/api/api-problem';
import {
  AlertListItem,
  AlertSubscription,
  Me,
  SavedProduct,
  SavedProductList,
} from '../../core/api/api-types';
import { SessionStore } from '../../core/auth/session.store';
import { CurrencyStore } from '../../core/state/currency.store';
import { RegionStore } from '../../core/state/region.store';
import { FIXTURE_COUNTRIES, FX_IDENTITY_TRY, fixtureMe } from '../../testing/api-fixtures';
import { EN, translocoTesting } from '../../testing/transloco-testing';
import { AlertsPageComponent } from './alerts-page.component';
import { SavedProductsPageComponent } from './saved-products-page.component';

const SYNTHETIC = { synthetic: true, source: null, sourceRef: null, sourceEntryDate: null };

const DROP_ALERT: AlertSubscription = {
  id: 'a1000000-0000-4000-8000-000000000001',
  productId: 'p1000000-0000-4000-8000-000000000001',
  countryCode: 'TR',
  trigger: 'PRICE_DROP',
  thresholdPercent: 5,
  baselinePrice: 540,
  baselineCurrency: 'TRY',
  active: true,
  createdAt: '2026-11-03T09:00:00Z',
  lastTriggeredAt: '2026-11-18T09:00:00Z',
};

const DAILY: SavedProduct = {
  productId: 'p1000000-0000-4000-8000-000000000001',
  code: 'DAILY-60',
  brand: 'Bosphorus Health',
  name: 'Daily Complete 60',
  dosageForm: 'TABLET',
  provenance: SYNTHETIC,
  countryCode: 'TR',
  savedAt: '2026-11-03T09:00:00Z',
  availabilityStatus: 'AVAILABLE',
  cost: {
    effectivePrice: 540,
    effectivePriceNative: 540,
    currency: 'TRY',
    servingsPerContainer: 60,
    daysSupply: 60,
    costPerDay: 9,
    costPerDayNative: 9,
    priceStale: false,
    outOfStock: false,
  },
  selectedOffer: {
    retailer: 'Demo Store',
    url: 'https://shop.example.com/daily-60',
    checkedAt: '2026-11-20T09:00:00Z',
  },
  flags: [],
  alerts: [DROP_ALERT],
};

const IRON: SavedProduct = {
  ...DAILY,
  productId: 'p1000000-0000-4000-8000-000000000002',
  code: 'IRON-30',
  brand: 'Meridian Vitals',
  name: 'Iron Plus 30',
  availabilityStatus: 'NOT_SOLD',
  cost: null,
  selectedOffer: null,
  flags: [{ code: 'UNAVAILABLE_IN_COUNTRY', nutrientCodes: [] }],
  alerts: [],
};

const SAVED_LIST: SavedProductList = {
  meta: { country: 'TR', nativeCurrency: 'TRY', currency: 'TRY', fx: FX_IDENTITY_TRY },
  items: [DAILY, IRON],
};

function limitProblem(messageKey: string): ApiProblem {
  return new ApiProblem({
    kind: 'problem',
    status: 403,
    code: 'entitlement_exceeded',
    limit: 3,
    used: 3,
    plan: 'FREE',
    upgradeTo: 'PRO',
    upgradeHint: { messageKey, ctaPath: '/upgrade' },
  });
}

async function settle(fixture: ComponentFixture<unknown>): Promise<void> {
  await fixture.whenStable();
  await new Promise((resolve) => setTimeout(resolve, 0));
  await fixture.whenStable();
}

function configure(api: Record<string, unknown>, me: Me = fixtureMe()) {
  const meSignal = signal(me);
  const reloadMe = vi.fn(async () => undefined);
  const recordMeta = vi.fn();
  const countryCode = signal('TR');
  TestBed.configureTestingModule({
    imports: [translocoTesting({ scopes: { account: { en: accountEn, tr: accountTr } } })],
    providers: [
      provideRouter([]),
      { provide: ApiClient, useValue: api },
      {
        provide: SessionStore,
        useValue: {
          me: meSignal,
          usage: computed(() => meSignal().usage),
          plan: computed(() => meSignal().plan.code),
          reloadMe,
        },
      },
      {
        provide: RegionStore,
        useValue: {
          intlLocale: signal('en-US'),
          countries: signal(FIXTURE_COUNTRIES),
          countryCode,
          country: signal(FIXTURE_COUNTRIES[0]),
          loadCountries: vi.fn(async () => undefined),
        },
      },
      {
        provide: CurrencyStore,
        useValue: { requestCurrency: signal<string | undefined>(undefined), recordMeta },
      },
    ],
  });
  return { reloadMe, recordMeta, countryCode };
}

describe('SavedProductsPageComponent', () => {
  let fixture: ComponentFixture<SavedProductsPageComponent>;
  const host = () => fixture.nativeElement as HTMLElement;
  const rows = () => Array.from(host().querySelectorAll('tr[data-product]'));

  async function setup(api: Record<string, unknown>, me?: Me) {
    const spies = configure({ listSavedProducts: vi.fn(() => of(SAVED_LIST)), ...api }, me);
    fixture = TestBed.createComponent(SavedProductsPageComponent);
    await settle(fixture);
    return spies;
  }

  it('lists saved products with money, unavailable flag, alert badges and the disclaimer', async () => {
    const list = vi.fn(() => of(SAVED_LIST));
    const { recordMeta } = await setup({ listSavedProducts: list });
    expect(list).toHaveBeenCalledWith({ country: 'TR' });
    expect(recordMeta).toHaveBeenCalledWith(SAVED_LIST.meta);
    expect(rows().map((row) => row.getAttribute('data-product'))).toEqual(['DAILY-60', 'IRON-30']);
    expect(rows()[0].textContent).toContain('TRY');
    expect(rows()[0].querySelector('[data-trigger="PRICE_DROP"]')?.textContent).toContain(
      'Price drop ≥ 5 %',
    );
    expect(rows()[1].textContent).toContain('Not sold in Türkiye');
    expect(rows()[1].textContent).toContain('—');
    expect(host().querySelector('[data-key="disclaimer.not_medical_advice"]')).not.toBeNull();
    expect(host().querySelector('[data-usage]')?.textContent).toContain('2 of 5 saved on FREE');
  });

  it('shows the limit notice when /me says the saved limit is reached', async () => {
    const me = fixtureMe();
    await setup({}, { ...me, usage: { ...me.usage, savedProducts: { used: 5, limit: 5 } } });
    const notice = host().querySelector('[data-notice="saved-limit"]');
    expect(notice?.textContent).toContain('limit of 5 saved products');
    expect(notice?.querySelector('a[href="/upgrade"]')).not.toBeNull();
  });

  it('removes optimistically and rolls back when the API refuses', async () => {
    const pending = new Subject<void>();
    const remove = vi.fn(() => pending.asObservable());
    await setup({ removeSavedProduct: remove });
    rows()[0].querySelector<HTMLButtonElement>('button[aria-label^="Remove"]')?.click();
    await settle(fixture);
    expect(remove).toHaveBeenCalledWith(DAILY.productId, 'TR');
    expect(rows().map((row) => row.getAttribute('data-product'))).toEqual(['IRON-30']);

    pending.error(new ApiProblem({ kind: 'problem', status: 500, code: 'internal_error' }));
    await settle(fixture);
    expect(rows().map((row) => row.getAttribute('data-product'))).toEqual(['DAILY-60', 'IRON-30']);
    expect(host().textContent).toContain(EN.problem.internal_error.title);
  });

  it('keeps the removal and reloads /me on success', async () => {
    const { reloadMe } = await setup({ removeSavedProduct: vi.fn(() => of(undefined)) });
    rows()[1].querySelector<HTMLButtonElement>('button[aria-label^="Remove"]')?.click();
    await settle(fixture);
    expect(rows().map((row) => row.getAttribute('data-product'))).toEqual(['DAILY-60']);
    expect(reloadMe).toHaveBeenCalled();
  });

  it('switches an alert off optimistically and restores it on failure', async () => {
    const update = vi.fn((): Observable<AlertSubscription> =>
      throwError(() => new ApiProblem({ kind: 'network', status: 0, code: null })),
    );
    await setup({ updateAlert: update });
    rows()[0].querySelector<HTMLButtonElement>('.badge-off')?.click();
    await settle(fixture);
    expect(update).toHaveBeenCalledWith(DROP_ALERT.id, { active: false });
    expect(rows()[0].querySelector('[data-trigger="PRICE_DROP"]')).not.toBeNull();
    expect(host().textContent).toContain(EN.problem.network.title);
  });

  it('creates an alert and renders the server limit notice when refused', async () => {
    const create = vi.fn(() =>
      throwError(() => limitProblem('limits.alert_subscriptions_reached')),
    );
    await setup({ createAlert: create });
    rows()[1].querySelector<HTMLButtonElement>('button[aria-expanded]')?.click();
    await settle(fixture);
    host()
      .querySelector('.add-alert')
      ?.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle(fixture);
    expect(create).toHaveBeenCalledWith({
      productId: IRON.productId,
      countryCode: 'TR',
      trigger: 'PRICE_DROP',
      thresholdPercent: 5,
    });
    const notice = host().querySelector('vd-limit-notice');
    expect(notice?.textContent).toContain('3 of 3 alerts are already active');
    expect(notice?.querySelector('a[href="/upgrade"]')).not.toBeNull();
  });

  it('adds the created alert to the row', async () => {
    const created: AlertSubscription = {
      ...DROP_ALERT,
      id: 'a1000000-0000-4000-8000-000000000009',
      productId: IRON.productId,
      trigger: 'BACK_IN_STOCK',
      thresholdPercent: null,
      baselinePrice: null,
    };
    const create = vi.fn(() => of(created));
    const { reloadMe } = await setup({ createAlert: create });
    rows()[1].querySelector<HTMLButtonElement>('button[aria-expanded]')?.click();
    await settle(fixture);
    const radio = host().querySelector<HTMLInputElement>('.add-alert input[value="BACK_IN_STOCK"]');
    radio?.click();
    radio?.dispatchEvent(new Event('change'));
    await settle(fixture);
    expect(host().querySelector('.add-alert .threshold')).toBeNull();
    host()
      .querySelector('.add-alert')
      ?.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle(fixture);
    expect(create).toHaveBeenCalledWith({
      productId: IRON.productId,
      countryCode: 'TR',
      trigger: 'BACK_IN_STOCK',
    });
    expect(rows()[1].querySelector('[data-trigger="BACK_IN_STOCK"]')).not.toBeNull();
    expect(reloadMe).toHaveBeenCalled();
  });

  it('re-enables an existing inactive subscription before reporting the alert as added', async () => {
    const inactive: AlertSubscription = {
      ...DROP_ALERT,
      id: 'a1000000-0000-4000-8000-000000000007',
      productId: IRON.productId,
      thresholdPercent: 10,
      active: false,
    };
    const create = vi.fn(() => of(inactive));
    const update = vi.fn(() => of({ ...inactive, active: true, thresholdPercent: 5 }));
    const { reloadMe } = await setup({ createAlert: create, updateAlert: update });
    rows()[1].querySelector<HTMLButtonElement>('button[aria-expanded]')?.click();
    await settle(fixture);
    host()
      .querySelector('.add-alert')
      ?.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle(fixture);
    expect(update).toHaveBeenCalledWith(inactive.id, { active: true, thresholdPercent: 5 });
    expect(rows()[1].querySelector('[data-trigger="PRICE_DROP"]')?.textContent).toContain(
      'Price drop ≥ 5 %',
    );
    expect(reloadMe).toHaveBeenCalled();
  });

  it('does not show a re-enabled alert when the activation is refused', async () => {
    const inactive: AlertSubscription = {
      ...DROP_ALERT,
      id: 'a1000000-0000-4000-8000-000000000007',
      productId: IRON.productId,
      active: false,
    };
    const create = vi.fn(() => of(inactive));
    const update = vi.fn(() =>
      throwError(() => limitProblem('limits.alert_subscriptions_reached')),
    );
    await setup({ createAlert: create, updateAlert: update });
    rows()[1].querySelector<HTMLButtonElement>('button[aria-expanded]')?.click();
    await settle(fixture);
    host()
      .querySelector('.add-alert')
      ?.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle(fixture);
    expect(update).toHaveBeenCalled();
    expect(rows()[1].querySelector('[data-trigger="PRICE_DROP"]')).toBeNull();
    expect(host().querySelector('vd-limit-notice')).not.toBeNull();
  });

  it('never lets a slower response for the previous country replace the current list', async () => {
    const slowTr = new Subject<SavedProductList>();
    const usList: SavedProductList = {
      meta: { ...SAVED_LIST.meta, country: 'US' },
      items: [{ ...IRON, countryCode: 'US', code: 'IRON-US' }],
    };
    const list = vi.fn((params: { country: string }) =>
      params.country === 'TR' ? slowTr.asObservable() : of(usList),
    );
    const { countryCode } = configure({ listSavedProducts: list });
    fixture = TestBed.createComponent(SavedProductsPageComponent);
    await settle(fixture);
    countryCode.set('US');
    await settle(fixture);
    expect(rows().map((row) => row.getAttribute('data-product'))).toEqual(['IRON-US']);

    slowTr.next(SAVED_LIST);
    slowTr.complete();
    await settle(fixture);
    expect(rows().map((row) => row.getAttribute('data-product'))).toEqual(['IRON-US']);
  });

  it('rejects a malformed threshold before calling the API', async () => {
    const create = vi.fn(() => of(DROP_ALERT));
    await setup({ createAlert: create });
    rows()[1].querySelector<HTMLButtonElement>('button[aria-expanded]')?.click();
    await settle(fixture);
    const input = host().querySelector<HTMLInputElement>('.add-alert .threshold input');
    if (input) {
      input.value = 'abc';
      input.dispatchEvent(new Event('input'));
    }
    host()
      .querySelector('.add-alert')
      ?.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle(fixture);
    expect(create).not.toHaveBeenCalled();
    expect(input?.getAttribute('aria-invalid')).toBe('true');
  });

  it('shows an empty state and a retryable error', async () => {
    const list = vi
      .fn()
      .mockReturnValueOnce(
        throwError(() => new ApiProblem({ kind: 'problem', status: 500, code: 'internal_error' })),
      )
      .mockReturnValue(of({ ...SAVED_LIST, items: [] }));
    await setup({ listSavedProducts: list });
    expect(host().textContent).toContain(EN.problem.internal_error.title);
    host().querySelector<HTMLButtonElement>('vd-problem-notice button')?.click();
    await settle(fixture);
    expect(host().textContent).toContain(accountEn.saved.empty_title);
  });
});

describe('AlertsPageComponent', () => {
  let fixture: ComponentFixture<AlertsPageComponent>;
  const host = () => fixture.nativeElement as HTMLElement;
  const row = (id: string) => host().querySelector(`tr[data-alert="${id}"]`);

  const PRODUCT = {
    id: DAILY.productId,
    code: DAILY.code,
    brand: DAILY.brand,
    name: DAILY.name,
    availabilityStatus: 'AVAILABLE' as const,
    provenance: SYNTHETIC,
  };
  const ITEMS: AlertListItem[] = [
    { ...DROP_ALERT, product: PRODUCT },
    {
      ...DROP_ALERT,
      id: 'a1000000-0000-4000-8000-000000000002',
      trigger: 'BACK_IN_STOCK',
      thresholdPercent: null,
      baselinePrice: null,
      lastTriggeredAt: null,
      active: false,
      product: { ...PRODUCT, availabilityStatus: 'NOT_SOLD' },
    },
  ];

  async function setup(api: Record<string, unknown>) {
    const spies = configure({ listAlerts: vi.fn(() => of({ items: ITEMS })), ...api });
    fixture = TestBed.createComponent(AlertsPageComponent);
    await settle(fixture);
    return spies;
  }

  it('renders trigger, threshold, baseline, last triggered and the unavailable badge', async () => {
    await setup({});
    const first = row(ITEMS[0].id);
    expect(first?.textContent).toContain('Price drop');
    expect(first?.querySelector<HTMLInputElement>('input')?.value).toBe('5');
    expect(first?.textContent).toContain('TRY');
    expect(first?.textContent).toContain('Nov 18, 2026');
    const second = row(ITEMS[1].id);
    expect(second?.textContent).toContain('Not sold in Türkiye');
    expect(second?.textContent).toContain(accountEn.alerts.never);
    expect(second?.querySelector('[role="switch"]')?.getAttribute('aria-checked')).toBe('false');
  });

  it('activates optimistically and rolls back with the limit notice', async () => {
    const pending = new Subject<AlertSubscription>();
    const update = vi.fn(() => pending.asObservable());
    await setup({ updateAlert: update });
    const toggle = () => row(ITEMS[1].id)?.querySelector<HTMLButtonElement>('[role="switch"]');
    toggle()?.click();
    await settle(fixture);
    expect(update).toHaveBeenCalledWith(ITEMS[1].id, { active: true });
    expect(toggle()?.getAttribute('aria-checked')).toBe('true');

    pending.error(limitProblem('limits.alert_subscriptions_reached'));
    await settle(fixture);
    expect(toggle()?.getAttribute('aria-checked')).toBe('false');
    expect(host().querySelector('vd-limit-notice')?.textContent).toContain(
      '3 of 3 alerts are already active',
    );
  });

  it('keeps the server answer and reloads /me after a successful change', async () => {
    const update = vi.fn(() => of({ ...ITEMS[0], active: false }));
    const { reloadMe } = await setup({ updateAlert: update });
    row(ITEMS[0].id)?.querySelector<HTMLButtonElement>('[role="switch"]')?.click();
    await settle(fixture);
    expect(row(ITEMS[0].id)?.querySelector('[role="switch"]')?.getAttribute('aria-checked')).toBe(
      'false',
    );
    expect(reloadMe).toHaveBeenCalled();
  });

  it('shows email_not_verified from an activation under the row', async () => {
    const update = vi.fn(() =>
      throwError(
        () => new ApiProblem({ kind: 'problem', status: 403, code: 'email_not_verified' }),
      ),
    );
    await setup({ updateAlert: update });
    row(ITEMS[1].id)?.querySelector<HTMLButtonElement>('[role="switch"]')?.click();
    await settle(fixture);
    expect(host().textContent).toContain(EN.problem.email_not_verified.title);
  });

  it('saves a new threshold and rolls it back on a validation error', async () => {
    const update = vi
      .fn()
      .mockReturnValueOnce(of({ ...ITEMS[0], thresholdPercent: 7.5, baselinePrice: 520 }))
      .mockReturnValueOnce(
        throwError(
          () =>
            new ApiProblem({
              kind: 'problem',
              status: 422,
              code: 'validation_failed',
              errors: [{ field: 'thresholdPercent', code: 'out_of_range', message: 'x' }],
            }),
        ),
      );
    await setup({ updateAlert: update });
    const input = () => row(ITEMS[0].id)?.querySelector<HTMLInputElement>('input');
    const change = (value: string) => {
      const element = input();
      if (element) {
        element.value = value;
        element.dispatchEvent(new Event('change'));
      }
    };
    change('7,5');
    await settle(fixture);
    expect(update).toHaveBeenCalledWith(ITEMS[0].id, { thresholdPercent: 7.5 });
    expect(input()?.value).toBe('7.5');

    change('99');
    await settle(fixture);
    expect(update).toHaveBeenCalledTimes(2);
    expect(input()?.getAttribute('aria-invalid')).toBe('true');

    // The stored threshold was rolled back to 7.5: entering it again sends nothing.
    change('7.5');
    await settle(fixture);
    expect(update).toHaveBeenCalledTimes(2);
    expect(input()?.getAttribute('aria-invalid')).toBeNull();

    change('nope');
    await settle(fixture);
    expect(update).toHaveBeenCalledTimes(2);
    expect(input()?.getAttribute('aria-invalid')).toBe('true');
  });

  it('deletes optimistically and restores the row on failure', async () => {
    const pending = new Subject<void>();
    await setup({ deleteAlert: vi.fn(() => pending.asObservable()) });
    row(ITEMS[0].id)?.querySelector<HTMLButtonElement>('button[aria-label^="Delete"]')?.click();
    await settle(fixture);
    expect(row(ITEMS[0].id)).toBeNull();
    pending.error(new ApiProblem({ kind: 'problem', status: 404, code: null }));
    await settle(fixture);
    expect(row(ITEMS[0].id)).not.toBeNull();
    expect(host().querySelectorAll('tr[data-alert]')[0]?.getAttribute('data-alert')).toBe(
      ITEMS[0].id,
    );
  });

  it('shows the empty state', async () => {
    await setup({ listAlerts: vi.fn(() => of({ items: [] })) });
    expect(host().textContent).toContain(accountEn.alerts.empty_title);
  });
});
