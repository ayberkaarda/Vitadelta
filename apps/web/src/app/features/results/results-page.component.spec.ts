import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { provideTranslocoScope } from '@jsverse/transloco';
import { Observable, Subject, of, throwError } from 'rxjs';

import resultsEn from '../../../../public/i18n/results/en.json';
import resultsTr from '../../../../public/i18n/results/tr.json';
import { ApiClient } from '../../core/api/api-client';
import { ApiProblem, ApiProblemInit } from '../../core/api/api-problem';
import { RecommendationQueryRequest, RecommendationQueryResponse } from '../../core/api/api-types';
import { SessionStore } from '../../core/auth/session.store';
import { CurrencyStore } from '../../core/state/currency.store';
import { RegionStore } from '../../core/state/region.store';
import { FIXTURE_COUNTRIES } from '../../testing/api-fixtures';
import { EN, translocoTesting } from '../../testing/transloco-testing';
import { ResultsPageComponent } from './results-page.component';
import { FIELD_ERRORS_STATE } from './shared/navigation-state';
import {
  FIXTURE_DEFAULTS,
  FIXTURE_NUTRIENTS,
  FIXTURE_PLANS,
  GOLD_A_ID,
  GOLD_B_ID,
  anonymousMeta,
  anonymousResponse,
  emptyResponse,
  goldA,
  goldAlert,
  goldB,
  userResponse,
  zincForte,
} from './testing/golden.fixtures';

const text = (element: Element | null | undefined) =>
  element?.textContent?.replace(/\s+/g, ' ').trim() ?? '';

type Query = (body: RecommendationQueryRequest) => Observable<RecommendationQueryResponse>;

function problem(init: Partial<ApiProblemInit> & Pick<ApiProblemInit, 'status'>): ApiProblem {
  return new ApiProblem({ kind: 'problem', code: null, ...init });
}

