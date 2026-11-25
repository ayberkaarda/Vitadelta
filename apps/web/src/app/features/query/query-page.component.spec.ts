import { Location } from '@angular/common';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { provideTranslocoScope } from '@jsverse/transloco';
import { of } from 'rxjs';

import queryEn from '../../../../public/i18n/query/en.json';
import queryTr from '../../../../public/i18n/query/tr.json';
import { ApiClient } from '../../core/api/api-client';
import { SessionStore } from '../../core/auth/session.store';
import { RegionStore } from '../../core/state/region.store';
import { FIXTURE_COUNTRIES } from '../../testing/api-fixtures';
import { translocoTesting } from '../../testing/transloco-testing';
import { FIELD_ERRORS_STATE } from '../results/shared/navigation-state';
import { FIXTURE_DEFAULTS, FIXTURE_NUTRIENTS } from '../results/testing/golden.fixtures';
import { QueryPageComponent } from './query-page.component';

function find<T extends Element>(root: ParentNode, selector: string): T {
  const element = root.querySelector<T>(selector);
  if (!element) {
    throw new Error(`Missing ${selector}`);
  }
  return element;
}

const text = (element: Element | null | undefined) =>
  element?.textContent?.replace(/\s+/g, ' ').trim() ?? '';

describe('QueryPageComponent', () => {
  let session: {
    status: ReturnType<typeof signal<string>>;
    anonymousQuota: ReturnType<typeof signal<unknown>>;
  };
  let setCountry: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    setCountry = vi.fn();
    session = {
      status: signal('anonymous'),
      anonymousQuota: signal({
        principal: 'ANONYMOUS',
        queriesUsed: 1,
        queriesLimit: 3,
        visibleResults: 3,
      }),
    };
    TestBed.configureTestingModule({
      imports: [translocoTesting({ scopes: { query: { en: queryEn, tr: queryTr } } })],
      providers: [
        provideRouter([
          { path: '', component: QueryPageComponent },
          { path: 'results', children: [] },
          { path: 'auth/register', children: [] },
        ]),
        provideTranslocoScope('query'),
        {
          provide: ApiClient,
          useValue: {
            listNutrients: vi.fn(() => of(FIXTURE_NUTRIENTS)),
            getDefaultWeights: vi.fn(() => of(FIXTURE_DEFAULTS)),
          },
        },
        { provide: SessionStore, useValue: session },
        {
          provide: RegionStore,
          useValue: {
            intlLocale: signal('en-US'),
            countryCode: signal('TR'),
            countries: signal(FIXTURE_COUNTRIES),
            setCountry,
          },
        },
      ],
    });
  });

  async function open(url = '/', state?: Record<string, unknown>) {
    const harness = await RouterTestingHarness.create();
    if (state) {
      await TestBed.inject(Router).navigateByUrl(url, { state });
      harness.detectChanges();
    } else {
      await harness.navigateByUrl(url, QueryPageComponent);
    }
    for (let i = 0; i < 3; i++) {
      await harness.fixture.whenStable();
    }
    return harness;
  }

  const host = (harness: RouterTestingHarness) => harness.routeNativeElement as HTMLElement;

  async function submit(harness: RouterTestingHarness): Promise<ReturnType<typeof vi.spyOn>> {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    host(harness).querySelector<HTMLButtonElement>('#q-submit')?.click();
    await harness.fixture.whenStable();
    return navigate;
  }

  it('makes the market chosen in the form the visitor country', async () => {
    const harness = await open('/');
    const select = host(harness).querySelector<HTMLSelectElement>('#q-country');
    if (!select) {
      throw new Error('country select missing');
    }
    select.value = 'DE';
    select.dispatchEvent(new Event('change'));
    harness.detectChanges();
    expect(setCountry).toHaveBeenCalledWith('DE');
    const navigate = await submit(harness);
    expect(navigate).toHaveBeenCalledWith(['/results'], { queryParams: { country: 'DE' } });
  });

  it('restores every control from the URL and submits the same query (round trip)', async () => {
    const url =
      '/?country=DE&stock=all&mode=CUSTOM&lambda=0.3&w=MINERAL:1.5,OTHER:0.5' +
      '&o=VIT_A:0,VIT_D:1.5&req=VIT_D:1000:IU,ZINC&ul=exclude';
    const harness = await open(url);
    const page = host(harness);

    expect(page.querySelector<HTMLSelectElement>('#q-country')?.value).toBe('DE');
    expect(setCountry).toHaveBeenCalledWith('DE');
    expect(page.querySelector<HTMLInputElement>('#q-stock-INCLUDE_OUT_OF_STOCK')?.checked).toBe(
      true,
    );
    expect(page.querySelector<HTMLInputElement>('#q-mode-CUSTOM')?.checked).toBe(true);
    const lambda = page.querySelector('#q-lambda');
    expect(lambda?.getAttribute('role')).toBe('slider');
    expect(lambda?.getAttribute('aria-valuenow')).toBe('0.3');
    expect(lambda?.getAttribute('aria-valuetext')).toBe('0.30');
    expect(page.querySelector('#q-w-MINERAL')?.getAttribute('aria-valuenow')).toBe('1.5');
    expect(page.querySelector('#q-w-VITAMIN')?.getAttribute('aria-valuenow')).toBe('1');
    expect(text(page.querySelector('#q-o-btn-VIT_A'))).toContain('×0.0');
    expect(text(page.querySelector('#q-o-btn-VIT_A'))).toContain('not scored');
    expect(page.querySelector<HTMLSelectElement>('#q-req-0-code')?.value).toBe('VIT_D');
    expect(page.querySelector<HTMLInputElement>('#q-req-0-min')?.value).toBe('1000');
    expect(page.querySelector<HTMLSelectElement>('#q-req-0-unit')?.value).toBe('IU');
    expect(page.querySelector<HTMLSelectElement>('#q-req-1-unit')?.disabled).toBe(true);
    expect(page.querySelector('#q-ul')?.getAttribute('aria-checked')).toBe('true');
    expect(text(page.querySelector('.summary'))).toContain('Custom · λ 0.30');
    expect(text(page.querySelector('.summary'))).toContain(
      'Mineral 1.5, Other 0.5, 2 nutrient overrides',
    );

    const navigate = await submit(harness);
    expect(navigate).toHaveBeenCalledWith(['/results'], {
      queryParams: {
        country: 'DE',
        stock: 'all',
        mode: 'CUSTOM',
        lambda: '0.3',
        w: 'MINERAL:1.5,OTHER:0.5',
        o: 'VIT_A:0,VIT_D:1.5',
        req: 'VIT_D:1000:IU,ZINC',
        ul: 'exclude',
      },
    });
  });

  it('serialises edits made with the controls', async () => {
    const harness = await open('/');
    const page = host(harness);
    expect(page.querySelector('#q-lambda')).toBeNull();

    page.querySelector<HTMLInputElement>('#q-mode-RICHNESS')?.click();
    page.querySelector<HTMLInputElement>('#q-stock-INCLUDE_OUT_OF_STOCK')?.click();
    page.querySelector<HTMLButtonElement>('#q-ul')?.click();
    await harness.fixture.whenStable();
    const vitamins = page.querySelector<HTMLElement>('#q-w-VITAMIN');
    vitamins?.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    await harness.fixture.whenStable();
    vitamins?.dispatchEvent(new KeyboardEvent('keydown', { key: 'PageUp', bubbles: true }));
    await harness.fixture.whenStable();
    page
      .querySelector<HTMLElement>('#q-w-OTHER')
      ?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Home', bubbles: true }));
    await harness.fixture.whenStable();
    expect(vitamins?.getAttribute('aria-valuenow')).toBe('1.6');

    page.querySelector<HTMLButtonElement>('#q-req-add')?.click();
    await harness.fixture.whenStable();
    const min = find<HTMLInputElement>(page, '#q-req-0-min');
    expect(page.querySelector<HTMLSelectElement>('#q-req-0-code')?.value).toBe('VIT_A');
    min.value = '700';
    min.dispatchEvent(new Event('input'));
    await harness.fixture.whenStable();
    const unit = find<HTMLSelectElement>(page, '#q-req-0-unit');
    expect(Array.from(unit.options).map((option) => option.value)).toEqual(['', 'mg', 'mcg', 'g']);
    unit.value = 'mcg';
    unit.dispatchEvent(new Event('change'));
    await harness.fixture.whenStable();

    const navigate = await submit(harness);
    expect(navigate).toHaveBeenCalledWith(['/results'], {
      queryParams: {
        country: 'TR',
        stock: 'all',
        mode: 'RICHNESS',
        w: 'VITAMIN:1.6,OTHER:0',
        req: 'VIT_A:700:mcg',
        ul: 'exclude',
      },
    });
  });

  it('validates required nutrients before navigating and lists the errors', async () => {
    const harness = await open('/?req=VIT_B12,MAGNESIUM');
    const page = host(harness);
    const setValue = (id: string, value: string) => {
      const input = find<HTMLInputElement>(page, id);
      input.value = value;
      input.dispatchEvent(new Event('input'));
    };
    setValue('#q-req-0-min', '0');
    await harness.fixture.whenStable();
    setValue('#q-req-1-min', '150');
    await harness.fixture.whenStable();

    const navigate = await submit(harness);
    expect(navigate).not.toHaveBeenCalled();
    const summary = page.querySelector('vd-error-summary [role="alert"]');
    expect(text(summary)).toContain('Please correct 2 field(s)');
    expect(text(summary)).toContain('Must be greater than 0.');
    expect(text(summary)).toContain('Choose a unit.');
    expect(document.activeElement).toBe(summary);
    expect(page.querySelector('#q-req-0-min')?.getAttribute('aria-invalid')).toBe('true');
    expect(page.querySelector('#q-req-0-min')?.getAttribute('aria-describedby')).toBe(
      'q-req-0-min-error',
    );
    expect(text(page.querySelector('#q-req-1-unit-error'))).toBe('Choose a unit.');

    setValue('#q-req-0-min', '2.5');
    await harness.fixture.whenStable();
    expect(page.querySelector('#q-req-0-min')?.getAttribute('aria-invalid')).toBeNull();
  });

  it('shows server field errors passed back from the results page', async () => {
    const harness = await open('/?o=VIT_C:0&req=ZINC:5:mg', {
      [FIELD_ERRORS_STATE]: [
        { field: 'weights', code: 'weights_all_zero', message: 'x' },
        { field: 'requiredNutrients[0].minAmountPerDay', code: 'out_of_range', message: 'x' },
      ],
    });
    expect(TestBed.inject(Location).path()).toContain('o=VIT_C:0');
    const page = host(harness);
    const summary = text(page.querySelector('vd-error-summary'));
    expect(summary).toContain('At least one nutrient must keep a weight above 0.');
    expect(summary).toContain('This value is outside the allowed range.');
    expect(text(page.querySelector('#q-weights-error'))).toContain(
      'At least one nutrient must keep a weight above 0.',
    );
    expect(page.querySelector('#q-req-0-min')?.getAttribute('aria-invalid')).toBe('true');
  });

  it('adds, edits and removes per-nutrient overrides', async () => {
    const harness = await open('/');
    const page = host(harness);
    page.querySelector<HTMLButtonElement>('#q-overrides-add')?.click();
    await harness.fixture.whenStable();
    const search = find<HTMLInputElement>(page, '#q-overrides-search');
    expect(document.activeElement).toBe(search);
    expect(search.getAttribute('role')).toBe('combobox');
    search.value = 'zinc';
    search.dispatchEvent(new Event('input'));
    await harness.fixture.whenStable();
    expect(page.querySelectorAll('[role="option"]')).toHaveLength(1);
    expect(search.getAttribute('aria-activedescendant')).toBe('q-overrides-opt-ZINC');
    search.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await harness.fixture.whenStable();

    const edit = find<HTMLInputElement>(page, '#q-o-ZINC');
    expect(edit.getAttribute('aria-label')).toBe('Weight for Zinc, 0 to 3');
    edit.value = '2.04';
    edit.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await harness.fixture.whenStable();
    expect(text(page.querySelector('#q-o-btn-ZINC'))).toContain('×2.0');

    page.querySelector<HTMLButtonElement>('[aria-label="Remove the override for Zinc"]')?.click();
    await harness.fixture.whenStable();
    expect(page.querySelector('#q-o-btn-ZINC')).toBeNull();
  });

  it('tells anonymous visitors how many free queries remain', async () => {
    const harness = await open('/');
    expect(text(host(harness).querySelector('[data-quota]'))).toBe(
      'This uses 1 of your 2 remaining free queries. Without an account you see the top 3 results of each query.',
    );
    session.anonymousQuota.set({
      principal: 'ANONYMOUS',
      queriesUsed: 3,
      queriesLimit: 3,
      visibleResults: 3,
    });
    await harness.fixture.whenStable();
    const quota = host(harness).querySelector('[data-quota]');
    expect(text(quota)).toContain('You have no free queries left.');
    expect(quota?.querySelector('a')?.getAttribute('href')).toBe('/auth/register');
  });
});
