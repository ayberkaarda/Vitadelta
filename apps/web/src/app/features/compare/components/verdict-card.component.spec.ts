import { TestBed } from '@angular/core/testing';
import { provideTranslocoScope } from '@jsverse/transloco';

import compareEn from '../../../../../public/i18n/compare/en.json';
import compareTr from '../../../../../public/i18n/compare/tr.json';
import { CompareResponse, EngineParameters, Verdict } from '../../../core/api/api-types';
import { EN, translocoTesting } from '../../../testing/transloco-testing';
import { provideTestLocale } from '../../../testing/test-providers';
import {
  FIXTURE_DEFAULTS,
  goldenCompare,
  insufficientCompare,
} from '../../results/testing/golden.fixtures';
import { VerdictCardComponent } from './verdict-card.component';

const ENGINE = FIXTURE_DEFAULTS.engine as EngineParameters;

const text = (element: Element | null | undefined) =>
  element?.textContent?.replace(/\s+/g, ' ').trim() ?? '';

/** Synthetic responses for every verdict (fictional products, numbers as in the hand-off). */
function responseFor(verdict: Verdict): CompareResponse {
  switch (verdict) {
    case 'WORTH_IT':
      return goldenCompare({
        verdict,
        deltaCost: 1.5,
        deltaContent: 1.53,
        marginalRate: 0.9804,
        mRef: 2.77,
        ratio: 0.3539,
        meta: { ...goldenCompare().meta, marketSize: 9 },
        explanation: {
          key: 'explain.verdict.worth_it',
          params: {
            currency: 'TRY',
            deltaCostPerDay: 1.5,
            deltaContent: 1.53,
            marginalRate: 0.9804,
            mRef: 2.77,
            ratio: 0.3539,
          },
        },
      });
    case 'MARGINAL':
      return goldenCompare({
        verdict,
        deltaCost: 7.6667,
        deltaContent: 2.38,
        marginalRate: 3.2213,
        mRef: 2.77,
        ratio: 1.1629,
        meta: { ...goldenCompare().meta, marketSize: 9 },
        explanation: {
          key: 'explain.verdict.marginal',
          params: {
            currency: 'TRY',
            deltaCostPerDay: 7.6667,
            deltaContent: 2.38,
            marginalRate: 3.2213,
            mRef: 2.77,
            ratio: 1.1629,
          },
        },
      });
    case 'NOT_WORTH_IT':
      return goldenCompare();
    case 'DOMINATES':
      return goldenCompare({
        verdict,
        deltaCost: 0,
        deltaCostNative: 0,
        deltaContent: 1,
        marginalRate: null,
        ratio: null,
        explanation: {
          key: 'explain.verdict.dominates',
          params: { currency: 'TRY', deltaCostPerDay: 0, deltaContent: 1, mRef: 2.6817 },
        },
      });
    case 'EQUIVALENT':
      return goldenCompare({
        verdict,
        deltaCost: 0,
        deltaCostNative: 0,
        deltaContent: 0,
        marginalRate: null,
        ratio: null,
        explanation: { key: 'explain.verdict.equivalent', params: { currency: 'TRY' } },
      });
    case 'INSUFFICIENT_DATA':
      return insufficientCompare();
  }
}

