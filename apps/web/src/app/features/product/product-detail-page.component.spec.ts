import { EnvironmentProviders, Provider, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Observable, Subject, of, throwError } from 'rxjs';

import productEn from '../../../../public/i18n/product/en.json';
import productTr from '../../../../public/i18n/product/tr.json';
import resultsEn from '../../../../public/i18n/results/en.json';
import resultsTr from '../../../../public/i18n/results/tr.json';
import { ApiClient } from '../../core/api/api-client';
import { ApiProblem } from '../../core/api/api-problem';
import { ProductDetailResponse, SavedProductRef } from '../../core/api/api-types';
import { SessionStore } from '../../core/auth/session.store';
import { CurrencyStore } from '../../core/state/currency.store';
import { RegionStore } from '../../core/state/region.store';
import { FIXTURE_COUNTRIES, fixtureMe, fixtureUser } from '../../testing/api-fixtures';
import { translocoTesting } from '../../testing/transloco-testing';
import { GOLD_A_ID, PRODUCT_NUTRIENTS, productDetail } from './testing/product.fixtures';
import productRoutes from './product.routes';
import { ProductDetailPageComponent } from './product-detail-page.component';

interface Options {
  readonly authenticated?: boolean;
  readonly detail?: ProductDetailResponse;
  readonly error?: ApiProblem;
  readonly currency?: string;
  readonly api?: Record<string, unknown>;
}

function problem(code: ApiProblem['code'], status: number, extra: Partial<ApiProblem> = {}) {
  return new ApiProblem({ kind: 'problem', status, code, requestId: 'req-1', ...extra });
}

async function setup(url: string, options: Options = {}) {
  const authenticated = options.authenticated ?? false;
  const api = {
    getProduct: vi.fn((): Observable<ProductDetailResponse> =>
      options.error ? throwError(() => options.error) : of(options.detail ?? productDetail()),
    ),
    listNutrients: vi.fn(() => of(PRODUCT_NUTRIENTS)),
    saveProduct: vi.fn(() =>
      of<SavedProductRef>({
        productId: GOLD_A_ID,
        countryCode: 'TR',
        savedAt: '2026-11-25T10:00:00Z',
      }),
    ),
    removeSavedProduct: vi.fn(() => of(undefined)),
    createAlert: vi.fn(),
    updateAlert: vi.fn(),
    resendVerification: vi.fn(() => of({ messageKey: 'auth.verify.resent' })),
    ...options.api,
  };
  const session = {
    whenReady: () => Promise.resolve(),
    generation: signal(1),
    isAuthenticated: signal(authenticated),
    status: signal(authenticated ? 'authenticated' : 'anonymous'),
    user: signal(authenticated ? fixtureUser({ emailVerified: false }) : null),
    usage: signal(authenticated ? fixtureMe().usage : null),
    plan: signal(authenticated ? 'FREE' : null),
    reloadMe: vi.fn(() => Promise.resolve()),
  };
  const currency = {
    requestCurrency: signal<string | undefined>(options.currency),
    recordMeta: vi.fn(),
  };
  const region = {
    intlLocale: signal('en-US'),
    countryCode: signal<string | null>('TR'),
    countries: signal(FIXTURE_COUNTRIES),
  };
  const providers: (Provider | EnvironmentProviders)[] = [
    provideRouter([{ path: 'products', children: productRoutes }], withComponentInputBinding()),
    { provide: ApiClient, useValue: api },
    { provide: SessionStore, useValue: session },
    { provide: CurrencyStore, useValue: currency },
    { provide: RegionStore, useValue: region },
  ];
  TestBed.configureTestingModule({
    imports: [
      translocoTesting({
        scopes: {
          product: { en: productEn, tr: productTr },
          results: { en: resultsEn, tr: resultsTr },
        },
      }),
    ],
    providers,
  });
  const harness = await RouterTestingHarness.create();
  await harness.navigateByUrl(url, ProductDetailPageComponent);
  const settle = async (): Promise<void> => {
    for (let round = 0; round < 4; round++) {
      await new Promise((resolve) => setTimeout(resolve));
      harness.detectChanges();
      await harness.fixture.whenStable();
    }
  };
  await settle();
  const root = (): HTMLElement => harness.routeNativeElement as HTMLElement;
  return { harness, api, session, currency, root, settle };
}

function text(element: Element | null): string {
  return (element?.textContent ?? '').replace(/\s+/g, ' ').trim();
}

