import { TestBed } from '@angular/core/testing';

import productEn from '../../../../../public/i18n/product/en.json';
import productTr from '../../../../../public/i18n/product/tr.json';
import { ProductNutrient } from '../../../core/api/api-types';
import { provideTestLocale } from '../../../testing/test-providers';
import { translocoTesting } from '../../../testing/transloco-testing';
import { ReferenceDataStore } from '../../results/shared/reference-data.store';
import { PRODUCT_NUTRIENTS } from '../testing/product.fixtures';
import { FormulationTableComponent } from './formulation-table.component';

// Synthetic formulation rows. Percentages and `exceeded` deliberately differ from what the
// amounts would give, so a test fails if the component computes them instead of rendering them.
function nutrient(
  code: string,
  amountPerDay: number,
  unit: 'mg' | 'mcg',
  extra: Partial<ProductNutrient> = {},
): ProductNutrient {
  return {
    code,
    category: code === 'ZINC' || code === 'MAGNESIUM' ? 'MINERAL' : 'VITAMIN',
    amountPerServing: amountPerDay,
    amountPerDay,
    unit,
    ulAmountPerServing: null,
    formCode: 'UNKNOWN',
    formLabel: null,
    conversionAssumed: false,
    ...extra,
  };
}

const ZINC_OVER = nutrient('ZINC', 40, 'mg', {
  reference: {
    amount: 10,
    unit: 'mg',
    setCode: 'EU_NRV',
    source: 'REGULATORY',
    percentOfReference: 400,
  },
  upperLimit: {
    amount: 25,
    unit: 'mg',
    basis: 'TOTAL',
    setCode: 'EFSA',
    formCode: 'ANY',
    note: 'Synthetic fixture note: adult tolerable upper intake level.',
    ulAmountPerDay: 40,
    exceeded: true,
  },
});

const MAGNESIUM_SUPPLEMENTAL = nutrient('MAGNESIUM', 1234.5, 'mg', {
  reference: {
    amount: 375,
    unit: 'mg',
    setCode: 'EU_NRV',
    source: 'REGULATORY',
    // Not 1234.5 / 375 * 100: the server value must be shown as it is.
    percentOfReference: 7.25,
  },
  upperLimit: {
    amount: 250,
    unit: 'mg',
    basis: 'SUPPLEMENTAL',
    setCode: 'EFSA',
    formCode: 'ANY',
    note: null,
    // Only part of the amount counts toward the limit; the server says it is not exceeded.
    ulAmountPerDay: 120.5,
    exceeded: false,
  },
});

const VIT_D_EQUAL = nutrient('VIT_D', 100, 'mcg', {
  reference: {
    amount: 5,
    unit: 'mcg',
    setCode: 'EU_NRV',
    source: 'REGULATORY',
    percentOfReference: 2000,
  },
  upperLimit: {
    amount: 100,
    unit: 'mcg',
    basis: 'TOTAL',
    setCode: 'EFSA',
    formCode: 'ANY',
    note: '   ',
    ulAmountPerDay: 100,
    // Equal rounded values, but the server compared the unrounded ones.
    exceeded: true,
  },
});

const VIT_C_NULLS = nutrient('VIT_C', 300, 'mg', { reference: null, upperLimit: null });
const VIT_B12_ABSENT = nutrient('VIT_B12', 2.5, 'mcg');

async function render(
  rows: readonly ProductNutrient[],
  options: { lang?: 'en' | 'tr'; locale?: string; highlight?: readonly string[] } = {},
) {
  TestBed.configureTestingModule({
    imports: [
      translocoTesting({
        lang: options.lang ?? 'en',
        scopes: { product: { en: productEn, tr: productTr } },
      }),
    ],
    providers: [
      provideTestLocale(options.locale ?? 'en-US'),
      {
        provide: ReferenceDataStore,
        useValue: {
          nutrient: (code: string) =>
            PRODUCT_NUTRIENTS.items.find((entry) => entry.code === code) ?? null,
        },
      },
    ],
  });
  const fixture = TestBed.createComponent(FormulationTableComponent);
  fixture.componentRef.setInput('nutrients', rows);
  fixture.componentRef.setInput('productName', 'Gold Multi A');
  fixture.componentRef.setInput('highlight', options.highlight ?? []);
  await fixture.whenStable();
  const host = fixture.nativeElement as HTMLElement;
  const row = (code: string) => host.querySelector(`tr[data-code="${code}"]`);
  const cell = (code: string, col: 'reference' | 'upper-limit') =>
    row(code)?.querySelector(`[data-col="${col}"]`) ?? null;
  return { fixture, host, row, cell };
}