describe('ResultsPageComponent', () => {
  let query: ReturnType<typeof vi.fn<Query>>;
  let api: Record<string, ReturnType<typeof vi.fn>>;
  let session: {
    status: ReturnType<typeof signal<string>>;
    isAuthenticated: ReturnType<typeof signal<boolean>>;
    whenReady: () => Promise<void>;
    generation: ReturnType<typeof signal<number>>;
    applyPrincipal: ReturnType<typeof vi.fn>;
    applyQuotaProblem: ReturnType<typeof vi.fn>;
    reloadMe: ReturnType<typeof vi.fn>;
    user: ReturnType<typeof signal<{ email: string } | null>>;
  };
  let currency: {
    requestCurrency: ReturnType<typeof signal<string | undefined>>;
    recordMeta: ReturnType<typeof vi.fn>;
  };

  function setup(result: Query, signedIn = false) {
    query = vi.fn(result);
    api = {
      queryRecommendations: query,
      getDefaultWeights: vi.fn(() => of(FIXTURE_DEFAULTS)),
      listNutrients: vi.fn(() => of(FIXTURE_NUTRIENTS)),
      listPlans: vi.fn(() => of(FIXTURE_PLANS)),
      saveProduct: vi.fn(),
      removeSavedProduct: vi.fn(),
      createAlert: vi.fn(),
      updateAlert: vi.fn(),
      resendVerification: vi.fn(),
    };
    session = {
      status: signal(signedIn ? 'authenticated' : 'anonymous'),
      isAuthenticated: signal(signedIn),
      whenReady: () => Promise.resolve(),
      generation: signal(1),
      applyPrincipal: vi.fn(),
      applyQuotaProblem: vi.fn(),
      reloadMe: vi.fn(() => Promise.resolve()),
      user: signal(signedIn ? { email: 'user@example.com' } : null),
    };
    currency = { requestCurrency: signal<string | undefined>(undefined), recordMeta: vi.fn() };
    TestBed.configureTestingModule({
      imports: [translocoTesting({ scopes: { results: { en: resultsEn, tr: resultsTr } } })],
      providers: [
        provideRouter(
          [
            { path: 'results', component: ResultsPageComponent },
            { path: '', children: [] },
            { path: 'compare/:a/:b', children: [] },
            { path: 'products/:id', children: [] },
            { path: 'auth/login', children: [] },
            { path: 'auth/register', children: [] },
          ],
          withComponentInputBinding(),
        ),
        provideTranslocoScope('results'),
        { provide: ApiClient, useValue: api },
        { provide: SessionStore, useValue: session },
        { provide: CurrencyStore, useValue: currency },
        {
          provide: RegionStore,
          useValue: {
            intlLocale: signal('en-US'),
            countryCode: signal('TR'),
            countries: signal(FIXTURE_COUNTRIES),
            loadCountries: vi.fn(async () => undefined),
            setCountry: vi.fn(),
          },
        },
      ],
    });
  }

  async function open(url = '/results?country=TR') {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url, ResultsPageComponent);
    await settle(harness);
    return harness;
  }

  async function settle(harness: RouterTestingHarness) {
    for (let i = 0; i < 3; i++) {
      await harness.fixture.whenStable();
    }
  }

  const host = (harness: RouterTestingHarness) => harness.routeNativeElement as HTMLElement;
  const rows = (page: HTMLElement) =>
    Array.from(
      page.querySelectorAll<HTMLTableRowElement>('vd-results-table tbody tr[data-product]'),
    );

  it('builds the request from the URL and the display currency', async () => {
    setup(() => of(anonymousResponse()));
    currency.requestCurrency.set('EUR');
    await open(
      '/results?country=TR&mode=CUSTOM&lambda=0.3&w=MINERAL:1.5&req=VIT_D:25:mcg&ul=exclude',
    );
    expect(query).toHaveBeenCalledTimes(1);
    expect(query.mock.calls[0][0]).toEqual({
      country: 'TR',
      stockFilter: 'IN_STOCK_ONLY',
      ranking: { mode: 'CUSTOM', lambda: 0.3 },
      weights: { categories: { MINERAL: 1.5 } },
      requiredNutrients: [{ code: 'VIT_D', minAmountPerDay: 25, unit: 'mcg' }],
      excludeExceedingUl: true,
      page: 1,
      pageSize: 20,
      displayCurrency: 'EUR',
    });
  });

  it('renders the anonymous ranking exactly as returned, with locked rows only from meta.visibility', async () => {
    setup(() => of(anonymousResponse()));
    const harness = await open();
    const page = host(harness);

    expect(text(page.querySelector('h1'))).toBe('8 products ranked');
    expect(text(page.querySelector('.eyebrow'))).toBe('Results · Türkiye · Value');
    expect(text(page.querySelector('.summary'))).toContain(
      '1 product left out because no offer is in stock',
    );
    expect(rows(page).map((row) => row.dataset['product'])).toEqual([
      'GOLD-A',
      'GOLD-B',
      'MV-ZN40',
    ]);
    expect(text(rows(page)[0].querySelector('vd-money'))).toContain('TRY 13.33');
    expect(text(rows(page)[0].querySelector('.value'))).toContain('raw 0.3729');
    expect(text(page.querySelector('.toolbar'))).toContain(
      'Market rate TRY 2.77 per content point per day (median of 9)',
    );

    const locked = Array.from(page.querySelectorAll('vd-locked-results .locked-row:not(.tail)'));
    expect(locked.map((row) => row.getAttribute('aria-label'))).toEqual([
      'Result 4, locked',
      'Result 5, locked',
      'Result 6, locked',
      'Result 7, locked',
      'Result 8, locked',
    ]);
    const lockedText = text(page.querySelector('vd-locked-results'));
    for (const item of anonymousResponse().items) {
      expect(lockedText).not.toContain(item.name);
      expect(lockedText).not.toContain(item.code);
    }
    expect(lockedText).toContain('5 more results are locked');
    expect(session.applyPrincipal).toHaveBeenCalledWith(anonymousMeta().principal);
    expect(currency.recordMeta).toHaveBeenCalled();
    expect(text(page)).toContain(EN.disclaimer.not_medical_advice);
    expect(page.querySelector('vd-pagination nav')).toBeNull();

    // Anonymous save and alert are login links that return to the results.
    const save = rows(page)[0].querySelector('a[data-action="save"]');
    expect(save?.getAttribute('href')).toContain('/auth/login?returnUrl=');
    expect(decodeURIComponent(save?.getAttribute('href') ?? '')).toContain('/results?country=TR');
  });

  it('never invents locked rows when the server reports none', async () => {
    setup(() => of(userResponse()), true);
    const page = host(await open());
    expect(page.querySelector('vd-locked-results .locked-row')).toBeNull();
    expect(page.querySelector('.cta')).toBeNull();
  });

  it('shows the quota-exhausted state on 403 quota_exceeded', async () => {
    const exceeded = problem({
      status: 403,
      code: 'quota_exceeded',
      limit: 3,
      used: 3,
      upgradeHint: { messageKey: 'quota.exhausted', ctaPath: '/auth/register' },
    });
    setup(() => throwError(() => exceeded));
    const page = host(await open());
    expect(text(page.querySelector('vd-quota-exhausted h2'))).toBe(
      'You have used your 3 free queries',
    );
    expect(text(page.querySelector('vd-quota-exhausted'))).toContain('Save up to 5 products');
    expect(text(page.querySelector('vd-quota-exhausted'))).toContain('3 price or stock alerts');
    expect(page.querySelector('vd-quota-exhausted a[href="/auth/register"]')).not.toBeNull();
    expect(session.applyQuotaProblem).toHaveBeenCalledWith(exceeded);
    expect(page.querySelector('vd-results-table')).toBeNull();
  });

  it('shows 503 scoring_unavailable with the request id and retries', async () => {
    let calls = 0;
    setup(() =>
      ++calls === 1
        ? throwError(() =>
            problem({ status: 503, code: 'scoring_unavailable', requestId: '7f3c9a0e-52d1' }),
          )
        : of(anonymousResponse()),
    );
    const harness = await open();
    const page = host(harness);
    const error = page.querySelector('vd-results-error');
    expect(text(error)).toContain('Ranking is temporarily unavailable');
    expect(text(error)).toContain('Request ID 7f3c9a0e-52d1');
    error?.querySelector<HTMLButtonElement>('button')?.click();
    await settle(harness);
    expect(query).toHaveBeenCalledTimes(2);
    expect(rows(page)).toHaveLength(3);
  });

  it('counts down Retry-After on 429 before the retry button enables', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    try {
      setup(() =>
        throwError(() => problem({ status: 429, code: 'rate_limited', retryAfterSeconds: 2 })),
      );
      const harness = await open();
      const button = () =>
        host(harness).querySelector<HTMLButtonElement>('vd-results-error button');
      expect(button()?.disabled).toBe(true);
      expect(text(button())).toBe('Try again in 2 s');
      vi.advanceTimersByTime(2000);
      await settle(harness);
      expect(button()?.disabled).toBe(false);
      expect(text(button())).toBe('Try again');
    } finally {
      vi.useRealTimers();
    }
  });

  it('explains an empty ranking from meta.excluded', async () => {
    setup(() => of(emptyResponse()));
    const harness = await open();
    const empty = host(harness).querySelector('vd-results-empty');
    expect(text(empty)).toContain('No product matches all your requirements');
    const counts = Array.from(empty?.querySelectorAll('dt') ?? []).map(
      (dt) => `${text(dt)}: ${text(dt.nextElementSibling)}`,
    );
    expect(counts).toEqual([
      'Missing a required nutrient: 5',
      'Above an upper limit (excluded): 2',
      'No offer in stock: 1',
      'No offer in Türkiye: 0',
    ]);
    const include = empty?.querySelector<HTMLButtonElement>('button');
    include?.click();
    await settle(harness);
    expect(query.mock.calls[1][0].stockFilter).toBe('INCLUDE_OUT_OF_STOCK');
  });

  it('shows the FX notices from meta.fx', async () => {
    const unavailable = anonymousResponse();
    unavailable.meta.fx = {
      base: 'TRY',
      quote: 'GBP',
      rate: null,
      asOf: null,
      source: null,
      stale: false,
      status: 'UNAVAILABLE',
    };
    setup(() => of(unavailable));
    const page = host(await open());
    expect(text(page.querySelector('[data-state="unavailable"]'))).toContain(
      'Prices are shown in TRY',
    );
  });

  it('sends validation_failed field errors back to the query page', async () => {
    setup(() =>
      throwError(() =>
        problem({
          status: 422,
          code: 'validation_failed',
          errors: [{ field: 'weights', code: 'weights_all_zero', message: 'all zero' }],
        }),
      ),
    );
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate');
    await open('/results?country=TR&o=VIT_C:0');
    expect(navigate).toHaveBeenCalledWith(['/'], {
      queryParams: { country: 'TR', o: 'VIT_C:0' },
      state: {
        [FIELD_ERRORS_STATE]: [{ field: 'weights', code: 'weights_all_zero', message: 'all zero' }],
      },
      replaceUrl: true,
    });
  });

  it('opens the breakdown drawer with references, markers and the ceiling line', async () => {
    setup(() => of(userResponse()), true);
    const harness = await open();
    const page = host(harness);
    rows(page)[1].querySelector<HTMLButtonElement>('[data-action="breakdown"]')?.click();
    await settle(harness);

    const dialog = document.querySelector('[role="dialog"]');
    expect(dialog).not.toBeNull();
    expect(text(dialog?.querySelector('h2'))).toBe('Gold Multi B · score breakdown');
    const items = Array.from(dialog?.querySelectorAll('.bd-row') ?? []);
    expect(items.map((item) => text(item.querySelector('.ncode')))).toEqual([
      'MAGNESIUM',
      'VIT_B12',
      'VIT_C',
      'VIT_D',
      'ZINC',
    ]);
    const vitC = items[2];
    expect(text(vitC)).toContain('At ceiling, extra earns nothing');
    expect(text(vitC)).toContain('350 mg');
    expect(text(vitC)).toContain('80 mg');
    expect(text(vitC)).toContain('EU NRV');
    expect(text(vitC)).toContain('438%');
    expect(text(vitC)).toContain('1.5000 × 1.0');
    expect(text(vitC.querySelector('vd-coverage-strip'))).toContain('ceiling 417%');
    expect(text(dialog)).toContain('Content score 5.0093');
    expect(document.activeElement?.getAttribute('aria-label')).toBe('Close the breakdown');

    document.activeElement?.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }),
    );
    await settle(harness);
    expect(document.querySelector('[role="dialog"]')).toBeNull();
  });

  it('jumps to the affected nutrient from an EXCEEDS_UL flag', async () => {
    setup(() => of(userResponse()), true);
    const harness = await open();
    rows(host(harness))[2]
      .querySelector<HTMLButtonElement>('vd-flag-badge button[data-code="EXCEEDS_UL"]')
      ?.click();
    await settle(harness);
    const target = document.querySelector('.bd-row.is-target');
    expect(text(target?.querySelector('.ncode'))).toBe('ZINC');
    expect(text(target)).toContain('Capped at upper limit');
  });

  it('saves optimistically and rolls back with the limit notice on entitlement_exceeded', async () => {
    setup(() => of(userResponse()), true);
    let fail: (error: unknown) => void = () => undefined;
    api['saveProduct'].mockReturnValue(
      new Observable((subscriber) => {
        fail = (error) => subscriber.error(error);
      }),
    );
    const harness = await open();
    const page = host(harness);
    const save = () => rows(page)[1].querySelector<HTMLButtonElement>('button[data-action="save"]');
    expect(save()?.getAttribute('aria-pressed')).toBe('false');
    save()?.click();
    await settle(harness);
    expect(api['saveProduct']).toHaveBeenCalledWith({ productId: GOLD_B_ID, countryCode: 'TR' });
    expect(save()?.getAttribute('aria-pressed')).toBe('true');

    fail(
      problem({
        status: 403,
        code: 'entitlement_exceeded',
        limit: 5,
        used: 5,
        plan: 'FREE',
        upgradeTo: 'PRO',
        upgradeHint: { messageKey: 'limits.saved_products_reached', ctaPath: '/upgrade' },
      }),
    );
    await settle(harness);
    expect(save()?.getAttribute('aria-pressed')).toBe('false');
    const notice = page.querySelector('.note-row vd-limit-notice');
    expect(text(notice)).toContain('You have saved 5 of 5 products');
    expect(notice?.querySelector('a[href="/upgrade"]')).not.toBeNull();
    expect(notice?.querySelector('a[href="/account/saved"]')).not.toBeNull();
  });

  it('removes a saved product and turns an active price alert off', async () => {
    setup(() => of(userResponse()), true);
    api['removeSavedProduct'].mockReturnValue(of(undefined));
    api['updateAlert'].mockImplementation((id: string, body: { active: boolean }) =>
      of({ ...userResponse().items[0].alerts[0], id, ...body }),
    );
    const harness = await open();
    const first = rows(host(harness))[0];
    first.querySelector<HTMLButtonElement>('button[data-action="save"]')?.click();
    await settle(harness);
    expect(api['removeSavedProduct']).toHaveBeenCalledWith(GOLD_A_ID, 'TR');
    expect(first.querySelector('button[data-action="save"]')?.getAttribute('aria-pressed')).toBe(
      'false',
    );
    const alert = first.querySelector<HTMLButtonElement>('button[data-action="alert"]');
    expect(alert?.getAttribute('aria-pressed')).toBe('true');
    alert?.click();
    await settle(harness);
    expect(api['updateAlert']).toHaveBeenCalledWith(goldAlert().id, { active: false });
    expect(alert?.getAttribute('aria-pressed')).toBe('false');
  });

  it('creates a price alert and shows email_not_verified with a resend action', async () => {
    setup(() => of(userResponse()), true);
    api['createAlert'].mockReturnValue(
      throwError(() => problem({ status: 403, code: 'email_not_verified' })),
    );
    api['resendVerification'].mockReturnValue(of({ messageKey: 'auth.verify.resent' }));
    const harness = await open();
    const page = host(harness);
    const alert = rows(page)[1].querySelector<HTMLButtonElement>('button[data-action="alert"]');
    alert?.click();
    await settle(harness);
    expect(api['createAlert']).toHaveBeenCalledWith({
      productId: GOLD_B_ID,
      countryCode: 'TR',
      trigger: 'PRICE_DROP',
    });
    expect(alert?.getAttribute('aria-pressed')).toBe('false');
    const notice = page.querySelector('[data-code="email_not_verified"]');
    expect(text(notice)).toContain('Verify your e-mail first');
    notice?.querySelector<HTMLButtonElement>('button')?.click();
    await settle(harness);
    expect(api['resendVerification']).toHaveBeenCalledWith({ email: 'user@example.com' });
    expect(text(page.querySelector('.note-row'))).toContain(EN.auth.verify.resent);
  });

  it('selects two products for the comparison, replacing the oldest on a third', async () => {
    setup(() => of(userResponse()), true);
    const router = TestBed.inject(Router);
    const harness = await open('/results?country=TR&stock=all&page=1');
    const page = host(harness);
    const boxes = () =>
      rows(page).map((row) => row.querySelector<HTMLInputElement>('input[type="checkbox"]'));
    boxes()[0]?.click();
    boxes()[1]?.click();
    boxes()[2]?.click();
    await settle(harness);
    expect(rows(page).map((row) => row.classList.contains('is-selected'))).toEqual([
      false,
      true,
      true,
    ]);
    expect(text(page)).toContain(
      'Only two products can be compared, so Gold Multi A was taken out',
    );
    const navigate = vi.spyOn(router, 'navigate');
    page.querySelector<HTMLButtonElement>('vd-compare-bar .go')?.click();
    expect(navigate).toHaveBeenCalledWith(['/compare', GOLD_B_ID, zincForte().productId], {
      queryParams: { country: 'TR', stock: 'all' },
    });
  });

  it('preselects the product of a "Compare with" link', async () => {
    setup(() => of(userResponse()), true);
    const page = host(await open(`/results?country=TR&cmp=${GOLD_B_ID}`));
    expect(text(page.querySelector('vd-compare-bar'))).toContain('Gold Multi B');
  });

  it('pages through a signed-in ranking', async () => {
    const response = userResponse();
    response.meta = {
      ...response.meta,
      total: 45,
      visibility: { shown: 20, total: 45, locked: 0, reason: null },
    };
    response.items = [goldA(), goldB()];
    setup(() => of(response), true);
    const router = TestBed.inject(Router);
    const harness = await open();
    const navigate = vi.spyOn(router, 'navigate');
    const next = Array.from(
      host(harness).querySelectorAll<HTMLButtonElement>('vd-pagination button'),
    )[1];
    next.click();
    expect(navigate).toHaveBeenCalledWith(['/results'], {
      queryParams: { country: 'TR', page: '2' },
    });
  });

  it('drops the previous account rows and runs the query again when the session changes', async () => {
    let calls = 0;
    const pending = new Subject<RecommendationQueryResponse>();
    setup(() => (++calls === 1 ? of(userResponse()) : pending.asObservable()), true);
    const harness = await open();
    expect(rows(host(harness)).length).toBeGreaterThan(0);

    session.generation.set(2);
    await settle(harness);
    expect(query).toHaveBeenCalledTimes(2);
    expect(rows(host(harness))).toHaveLength(0);
    pending.next(anonymousResponse());
    pending.complete();
    await settle(harness);
    expect(rows(host(harness)).length).toBeGreaterThan(0);
  });

  it('drops URL values outside the registries and contract bounds before any request', async () => {
    setup(() => of(anonymousResponse()));
    const harness = await open(
      '/results?country=ZZ&mode=CUSTOM&lambda=-1&w=VITAMIN:99,MINERAL:1.5&o=UNKNOWN:4,ZINC:2' +
        '&req=VIT_D:25,VIT_C,BORON&page=1e100&size=500',
    );
    expect(query.mock.calls.map(([body]) => body.country)).not.toContain('ZZ');
    expect(query).toHaveBeenCalledTimes(1);
    expect(query.mock.calls[0][0]).toEqual({
      country: 'TR',
      stockFilter: 'IN_STOCK_ONLY',
      ranking: { mode: 'CUSTOM' },
      weights: { categories: { MINERAL: 1.5 }, overrides: { ZINC: 2 } },
      requiredNutrients: [{ code: 'VIT_C' }],
      excludeExceedingUl: false,
      page: 1,
      pageSize: 20,
    });
    await settle(harness);
    expect(TestBed.inject(Router).url).toBe(
      '/results?mode=CUSTOM&w=MINERAL:1.5&o=ZINC:2&req=VIT_C',
    );
  });
});
