import { TestBed } from '@angular/core/testing';

import { provideTestLocale } from '../../testing/test-providers';
import { translocoTesting } from '../../testing/transloco-testing';
import { MINUS } from '../../core/format/formatter';
import { DeltaBarComponent } from './delta-bar.component';
import { ScoreBarComponent } from './score-bar.component';
import { UsageMeterComponent } from './usage-meter.component';

const SVG_NS = 'http://www.w3.org/2000/svg';

function setup() {
  TestBed.configureTestingModule({
    imports: [translocoTesting()],
    providers: [provideTestLocale()],
  });
}

async function renderScoreBar(display: number, raw: number, active: boolean, kind = 'content') {
  const fixture = TestBed.createComponent(ScoreBarComponent);
  fixture.componentRef.setInput('kind', kind);
  fixture.componentRef.setInput('display', display);
  fixture.componentRef.setInput('raw', raw);
  fixture.componentRef.setInput('active', active);
  await fixture.whenStable();
  return fixture.nativeElement as HTMLElement;
}

describe('ScoreBarComponent', () => {
  beforeEach(setup);

  it('draws the fill at the display width in the active colour', async () => {
    const host = await renderScoreBar(68.4, 5.0093, true);
    const rects = host.querySelectorAll('rect');
    expect(rects).toHaveLength(2);
    expect(rects[0].namespaceURI).toBe(SVG_NS);
    expect(rects[0].getAttribute('class')).toBe('svg-track');
    expect(rects[0].getAttribute('width')).toBe('100%');
    expect(rects[1].getAttribute('class')).toBe('svg-fill');
    expect(rects[1].getAttribute('width')).toBe('68.4%');
    expect(rects[1].getAttribute('height')).toBe('8');
    expect(rects[1].getAttribute('rx')).toBe('4');
    expect(host.querySelector('svg')?.getAttribute('aria-hidden')).toBe('true');
    expect(host.querySelector('.v')?.textContent?.trim()).toBe('68');
    expect(host.querySelector('.raw')?.textContent?.trim()).toBe('5.0093');
    expect(host.querySelector('.sr-only')?.textContent?.trim()).toBe(
      'Content score 68 of 100, raw 5.0093',
    );
  });

  it('uses the alternative fill for the inactive criterion', async () => {
    const host = await renderScoreBar(53, 0.334, false, 'value');
    expect(host.querySelectorAll('rect')[1].getAttribute('class')).toBe('svg-fill-alt');
    expect(host.querySelector('.sr-only')?.textContent?.trim()).toBe(
      'Value score 53 of 100, raw 0.3340',
    );
  });

  it('keeps a minimum visible width and draws only the track at 0', async () => {
    const small = await renderScoreBar(1, 0.0812, false);
    expect(small.querySelectorAll('rect')[1].getAttribute('width')).toBe('2%');
    const zero = await renderScoreBar(0, 0, false);
    expect(zero.querySelectorAll('rect')).toHaveLength(1);
    expect(zero.querySelector('.v')?.textContent?.trim()).toBe('0');
  });
});

describe('DeltaBarComponent', () => {
  beforeEach(setup);

  async function render(delta: number, maxAbs: number) {
    const fixture = TestBed.createComponent(DeltaBarComponent);
    fixture.componentRef.setInput('delta', delta);
    fixture.componentRef.setInput('maxAbs', maxAbs);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('draws a gain right of the zero axis with a signed value', async () => {
    const host = await render(0.0374, 0.0374);
    const rect = host.querySelector('rect');
    expect(rect?.getAttribute('class')).toBe('svg-gain');
    expect(rect?.getAttribute('x')).toBe('50%');
    expect(rect?.getAttribute('width')).toBe('46%');
    expect(host.querySelector('line')?.getAttribute('x1')).toBe('50%');
    expect(host.querySelector('.v')?.textContent?.trim()).toBe('+0.0374');
  });

  it('draws a loss left of the axis with a real minus sign', async () => {
    const host = await render(-0.2667, 0.2667);
    const rect = host.querySelector('rect');
    expect(rect?.getAttribute('class')).toBe('svg-loss');
    expect(rect?.getAttribute('x')).toBe('4%');
    expect(host.querySelector('.v')?.textContent?.trim()).toBe(`${MINUS}0.2667`);
  });

  it('draws a dot and a muted zero for no change', async () => {
    const host = await render(0, 0.2);
    expect(host.querySelector('rect')).toBeNull();
    expect(host.querySelector('circle')?.getAttribute('r')).toBe('2.5');
    expect(host.querySelector('.v')?.classList.contains('zero')).toBe(true);
    expect(host.querySelector('.sr-only')?.textContent?.trim()).toBe('Change in content score 0');
  });
});

describe('UsageMeterComponent', () => {
  beforeEach(setup);

  async function render(used: number, limit: number | null) {
    const fixture = TestBed.createComponent(UsageMeterComponent);
    fixture.componentRef.setInput('label', 'Saved products');
    fixture.componentRef.setInput('used', used);
    fixture.componentRef.setInput('limit', limit);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('shows "3 of 5" with a proportional bar', async () => {
    const host = await render(3, 5);
    const meter = host.querySelector('[role="meter"]');
    expect(meter?.getAttribute('aria-valuenow')).toBe('3');
    expect(meter?.getAttribute('aria-valuemax')).toBe('5');
    expect(meter?.getAttribute('aria-label')).toBe('Saved products');
    expect(host.querySelector('.n')?.textContent?.trim()).toBe('3 of 5');
    expect(host.querySelectorAll('rect')[1].getAttribute('width')).toBe('60%');
    expect(host.querySelectorAll('rect')[1].getAttribute('class')).toBe('svg-fill');
  });

  it('switches to the warning tone and text at the limit', async () => {
    const host = await render(5, 5);
    expect(host.querySelector('.n')?.textContent?.trim()).toBe('5 of 5 · limit reached');
    expect(host.querySelectorAll('rect')[1].getAttribute('class')).toBe('svg-warn');
  });

  it('uses the "used / limit" form in Turkish', async () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [translocoTesting({ lang: 'tr' })],
      providers: [provideTestLocale('tr-TR')],
    });
    expect((await render(1, 5)).querySelector('.n')?.textContent?.trim()).toBe('1 / 5');
    expect((await render(5, 5)).querySelector('.n')?.textContent?.trim()).toBe(
      '5 / 5 · sınıra ulaşıldı',
    );
  });

  it('draws only the track for unlimited plans', async () => {
    const host = await render(12, null);
    expect(host.querySelector('.n')?.textContent?.trim()).toBe('12 · unlimited');
    expect(host.querySelectorAll('rect')).toHaveLength(1);
  });
});