function text(element: Element | null | undefined): string {
  return (element?.textContent ?? '').replace(/\s+/g, ' ').trim();
}

describe('FormulationTableComponent', () => {
  it('renders the reference share and the upper limit from the server facts', async () => {
    const { host, cell } = await render([ZINC_OVER, MAGNESIUM_SUPPLEMENTAL]);
    const headers = Array.from(host.querySelectorAll('th')).map((th) => text(th));
    expect(headers.slice(4)).toEqual(['Of reference', 'Upper limit']);

    expect(text(cell('ZINC', 'reference')?.querySelector('[data-part="percent"]'))).toBe('400%');
    expect(text(cell('ZINC', 'reference')?.querySelector('[data-part="reference"]'))).toBe(
      'of 10 mg · EU NRV',
    );
    expect(text(cell('ZINC', 'upper-limit')?.querySelector('[data-part="limit"]'))).toBe('25 mg');
    expect(text(cell('ZINC', 'upper-limit')?.querySelector('[data-part="basis"]'))).toBe(
      'total intake · EFSA',
    );
    expect(text(cell('MAGNESIUM', 'upper-limit')?.querySelector('[data-part="basis"]'))).toBe(
      'supplements only · EFSA',
    );
  });

  it('never recomputes percentages, UL-relevant amounts or the exceeded decision', async () => {
    const { cell, row } = await render([MAGNESIUM_SUPPLEMENTAL, VIT_D_EQUAL]);
    // 1234.5 mg against 375 mg would be 329 %; the server said 7.25.
    expect(text(cell('MAGNESIUM', 'reference')?.querySelector('[data-part="percent"]'))).toBe(
      '7.3%',
    );
    // 1234.5 mg per day is above 250 mg, yet the server's UL-relevant amount is not.
    expect(cell('MAGNESIUM', 'upper-limit')?.querySelector('[data-marker="exceeded"]')).toBeNull();
    expect(row('MAGNESIUM')?.classList.contains('is-hl')).toBe(false);
    expect(text(cell('MAGNESIUM', 'upper-limit')?.querySelector('[data-part="ul-amount"]'))).toBe(
      'Counts toward the limit: 120.5 mg',
    );
    // Equal displayed values, exceeded because the server says so.
    expect(cell('VIT_D', 'upper-limit')?.querySelector('[data-marker="exceeded"]')).not.toBeNull();
    expect(cell('VIT_D', 'upper-limit')?.querySelector('[data-part="ul-amount"]')).toBeNull();
    expect(text(cell('VIT_D', 'reference')?.querySelector('[data-part="percent"]'))).toBe('2,000%');
  });

  it('marks an exceeded limit with an icon and words, not colour alone', async () => {
    const { row, cell } = await render([ZINC_OVER]);
    const marker = cell('ZINC', 'upper-limit')?.querySelector('[data-marker="exceeded"]');
    expect(text(marker)).toContain('exceeded');
    expect(marker?.querySelector('vd-icon')).not.toBeNull();
    expect(row('ZINC')?.classList.contains('is-hl')).toBe(true);
    expect(text(row('ZINC')?.querySelector('.warn'))).toBe('Above the upper limit');
    expect(row('ZINC')?.querySelector('td.r.strong')).not.toBeNull();
  });

  it('shows the empty markers for null and absent facts', async () => {
    const { cell } = await render([VIT_C_NULLS, VIT_B12_ABSENT]);
    for (const code of ['VIT_C', 'VIT_B12']) {
      expect(text(cell(code, 'reference'))).toBe('no reference');
      expect(cell(code, 'reference')?.querySelector('[data-empty="reference"]')).not.toBeNull();
      expect(text(cell(code, 'upper-limit'))).toBe('none set');
      expect(cell(code, 'upper-limit')?.querySelector('[data-empty="upper-limit"]')).not.toBeNull();
    }
  });

  it('opens the citation note as a keyboard-reachable disclosure', async () => {
    const { fixture, cell } = await render([ZINC_OVER, VIT_D_EQUAL]);
    const button = cell('ZINC', 'upper-limit')?.querySelector<HTMLButtonElement>('button');
    expect(button?.type).toBe('button');
    expect(text(button)).toBe('Source');
    // The accessible name starts with the visible label (WCAG 2.5.3).
    expect(button?.getAttribute('aria-label')).toBe('Source of the upper limit for ZINC');
    expect(button?.getAttribute('aria-expanded')).toBe('false');
    const note = document.getElementById(button?.getAttribute('aria-controls') ?? '');
    expect(note).not.toBeNull();
    expect(note?.hidden).toBe(true);
    expect(text(note)).toBe('Synthetic fixture note: adult tolerable upper intake level.');

    button?.click();
    await fixture.whenStable();
    expect(button?.getAttribute('aria-expanded')).toBe('true');
    expect(note?.hidden).toBe(false);
    button?.click();
    await fixture.whenStable();
    expect(note?.hidden).toBe(true);

    // A blank note has no disclosure.
    expect(cell('VIT_D', 'upper-limit')?.querySelector('button')).toBeNull();
  });

  it('localises numbers, percentages and labels in Turkish', async () => {
    const { host, cell } = await render([ZINC_OVER, MAGNESIUM_SUPPLEMENTAL, VIT_C_NULLS], {
      lang: 'tr',
      locale: 'tr-TR',
    });
    const headers = Array.from(host.querySelectorAll('th')).map((th) => text(th));
    expect(headers.slice(4)).toEqual(['Referansa göre', 'Üst sınır']);
    expect(text(cell('ZINC', 'reference')?.querySelector('[data-part="percent"]'))).toBe('%400');
    expect(text(cell('ZINC', 'reference')?.querySelector('[data-part="reference"]'))).toBe(
      'referans 10 mg · EU NRV',
    );
    expect(text(cell('ZINC', 'upper-limit')?.querySelector('[data-marker="exceeded"]'))).toContain(
      'aşıldı',
    );
    expect(text(cell('MAGNESIUM', 'reference')?.querySelector('[data-part="percent"]'))).toBe(
      '%7,3',
    );
    expect(text(cell('MAGNESIUM', 'upper-limit')?.querySelector('[data-part="basis"]'))).toBe(
      'yalnızca takviye · EFSA',
    );
    expect(text(cell('MAGNESIUM', 'upper-limit')?.querySelector('[data-part="ul-amount"]'))).toBe(
      'Sınıra sayılan: 120,5 mg',
    );
    expect(text(host.querySelector('tr[data-code="MAGNESIUM"] td:nth-child(4)'))).toBe(
      '1.234,5 mg',
    );
    expect(text(cell('VIT_C', 'reference'))).toBe('referans yok');
    expect(text(cell('VIT_C', 'upper-limit'))).toBe('belirlenmemiş');
  });

  it('keeps the scroll container focusable and labelled for narrow screens', async () => {
    const { host } = await render([ZINC_OVER]);
    const wrap = host.querySelector('.tbl-wrap');
    expect(wrap?.getAttribute('role')).toBe('region');
    expect(wrap?.getAttribute('tabindex')).toBe('0');
    expect(wrap?.getAttribute('aria-label')).toBe('Formulation of Gold Multi A');
  });

  it('highlights the rows a flag link points to', async () => {
    const { row } = await render([VIT_C_NULLS], { highlight: ['VIT_C'] });
    expect(row('VIT_C')?.classList.contains('is-hl')).toBe(true);
  });
});
