import { TestBed } from '@angular/core/testing';

import { CoverageStripComponent } from '../results/shared/coverage-strip.component';
import { ceilingRatio, setLabel } from '../results/shared/reference-data.store';
import { formatPercent, formatPlain, formatQuantity } from '../../core/format/formatter';
import { FIXTURE_DEFAULTS } from '../results/testing/golden.fixtures';
import {
  ValueSliderComponent,
  snapToStep,
  sliderFraction,
} from './components/value-slider.component';
import { unitsFor } from './components/required-nutrients-editor.component';
import {
  controlIdForField,
  parseMinimum,
  readFieldErrors,
  rowsFromRequired,
  serverEntries,
  toRequired,
  validateRows,
} from './query-form';

describe('query form helpers', () => {
  it('maps server field paths to the controls that edit them', () => {
    expect(controlIdForField('requiredNutrients[2].minAmountPerDay', 'VALUE')).toBe('q-req-2-min');
    expect(controlIdForField('requiredNutrients[0].unit', 'VALUE')).toBe('q-req-0-unit');
    expect(controlIdForField('requiredNutrients[1]', 'VALUE')).toBe('q-req-1-code');
    expect(controlIdForField('requiredNutrients', 'VALUE')).toBe('q-req-add');
    expect(controlIdForField('weights.categories.MINERAL', 'VALUE')).toBe('q-w-MINERAL');
    expect(controlIdForField('weights.overrides.VIT_C', 'VALUE')).toBe('q-o-btn-VIT_C');
    expect(controlIdForField('weights', 'VALUE')).toBe('q-weights-reset');
    expect(controlIdForField('ranking.lambda', 'CUSTOM')).toBe('q-lambda');
    expect(controlIdForField('ranking.lambda', 'PRICE')).toBe('q-mode-PRICE');
    expect(controlIdForField('ranking.mode', 'VALUE')).toBe('q-mode-VALUE');
    expect(controlIdForField('country', 'VALUE')).toBe('q-country');
    expect(controlIdForField('displayCurrency', 'VALUE')).toBe('q-country');
    expect(controlIdForField('stockFilter', 'VALUE')).toBe('q-stock-IN_STOCK_ONLY');
    expect(controlIdForField('excludeExceedingUl', 'VALUE')).toBe('q-ul');
    expect(controlIdForField('pageSize', 'VALUE')).toBeNull();
  });

  it('keeps one summary entry per control and falls back to the submit button', () => {
    expect(
      serverEntries(
        [
          { field: 'weights', code: 'weights_all_zero', message: '' },
          { field: 'weights.overrides', code: 'weights_all_zero', message: '' },
          { field: 'pageSize', code: 'surprise', message: '' },
        ],
        'VALUE',
      ),
    ).toEqual([
      { controlId: 'q-weights-reset', messageKey: 'validation.weights_all_zero' },
      { controlId: 'q-submit', messageKey: 'validation.generic' },
    ]);
  });

  it('validates and converts required nutrient rows like the contract', () => {
    const rows = [
      { key: 1, code: 'VIT_D', min: '1000', unit: 'IU' as const },
      { key: 2, code: 'ZINC', min: '', unit: '' as const },
      { key: 3, code: 'VIT_C', min: '-1', unit: 'mg' as const },
      { key: 4, code: 'MAGNESIUM', min: '1,5', unit: '' as const },
      { key: 5, code: 'VIT_A', min: '2', unit: 'IU' as const },
    ];
    expect(validateRows(rows)).toEqual([
      { controlId: 'q-req-2-min', messageKey: 'query.errors.min_positive' },
      { controlId: 'q-req-3-unit', messageKey: 'query.errors.unit_required' },
      { controlId: 'q-req-4-unit', messageKey: 'query.errors.iu_only_vit_d' },
    ]);
    expect(toRequired(rows.slice(0, 2))).toEqual([
      { code: 'VIT_D', minAmountPerDay: 1000, unit: 'IU' },
      { code: 'ZINC' },
    ]);
    expect(parseMinimum(' 2,5 ')).toBe(2.5);
    expect(parseMinimum('')).toBeNull();
    expect(parseMinimum('abc')).toBeNaN();
    expect(unitsFor('VIT_D')).toContain('IU');
    expect(unitsFor('ZINC')).not.toContain('IU');
    const restored = rowsFromRequired([
      { code: 'VIT_D', minAmountPerDay: 25, unit: 'mcg' },
      { code: 'ZINC' },
    ]);
    expect(restored.map(({ code, min, unit }) => ({ code, min, unit }))).toEqual([
      { code: 'VIT_D', min: '25', unit: 'mcg' },
      { code: 'ZINC', min: '', unit: '' },
    ]);
  });

  it('reads field errors from navigation state defensively', () => {
    expect(readFieldErrors(null, 'k')).toEqual([]);
    expect(readFieldErrors({ k: 'nope' }, 'k')).toEqual([]);
    expect(
      readFieldErrors({ k: [{ field: 'a', code: 'b', message: 'c' }, { field: 1 }, null] }, 'k'),
    ).toEqual([{ field: 'a', code: 'b', message: 'c' }]);
  });
});