describe('VerdictCardComponent', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [translocoTesting({ scopes: { compare: { en: compareEn, tr: compareTr } } })],
      providers: [provideTestLocale('en-US'), provideTranslocoScope('compare')],
    }),
  );

  async function render(response: CompareResponse, engine: EngineParameters | null = ENGINE) {
    const fixture = TestBed.createComponent(VerdictCardComponent);
    fixture.componentRef.setInput('response', response);
    fixture.componentRef.setInput('engine', engine);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  const metrics = (host: HTMLElement) =>
    Array.from(host.querySelectorAll('[data-metric]')).map((m) => m.getAttribute('data-metric'));

  it.each([
    ['WORTH_IT', 'v-worth'],
    ['MARGINAL', 'v-marginal'],
    ['NOT_WORTH_IT', 'v-notworth'],
    ['DOMINATES', 'v-dominates'],
    ['EQUIVALENT', 'v-equivalent'],
    ['INSUFFICIENT_DATA', 'v-insufficient'],
  ] as const)('renders %s with its label, tone and the indicative note', async (verdict, tone) => {
    const host = await render(responseFor(verdict));
    const card = host.querySelector('.verdict');
    expect(card?.getAttribute('data-verdict')).toBe(verdict);
    expect(card?.classList.contains(tone)).toBe(true);
    expect(text(host.querySelector('vd-verdict-badge'))).toBe(EN.verdict[verdict]);
    expect(text(host)).toContain(EN.verdict.caption);
    expect(text(host.querySelector('[data-note="indicative"]'))).toBe(EN.verdict.indicative_note);
    expect(text(host)).toContain(EN.disclaimer.not_medical_advice);
  });

  it('shows every metric, the scale and the fallback note for the golden pair', async () => {
    const host = await render(goldenCompare());
    expect(metrics(host)).toEqual(['extra_cost', 'extra_content', 'marginal_rate', 'ratio']);
    expect(text(host.querySelector('[data-metric="extra_cost"]'))).toBe(
      'Extra cost per day +TRY 1.67 15.00 − 13.33',
    );
    expect(text(host.querySelector('[data-metric="extra_content"]'))).toBe(
      'Extra content +0.0374 5.0093 − 4.9719',
    );
    expect(text(host.querySelector('[data-metric="marginal_rate"]'))).toContain(
      'TRY 44.58 market rate TRY 2.68',
    );
    expect(text(host.querySelector('[data-metric="ratio"]'))).toBe(
      'Ratio to market rate 16.6× worth it ≤ 1.0 · marginal ≤ 1.5',
    );
    expect(host.querySelector('vd-ratio-scale svg')?.getAttribute('role')).toBe('img');
    expect(text(host.querySelector('vd-ratio-scale'))).toContain('16.6× · off scale');
    expect(text(host.querySelector('[data-note="market_fallback"]'))).toBe(
      EN.verdict.market_fallback.replace('{{ marketSize }}', '2'),
    );
  });

  it('formats the explanation parameters (money as amounts) before interpolation', async () => {
    const host = await render(goldenCompare());
    expect(text(host.querySelector('[data-role="explanation"]'))).toBe(
      'B costs 1.67 TRY more per day for 0.0374 content points. That extra costs 44.58 TRY per ' +
        'point, 16.6× the market rate of 2.68 TRY: not worth it.',
    );
  });

  it('renders the explanation numbers bold through text nodes only', async () => {
    const host = await render(goldenCompare());
    const explanation = host.querySelector('[data-role="explanation"] vd-emphasized-text');
    expect(explanation?.textContent).toBe(
      'B costs 1.67 TRY more per day for 0.0374 content points. That extra costs 44.58 TRY per ' +
        'point, 16.6× the market rate of 2.68 TRY: not worth it.',
    );
    expect(Array.from(explanation?.querySelectorAll('strong') ?? [], (s) => s.textContent)).toEqual(
      ['1.67', 'TRY', '0.0374', '44.58', 'TRY', '16.6', '2.68', 'TRY'],
    );
    expect(explanation?.textContent).not.toMatch(/[]/);
  });

  it('renders INSUFFICIENT_DATA as a neutral dashed card without ratio or market rate', async () => {
    const host = await render(insufficientCompare());
    const card = host.querySelector('.verdict');
    expect(card?.classList.contains('dashed')).toBe(true);
    expect(metrics(host)).toEqual(['extra_cost', 'extra_content']);
    expect(host.querySelector('vd-ratio-scale')).toBeNull();
    expect(host.querySelector('[data-note="market_fallback"]')).toBeNull();
    expect(text(host)).not.toContain('market rate');
    expect(text(host.querySelector('[data-role="explanation"]'))).toBe(
      EN.explain.verdict.insufficient_data,
    );
  });

  it('drops ratio metrics and the scale for equal-cost verdicts', async () => {
    for (const verdict of ['DOMINATES', 'EQUIVALENT'] as const) {
      const host = await render(responseFor(verdict));
      expect(metrics(host)).toEqual(['extra_cost', 'extra_content']);
      expect(host.querySelector('vd-ratio-scale')).toBeNull();
      expect(text(host.querySelector('[data-metric="extra_cost"]'))).toContain('TRY 0.00');
    }
  });

  it('hides the scale and the threshold line when the engine parameters are unknown', async () => {
    const host = await render(goldenCompare(), null);
    expect(host.querySelector('vd-ratio-scale')).toBeNull();
    expect(text(host.querySelector('[data-metric="ratio"]'))).toBe('Ratio to market rate 16.6×');
  });

  it('places the marker inside the scale for a worth-it ratio', async () => {
    const host = await render(responseFor('WORTH_IT'));
    const marker = host.querySelector('vd-ratio-scale circle');
    expect(Number.parseFloat(marker?.getAttribute('cx') ?? '')).toBeCloseTo(11.8, 0);
    expect(text(host.querySelector('vd-ratio-scale'))).not.toContain('off scale');
    expect(host.querySelector('[data-note="market_fallback"]')).toBeNull();
  });
});
