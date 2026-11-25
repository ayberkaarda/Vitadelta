import { TestBed } from '@angular/core/testing';
import { provideTranslocoScope } from '@jsverse/transloco';

import compareEn from '../../../../../public/i18n/compare/en.json';
import compareTr from '../../../../../public/i18n/compare/tr.json';
import { DeltaRow, EngineParameters } from '../../../core/api/api-types';
import { EN, translocoTesting } from '../../../testing/transloco-testing';
import { provideTestLocale } from '../../../testing/test-providers';
import { FIXTURE_DEFAULTS, goldenCompare } from '../../results/testing/golden.fixtures';
import { DeltaTableComponent } from './delta-table.component';

const ENGINE = FIXTURE_DEFAULTS.engine as EngineParameters;

const text = (element: Element | null | undefined) =>
  element?.textContent?.replace(/\s+/g, ' ').trim() ?? '';

/** Synthetic rows with one nutrient only in B and one only in A. */
const EXTRA_ROWS: DeltaRow[] = [
  {
    code: 'COQ10',
    category: 'COENZYME',
    amountA: 0,
    amountB: 50,
    deltaAmount: 50,
    unit: 'mg',
    rA: null,
    rB: 1.6667,
    uA: 0,
    uB: 0.24,
    weight: 1,
    deltaContribution: 0.24,
    flags: ['NEW_IN_B'],
  },
  {
    code: 'SELENIUM',
    category: 'MINERAL',
    amountA: 55,
    amountB: 0,
    deltaAmount: -55,
    unit: 'mcg',
    rA: 1,
    rB: 0,
    uA: 0.2041,
    uB: 0,
    weight: 1,
    deltaContribution: -0.2041,
    flags: ['REMOVED_IN_B'],
  },
];

describe('DeltaTableComponent', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [translocoTesting({ scopes: { compare: { en: compareEn, tr: compareTr } } })],
      providers: [provideTestLocale('en-US'), provideTranslocoScope('compare')],
    }),
  );

  async function render(rows: readonly DeltaRow[], deltaContent = 0.0374) {
    const fixture = TestBed.createComponent(DeltaTableComponent);
    fixture.componentRef.setInput('rows', rows);
    fixture.componentRef.setInput('deltaContent', deltaContent);
    fixture.componentRef.setInput('engine', ENGINE);
    fixture.componentRef.setInput('reference', 'EU NRV');
    await fixture.whenStable();
    return { fixture, host: fixture.nativeElement as HTMLElement };
  }

  const row = (host: HTMLElement, code: string) =>
    host.querySelector<HTMLTableRowElement>(`tbody tr[data-code="${code}"]`);
  const cells = (tr: HTMLTableRowElement | null) =>
    Array.from(tr?.querySelectorAll('td') ?? []).map((cell) => text(cell));

  it('renders the golden rows in server order with amounts, coverage and utility', async () => {
    const { host } = await render(goldenCompare().rows);
    expect(
      Array.from(host.querySelectorAll('tbody tr[data-code]')).map((tr) =>
        tr.getAttribute('data-code'),
      ),
    ).toEqual(['MAGNESIUM', 'VIT_B12', 'VIT_C', 'VIT_D', 'ZINC']);
    const vitC = cells(row(host, 'VIT_C'));
    expect(text(row(host, 'VIT_C')?.querySelector('.ncode'))).toBe('VIT_C');
    expect(vitC[0]).toContain(EN.category.VITAMIN);
    expect(vitC.slice(1, 7)).toEqual([
      '300 mg',
      '350 mg',
      '+50 mg',
      '375% → 438%',
      '1.4626 → 1.5000',
      '1.0',
    ]);
    expect(text(host.querySelector('caption'))).toBe(compareEn.table.sr_caption);
    expect(text(host)).toContain('coverage against EU NRV');
    expect(host.querySelectorAll('th[scope="col"]')).toHaveLength(9);
  });

  it('renders the row flags as badges and leaves unchanged rows without flags', async () => {
    const { host } = await render(goldenCompare().rows);
    const badges = Array.from(row(host, 'VIT_C')?.querySelectorAll('[data-code]') ?? []);
    expect(badges.map((badge) => badge.getAttribute('data-code'))).toEqual([
      'DIMINISHING',
      'ABOVE_CEILING_NO_CREDIT',
    ]);
    expect(text(row(host, 'VIT_C'))).toContain(EN.delta.flag.DIMINISHING);
    expect(text(row(host, 'VIT_C'))).toContain(EN.delta.flag.ABOVE_CEILING_NO_CREDIT);
    const zinc = row(host, 'ZINC');
    expect(zinc?.classList.contains('unchanged')).toBe(true);
    expect(zinc?.querySelector('vd-flag-badge')).toBeNull();
    expect(zinc?.querySelector('button')).toBeNull();
    expect(cells(zinc)[3]).toBe('0');
  });

  it('expands a flagged row into the coverage strip and the flag reading', async () => {
    const { fixture, host } = await render(goldenCompare().rows);
    const button = row(host, 'VIT_C')?.querySelector<HTMLButtonElement>('button');
    expect(button?.getAttribute('aria-expanded')).toBe('false');
    expect(button?.getAttribute('aria-label')).toBe('Details for VIT_C');
    button?.click();
    await fixture.whenStable();
    expect(button?.getAttribute('aria-expanded')).toBe('true');
    const detail = host.querySelector(`#${button?.getAttribute('aria-controls')}`);
    expect(detail).not.toBeNull();
    expect(detail?.querySelector('vd-coverage-strip')).not.toBeNull();
    expect(text(detail)).toContain('ceiling 417%');
    // GT-1: reference 300 mg / 3.75 = 80 mg, ceiling 417 % = 333.6 mg, so 34 mg of the extra
    // 50 mg still count and 16 mg land above the ceiling.
    expect(text(detail?.querySelector('[data-role="reading"]'))).toBe(
      `${compareEn.flag_reading.DIMINISHING} ` +
        'The first 34 mg of the extra 50 mg still adds a little; the last 16 mg lands above the ' +
        '417% ceiling and earns nothing.',
    );
    button?.click();
    await fixture.whenStable();
    expect(host.querySelector('tr.detail')).toBeNull();
  });

  it('shows "—" for the absent side and signs losses with a real minus', async () => {
    const { host } = await render(EXTRA_ROWS, 0.0359);
    const coq = cells(row(host, 'COQ10'));
    expect(coq[1]).toBe(EN.common.none);
    expect(coq[4]).toBe(`${EN.common.none} → 167%`);
    const selenium = cells(row(host, 'SELENIUM'));
    expect(selenium[2]).toBe(EN.common.none);
    expect(selenium[3]).toBe('−55 mcg');
    expect(text(row(host, 'SELENIUM'))).toContain(EN.delta.flag.REMOVED_IN_B);
  });

  it('shows the server total in the footer and the unchanged-rows toggle', async () => {
    const { fixture, host } = await render(goldenCompare().rows);
    expect(cells(host.querySelector<HTMLTableRowElement>('tfoot tr')).slice(0, 2)).toEqual([
      compareEn.table.total,
      '+0.0374',
    ]);
    const toggle = host.querySelector<HTMLButtonElement>('button.mobile-only');
    expect(text(toggle)).toBe('Show 4 unchanged nutrients');
    toggle?.click();
    await fixture.whenStable();
    expect(toggle?.getAttribute('aria-expanded')).toBe('true');
    expect(host.querySelector('table')?.classList.contains('show-unchanged')).toBe(true);
    expect(text(toggle)).toBe(compareEn.table.hide_unchanged);
  });
});