describe('ValueSliderComponent', () => {
  it('snaps to the step grid and clamps to the range', () => {
    expect(snapToStep(0.333, 0, 1, 0.05)).toBe(0.35);
    expect(snapToStep(7, 0, 3, 0.1)).toBe(3);
    expect(snapToStep(-1, 0, 2, 0.1)).toBe(0);
    expect(snapToStep(1.25, 0, 2, 0)).toBe(1.25);
    expect(sliderFraction(1.5, 0, 2)).toBe(0.75);
    expect(sliderFraction(1, 1, 1)).toBe(0);
  });

  it('follows the keyboard and the pointer', async () => {
    const fixture = TestBed.createComponent(ValueSliderComponent);
    const values: number[] = [];
    fixture.componentInstance.valueChange.subscribe((value) => values.push(value));
    fixture.componentRef.setInput('controlId', 's');
    fixture.componentRef.setInput('labelledBy', 'l');
    fixture.componentRef.setInput('value', 0.5);
    fixture.componentRef.setInput('min', 0);
    fixture.componentRef.setInput('max', 1);
    fixture.componentRef.setInput('step', 0.05);
    fixture.componentRef.setInput('valueText', '0.50');
    await fixture.whenStable();
    const thumb = fixture.nativeElement.querySelector('[role="slider"]') as HTMLElement;
    expect(thumb.getAttribute('aria-valuemin')).toBe('0');
    expect(thumb.getAttribute('aria-valuemax')).toBe('1');
    for (const key of ['ArrowLeft', 'ArrowUp', 'PageDown', 'End', 'Home', 'Tab']) {
      thumb.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true }));
    }
    expect(values).toEqual([0.45, 0.55, 0.25, 1, 0]);

    const rail = fixture.nativeElement.querySelector('.rail') as HTMLElement;
    rail.getBoundingClientRect = () => ({ left: 0, width: 200, top: 0, height: 44 }) as DOMRect;
    const pointer = (type: string, clientX: number) => {
      const event = new MouseEvent(type, { clientX, button: 0, bubbles: true });
      Object.defineProperty(event, 'pointerId', { value: 1 });
      rail.dispatchEvent(event);
    };
    pointer('pointerdown', 150);
    pointer('pointermove', 20);
    pointer('pointerup', 20);
    pointer('pointermove', 180);
    expect(values.slice(5)).toEqual([0.75, 0.1]);
    expect(document.activeElement).toBe(thumb);
  });
});

describe('CoverageStripComponent and formatting helpers', () => {
  it('draws credited fill, hatched excess and the ceiling line', async () => {
    const fixture = TestBed.createComponent(CoverageStripComponent);
    fixture.componentRef.setInput('bars', [
      { letter: 'A', ratio: 3.75, credited: 3.75, tone: 'alt' },
      { letter: 'B', ratio: 4.375, credited: 4.17, tone: 'fill' },
    ]);
    fixture.componentRef.setInput('ceiling', 4.17);
    fixture.componentRef.setInput('ul', 2);
    fixture.componentRef.setInput('refLabel', '100% (80 mg)');
    fixture.componentRef.setInput('ceilLabel', 'ceiling 417%');
    await fixture.whenStable();
    const svg = fixture.nativeElement.querySelector('svg') as SVGElement;
    expect(svg.getAttribute('aria-hidden')).toBe('true');
    expect(svg.querySelectorAll('rect.svg-fill')).toHaveLength(1);
    expect(svg.querySelectorAll('rect.svg-fill-alt')).toHaveLength(1);
    expect(svg.querySelectorAll('rect[fill^="url(#vd-hatch-"]')).toHaveLength(1);
    expect(svg.querySelector('line.ceil')).not.toBeNull();
    expect(svg.querySelector('line.ul')).not.toBeNull();
    expect(svg.textContent).toContain('ceiling 417%');
    expect(svg.textContent).toContain('A');
  });

  it('formats quantities, percents and set codes in the locale', () => {
    expect(formatQuantity('en-US', 1234.5, 'mg')).toBe('1,234.5 mg');
    expect(formatQuantity('tr-TR', 12.5, 'mcg')).toBe('12,5 mcg');
    expect(formatPercent('en-US', 4.375)).toBe('438%');
    expect(formatPercent('tr-TR', 1)).toBe('%100');
    expect(formatPlain('en-US', 1.234567, 2)).toBe('1.23');
    expect(setLabel('EU_NRV')).toBe('EU NRV');
    expect(ceilingRatio(FIXTURE_DEFAULTS.engine)).toBeCloseTo(4.17, 2);
    expect(ceilingRatio(null)).toBeNull();
  });
});
