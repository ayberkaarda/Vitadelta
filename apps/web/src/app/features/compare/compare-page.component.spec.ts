import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { provideTranslocoScope } from '@jsverse/transloco';
import { Observable, of, throwError } from 'rxjs';

import compareEn from '../../../../public/i18n/compare/en.json';
import compareTr from '../../../../public/i18n/compare/tr.json';
import { ApiClient } from '../../core/api/api-client';
import { ApiProblem, ApiProblemInit } from '../../core/api/api-problem';
import { CompareRequest, CompareResponse } from '../../core/api/api-types';
import { SessionStore } from '../../core/auth/session.store';
import { CurrencyStore } from '../../core/state/currency.store';
import { RegionStore } from '../../core/state/region.store';
import { FIXTURE_COUNTRIES } from '../../testing/api-fixtures';
import { EN, translocoTesting } from '../../testing/transloco-testing';
import {
  FIXTURE_DEFAULTS,
  GOLD_A_ID,
  GOLD_B_ID,
  goldenCompare,
} from '../results/testing/golden.fixtures';
import { ComparePageComponent } from './compare-page.component';

const text = (element: Element | null | undefined) =>
  element?.textContent?.replace(/\s+/g, ' ').trim() ?? '';

describe('ComparePageComponent', () => {
  let compare: ReturnType<typeof vi.fn<(body: CompareRequest) => Observable<CompareResponse>>>;
  let requestCurrency: ReturnType<typeof signal<string | undefined>>;
  let recordMeta: ReturnType<typeof vi.fn>;

  function setup(result: () => Observable<CompareResponse>) {
    compare = vi.fn(result);
    requestCurrency = signal<string | undefined>(undefined);
    recordMeta = vi.fn();
    TestBed.configureTestingModule({
      imports: [translocoTesting({ scopes: { compare: { en: compareEn, tr: compareTr } } })],
      providers: [
        provideRouter(
          [
            { path: 'compare/:baselineId/:candidateId', component: ComparePageComponent },
            { path: 'results', children: [] },
            { path: 'products/:id', children: [] },
          ],
          withComponentInputBinding(),
        ),
        provideTranslocoScope('compare'),
        {
          provide: ApiClient,
          useValue: {
            compareProducts: compare,
            getDefaultWeights: vi.fn(() => of(FIXTURE_DEFAULTS)),
          },
        },
        { provide: SessionStore, useValue: { whenReady: () => Promise.resolve() } },
        { provide: CurrencyStore, useValue: { requestCurrency, recordMeta } },
        {
          provide: RegionStore,
          useValue: {
            intlLocale: signal('en-US'),
            countryCode: signal('TR'),
            countries: signal(FIXTURE_COUNTRIES),
          },
        },
      ],
    });
  }

  async function open(url: string) {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url, ComparePageComponent);
    await harness.fixture.whenStable();
    await harness.fixture.whenStable();
    return harness;
  }

  const host = (harness: RouterTestingHarness) => harness.routeNativeElement as HTMLElement;

  function problem(init: Partial<ApiProblemInit> & Pick<ApiProblemInit, 'status'>) {
    return () => throwError(() => new ApiProblem({ kind: 'problem', code: null, ...init }));
  }

  it('builds the request from the route, the URL query and the display currency', async () => {
    setup(() => of(goldenCompare()));
    requestCurrency.set('EUR');
    const harness = await open(
      `/compare/${GOLD_A_ID}/${GOLD_B_ID}?country=TR&stock=all&w=MINERAL:1.5`,
    );
    expect(compare).toHaveBeenCalledTimes(1);
    expect(compare.mock.calls[0][0]).toEqual({
      country: 'TR',
      baselineProductId: GOLD_A_ID,
      candidateProductId: GOLD_B_ID,
      stockFilter: 'INCLUDE_OUT_OF_STOCK',
      weights: { categories: { MINERAL: 1.5 } },
      displayCurrency: 'EUR',
    });
    expect(recordMeta).toHaveBeenCalledWith(goldenCompare().meta);

    const page = host(harness);
    expect(text(page.querySelector('h1'))).toBe('Is Gold Multi B worth TRY 1.67 more per day?');
    expect(text(page.querySelector('.eyebrow'))).toBe('Compare · Türkiye · including out of stock');
    expect(page.querySelector('[data-role="swapped"]')).toBeNull();
    expect(page.querySelector('vd-verdict-card vd-ratio-scale')).not.toBeNull();
    expect(page.querySelectorAll('vd-delta-table tbody tr[data-code]')).toHaveLength(5);
    expect(text(page)).toContain(EN.disclaimer.not_medical_advice);
    const back = page.querySelector('[data-role="back"]')?.getAttribute('href') ?? '';
    expect(back.startsWith('/results?')).toBe(true);
    expect(back).toContain('country=TR');
    expect(back).toContain('stock=all');
    expect(back).toContain('w=MINERAL');
    const productLink = page.querySelector('[data-side="A"] a.name')?.getAttribute('href');
    expect(productLink).toContain(`/products/${GOLD_A_ID}?`);
    expect(productLink).toContain('country=TR');

    requestCurrency.set('USD');
    await harness.fixture.whenStable();
    await harness.fixture.whenStable();
    expect(compare).toHaveBeenCalledTimes(2);
    expect(compare.mock.calls[1][0].displayCurrency).toBe('USD');
  });

  it('explains a swapped order and titles equal-cost verdicts as a choice', async () => {
    setup(() =>
      of(
        goldenCompare({
          swapped: true,
          verdict: 'DOMINATES',
          deltaCost: 0,
          marginalRate: null,
          ratio: null,
        }),
      ),
    );
    const page = host(await open(`/compare/${GOLD_B_ID}/${GOLD_A_ID}?country=TR`));
    expect(text(page.querySelector('[data-role="swapped"]'))).toBe(
      'Order switched You opened Gold Multi B first. A is always the product that costs less per ' +
        'day, so Gold Multi A is shown as A.',
    );
    expect(text(page.querySelector('h1'))).toBe('Gold Multi A or Gold Multi B?');
  });

  it.each([
    ['same_product', 'candidateProductId', compareEn.error.same_product],
    ['no_offer', 'baselineProductId', 'The first product has no offer in this country.'],
    [
      'no_stock',
      'candidateProductId',
      'The second product has no offer in stock. Include out-of-stock offers in your query to compare it.',
    ],
  ])('renders the %s validation error for the named product', async (code, field, body) => {
    setup(
      problem({
        status: 422,
        code: 'validation_failed',
        errors: [{ field, code, message: 'x' }],
      }),
    );
    const page = host(await open(`/compare/${GOLD_A_ID}/${GOLD_B_ID}?country=TR`));
    const error = page.querySelector('[data-role="error"]');
    expect(error?.getAttribute('role')).toBe('alert');
    expect(text(error?.querySelector('h2'))).toBe(compareEn.error.title);
    expect(text(error)).toContain(body);
    expect(error?.querySelector('[data-role="retry"]')).toBeNull();
    expect(text(error)).toContain(compareEn.back);
  });

  it.each([
    ['product_not_found', EN.problem.product_not_found],
    ['product_not_available_in_country', EN.problem.product_not_available_in_country],
  ] as const)('renders %s as a not-found state', async (code, texts) => {
    setup(problem({ status: 404, code }));
    const page = host(await open(`/compare/${GOLD_A_ID}/${GOLD_B_ID}?country=TR`));
    expect(text(page.querySelector('[data-role="error"] h2'))).toBe(texts.title);
    expect(text(page)).toContain(texts.body);
    expect(page.querySelector('[data-role="retry"]')).toBeNull();
  });

  it('offers a retry with the request id when scoring is unavailable', async () => {
    let calls = 0;
    setup(() => {
      calls += 1;
      return calls === 1
        ? throwError(
            () =>
              new ApiProblem({
                kind: 'problem',
                status: 503,
                code: 'scoring_unavailable',
                requestId: 'req-503',
              }),
          )
        : of(goldenCompare());
    });
    const harness = await open(`/compare/${GOLD_A_ID}/${GOLD_B_ID}?country=TR`);
    const page = host(harness);
    expect(text(page.querySelector('[data-role="error"] h2'))).toBe(
      EN.problem.scoring_unavailable.title,
    );
    expect(text(page)).toContain('Request ID req-503');
    page.querySelector<HTMLButtonElement>('[data-role="retry"]')?.click();
    await harness.fixture.whenStable();
    await harness.fixture.whenStable();
    expect(compare).toHaveBeenCalledTimes(2);
    expect(page.querySelector('vd-verdict-card')).not.toBeNull();
  });

  it('counts down Retry-After before the retry button enables itself', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    try {
      setup(problem({ status: 429, code: 'rate_limited', retryAfterSeconds: 2 }));
      const harness = await open(`/compare/${GOLD_A_ID}/${GOLD_B_ID}?country=TR`);
      const button = () => host(harness).querySelector<HTMLButtonElement>('[data-role="retry"]');
      expect(text(host(harness).querySelector('h2'))).toBe(EN.problem.rate_limited.title);
      expect(button()?.disabled).toBe(true);
      expect(text(button())).toBe('Try again in 2 s');
      vi.advanceTimersByTime(1000);
      await harness.fixture.whenStable();
      expect(text(button())).toBe('Try again in 1 s');
      vi.advanceTimersByTime(1000);
      await harness.fixture.whenStable();
      expect(button()?.disabled).toBe(false);
      expect(text(button())).toBe(EN.problem.retry);
    } finally {
      vi.useRealTimers();
    }
  });
});