describe('ProductDetailPageComponent', () => {
  it('loads the product again for a new session so saved and alert state follow the account', async () => {
    const { api, session, settle } = await setup('/products/' + GOLD_A_ID + '?country=TR', {
      authenticated: true,
    });
    expect(api.getProduct).toHaveBeenCalledTimes(1);
    session.generation.set(2);
    await settle();
    expect(api.getProduct).toHaveBeenCalledTimes(2);
  });

  it('requests the product with the country of the URL and the display currency', async () => {
    const { api, currency } = await setup(`/products/${GOLD_A_ID}?country=DE&mode=PRICE`, {
      currency: 'EUR',
    });
    expect(api.getProduct).toHaveBeenCalledWith(GOLD_A_ID, {
      country: 'DE',
      displayCurrency: 'EUR',
    });
    expect(currency.recordMeta).toHaveBeenCalledWith(productDetail().meta);
  });

  it('falls back to the selected country and links the crumb to Find without a results query', async () => {
    const { api, root } = await setup(`/products/${GOLD_A_ID}`);
    expect(api.getProduct).toHaveBeenCalledWith(GOLD_A_ID, { country: 'TR' });
    const crumb = root().querySelector('.crumbs a');
    expect(crumb?.getAttribute('href')).toBe('/');
    expect(text(crumb)).toBe('Find');
  });

  it('links back to the results and to a comparison when the URL carries a query', async () => {
    const { root } = await setup(`/products/${GOLD_A_ID}?country=TR&mode=PRICE`);
    const crumb = root().querySelector('.crumbs a');
    expect(crumb?.getAttribute('href')).toBe('/results?country=TR&mode=PRICE');
    const compare = root().querySelector('[data-action="compare"]');
    expect(compare?.getAttribute('href')).toContain(`cmp=${GOLD_A_ID}`);
  });

  it('renders header, formulation, offers, price card and disclaimer', async () => {
    const { root } = await setup(`/products/${GOLD_A_ID}`);
    expect(text(root().querySelector('h1'))).toBe('Gold Multi A');
    expect(text(root().querySelector('.pack'))).toContain('Tablet');
    expect(text(root().querySelector('.pack'))).toContain('60 per container');
    expect(text(root().querySelector('.pack'))).toContain('1 serving a day');
    expect(text(root().querySelector('.pack'))).toContain('60 days');
    expect(text(root().querySelector('[data-kind="synthetic"]'))).toBe('Synthetic demo product');

    const vitC = root().querySelector('tr[data-code="VIT_C"]');
    expect(text(vitC)).toContain('Vitamin C');
    expect(text(vitC)).toContain('ascorbic acid');
    expect(text(vitC)).toContain('300 mg');
    const zinc = root().querySelector('tr[data-code="ZINC"]');
    expect(text(zinc)).toContain('—');
    const vitD = root().querySelector('tr[data-code="VIT_D"]');
    expect(text(vitD)).toContain('10 mcg');
    expect(text(vitD)).toContain('Vitamin D3 (cholecalciferol)');
    expect(text(vitD)).toContain('IU conversion assumed');

    const store = root().querySelector('tr[data-retailer="Demo Store"]');
    expect(store?.querySelector('[data-marker="used-for-cost"]')).not.toBeNull();
    expect(store?.querySelector('[data-stock="in"]')).not.toBeNull();
    const market = root().querySelector('tr[data-retailer="Demo Market"]');
    expect(market?.querySelector('[data-marker="used-for-cost"]')).toBeNull();
    expect(market?.querySelector('[data-stock="out"]')).not.toBeNull();
    expect(text(market?.querySelector('[data-code="PRICE_STALE"]') ?? null)).toBe(
      'Price may be outdated',
    );
    const visit = market?.querySelector('a[target="_blank"]');
    expect(visit?.getAttribute('rel')).toBe('noopener noreferrer nofollow');
    expect(visit?.getAttribute('aria-label')).toBe('Visit Demo Market (opens in a new tab)');
    expect(text(root())).toContain('Demo prices, not real retailer data');

    expect(text(root().querySelector('.price .big'))).toBe('TRY 13.33');
    expect(text(root().querySelector('[data-availability="AVAILABLE"]'))).toBe('Sold in Türkiye');
    expect(root().querySelector('[data-key="disclaimer.not_medical_advice"]')).not.toBeNull();
  });

  it('labels a US label snapshot with its date and the identification-only line', async () => {
    const detail = productDetail();
    const snapshot: ProductDetailResponse = {
      ...detail,
      product: {
        ...detail.product,
        synthetic: false,
        provenance: {
          synthetic: false,
          source: 'DSLD',
          sourceRef: '239077',
          sourceEntryDate: '2020-11-21',
        },
      },
    };
    const { root } = await setup(`/products/${GOLD_A_ID}`, { detail: snapshot });
    expect(text(root().querySelector('[data-kind="snapshot"]'))).toBe(
      'US label snapshot · Nov 21, 2020',
    );
    expect(text(root())).toContain('Product names are for identification only; no affiliation.');
  });

  it('turns save and create-alert into login links for anonymous visitors', async () => {
    const { root } = await setup(`/products/${GOLD_A_ID}?country=TR`);
    const links = Array.from(root().querySelectorAll<HTMLAnchorElement>('a[data-action]'))
      .filter((link) => link.dataset['action'] !== 'compare')
      .map((link) => link.getAttribute('href'));
    expect(links).toHaveLength(2);
    for (const href of links) {
      expect(href).toBe(
        `/auth/login?returnUrl=${encodeURIComponent(`/products/${GOLD_A_ID}?country=TR`)}`,
      );
    }
    expect(root().querySelector('button[data-action="save"]')).toBeNull();
  });

  it('highlights and focuses the rows named by an EXCEEDS_UL flag', async () => {
    const detail = productDetail();
    const flagged: ProductDetailResponse = {
      ...detail,
      product: { ...detail.product, flags: [{ code: 'EXCEEDS_UL', nutrientCodes: ['ZINC'] }] },
    };
    const { root, settle } = await setup(`/products/${GOLD_A_ID}`, { detail: flagged });
    const zinc = root().querySelector<HTMLElement>('tr[data-code="ZINC"]');
    // The row marker comes from `upperLimit.exceeded` only; the flag link adds the highlight.
    expect(zinc?.classList.contains('is-hl')).toBe(false);
    root().querySelector<HTMLButtonElement>('vd-flag-badge button')?.click();
    await settle();
    expect(zinc?.classList.contains('is-hl')).toBe(true);
    expect(document.activeElement).toBe(zinc);
  });

  it('shows reference and upper-limit columns next to the not-medical-advice disclaimer', async () => {
    const { root } = await setup(`/products/${GOLD_A_ID}`);
    const headers = Array.from(root().querySelectorAll('vd-formulation-table th')).map(text);
    expect(headers).toEqual([
      'Nutrient',
      'Form on label',
      'Per serving',
      'Per day',
      'Of reference',
      'Upper limit',
    ]);
    const vitC = root().querySelector('tr[data-code="VIT_C"]');
    expect(text(vitC?.querySelector('[data-col="reference"]') ?? null)).toBe(
      '375% of 80 mg · EU NRV',
    );
    expect(text(vitC?.querySelector('[data-col="upper-limit"]') ?? null)).toBe('none set');
    expect(root().querySelector('[data-key="disclaimer.not_medical_advice"]')).not.toBeNull();
  });

  it('saves optimistically and rolls back with the server limit notice', async () => {
    const answer = new Subject<SavedProductRef>();
    const { root, settle, api } = await setup(`/products/${GOLD_A_ID}`, {
      authenticated: true,
      api: { saveProduct: vi.fn(() => answer) },
    });
    const save = root().querySelector<HTMLButtonElement>('button[data-action="save"]');
    expect(save?.getAttribute('aria-pressed')).toBe('false');
    save?.click();
    await settle();
    expect(save?.getAttribute('aria-pressed')).toBe('true');
    expect(api.saveProduct).toHaveBeenCalledWith({ productId: GOLD_A_ID, countryCode: 'TR' });

    answer.error(
      problem('entitlement_exceeded', 403, {
        limit: 5,
        used: 5,
        plan: 'FREE',
        upgradeTo: 'PRO',
        upgradeHint: { messageKey: 'limits.saved_products_reached', ctaPath: '/upgrade' },
      }),
    );
    await settle();
    expect(save?.getAttribute('aria-pressed')).toBe('false');
    const notice = root().querySelector('vd-limit-notice');
    expect(text(notice)).toContain('You have saved 5 of 5 products.');
    expect(notice?.querySelector('a[href="/upgrade"]')).not.toBeNull();
    expect(notice?.querySelector('a[href="/account/saved"]')).not.toBeNull();
  });

  it('validates the price-drop threshold and creates the alert', async () => {
    const createAlert = vi.fn(() =>
      of({
        id: '7c8d9e0f-1a2b-4c3d-8e4f-5a6b7c8d9e0f',
        productId: GOLD_A_ID,
        countryCode: 'TR',
        trigger: 'PRICE_DROP' as const,
        thresholdPercent: 7.5,
        baselinePrice: 800,
        baselineCurrency: 'TRY',
        active: true,
        createdAt: '2026-11-25T10:00:00Z',
        lastTriggeredAt: null,
      }),
    );
    const { root, settle } = await setup(`/products/${GOLD_A_ID}`, {
      authenticated: true,
      api: { createAlert },
    });
    const open = root().querySelector<HTMLButtonElement>('button[data-action="create-alert"]');
    open?.click();
    await settle();
    expect(open?.getAttribute('aria-expanded')).toBe('true');
    const form = root().querySelector('vd-alert-form');
    expect(text(form)).toContain("from today's TRY 800.00");
    expect(text(form)).toContain('You are using 1 of 3 alerts on FREE.');

    const input = form?.querySelector('input.inp') as HTMLInputElement;
    input.value = '0';
    input.dispatchEvent(new Event('input'));
    form?.querySelector<HTMLButtonElement>('button[type="submit"]')?.click();
    await settle();
    expect(createAlert).not.toHaveBeenCalled();
    expect(input?.getAttribute('aria-invalid')).toBe('true');
    expect(text(form)).toContain('Enter a value from 0.01 to 99.99');

    input.value = '7.5';
    input.dispatchEvent(new Event('input'));
    form?.querySelector<HTMLButtonElement>('button[type="submit"]')?.click();
    await settle();
    expect(createAlert).toHaveBeenCalledWith({
      productId: GOLD_A_ID,
      countryCode: 'TR',
      trigger: 'PRICE_DROP',
      thresholdPercent: 7.5,
    });
    expect(root().querySelector('vd-alert-form')).toBeNull();
    expect(document.activeElement).toBe(open);
    expect(text(root())).toContain('Alert created.');
    expect(text(root().querySelector('[data-trigger="PRICE_DROP"]'))).toBe('Price drop · active');
  });

  it('sends no threshold for a back-in-stock alert', async () => {
    const createAlert = vi.fn(() => throwError(() => problem('internal_error', 500)));
    const { root, settle } = await setup(`/products/${GOLD_A_ID}`, {
      authenticated: true,
      api: { createAlert },
    });
    root().querySelector<HTMLButtonElement>('button[data-action="create-alert"]')?.click();
    await settle();
    const radio = root().querySelector('input[data-trigger="BACK_IN_STOCK"]') as HTMLInputElement;
    radio.checked = true;
    radio.dispatchEvent(new Event('change'));
    await settle();
    root().querySelector<HTMLButtonElement>('vd-alert-form button[type="submit"]')?.click();
    await settle();
    expect(createAlert).toHaveBeenCalledWith({
      productId: GOLD_A_ID,
      countryCode: 'TR',
      trigger: 'BACK_IN_STOCK',
    });
    expect(root().querySelector('vd-alert-form')).not.toBeNull();
  });

  it('asks to verify the e-mail and resends the link', async () => {
    const { root, settle, api } = await setup(`/products/${GOLD_A_ID}`, {
      authenticated: true,
      api: { createAlert: vi.fn(() => throwError(() => problem('email_not_verified', 403))) },
    });
    root().querySelector<HTMLButtonElement>('button[data-action="create-alert"]')?.click();
    await settle();
    root().querySelector<HTMLButtonElement>('vd-alert-form button[type="submit"]')?.click();
    await settle();
    const notice = root().querySelector('[data-code="email_not_verified"]');
    expect(text(notice)).toContain('Verify your e-mail first');
    notice?.querySelector<HTMLButtonElement>('button')?.click();
    await settle();
    expect(api.resendVerification).toHaveBeenCalledWith({ email: 'user@example.com' });
    expect(text(root())).toContain('If this address needs verification, a new link is on its way.');
  });

  it('shows the not-found state for an unknown product', async () => {
    const { root } = await setup(`/products/${GOLD_A_ID}`, {
      error: problem('product_not_found', 404),
    });
    const state = root().querySelector('[data-state="not-found"]');
    expect(text(state)).toContain('Product not found');
    expect(state?.querySelector('a')?.getAttribute('href')).toBe('/');
    expect(root().querySelector('h1')).toBeNull();
  });

  it('names the country when the product is not sold there', async () => {
    const { root } = await setup(`/products/${GOLD_A_ID}?country=TR`, {
      error: problem('product_not_available_in_country', 404),
    });
    expect(text(root().querySelector('[data-state="not-available"]'))).toContain(
      'Not sold in Türkiye',
    );
  });

  it('offers a retry for other failures', async () => {
    const getProduct = vi
      .fn()
      .mockReturnValueOnce(throwError(() => problem('internal_error', 500)))
      .mockReturnValueOnce(of(productDetail()));
    const { root, settle } = await setup(`/products/${GOLD_A_ID}`, { api: { getProduct } });
    const notice = root().querySelector('vd-problem-notice');
    expect(text(notice)).toContain('Request ID req-1');
    notice?.querySelector<HTMLButtonElement>('button')?.click();
    await settle();
    expect(getProduct).toHaveBeenCalledTimes(2);
    expect(text(root().querySelector('h1'))).toBe('Gold Multi A');
  });
});
