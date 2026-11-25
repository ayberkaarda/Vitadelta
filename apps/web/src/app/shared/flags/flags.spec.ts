import { TestBed } from '@angular/core/testing';

import { translocoTesting } from '../../testing/transloco-testing';
import {
  DELTA_ROW_FLAGS,
  DeltaRowFlag,
  PRODUCT_FLAG_CODES,
  ProductFlag,
  ProductFlagCode,
} from '../../core/api/api-types';
import { FlagBadgeComponent } from './flag-badge.component';
import { FlagBadgesComponent } from './flag-badges.component';
import { DELTA_FLAG_STYLE, PRODUCT_FLAG_STYLE } from './flag-style';

async function renderBadge(
  code: ProductFlagCode | DeltaRowFlag,
  inputs: Record<string, unknown> = {},
) {
  const fixture = TestBed.createComponent(FlagBadgeComponent);
  fixture.componentRef.setInput('code', code);
  for (const [name, value] of Object.entries(inputs)) {
    fixture.componentRef.setInput(name, value);
  }
  await fixture.whenStable();
  return { fixture, host: fixture.nativeElement as HTMLElement };
}

describe('FlagBadgeComponent', () => {
  beforeEach(() => TestBed.configureTestingModule({ imports: [translocoTesting()] }));

  it.each(PRODUCT_FLAG_CODES)(
    'renders %s with an icon, a text label and its tone',
    async (code) => {
      const { host } = await renderBadge(code, {
        nutrients: ['ZINC', 'VIT_D'],
        currency: 'TRY',
        country: 'Türkiye',
      });
      const badge = host.querySelector('.badge');
      expect(badge?.getAttribute('data-code')).toBe(code);
      expect(badge?.querySelector('svg')).not.toBeNull();
      expect(badge?.textContent?.trim().length).toBeGreaterThan(3);
      const tone = PRODUCT_FLAG_STYLE[code].tone;
      expect(badge?.className).toContain(`t-${tone}`);
    },
  );

  it.each(DELTA_ROW_FLAGS)('renders delta row flag %s', async (code) => {
    const { host } = await renderBadge(code);
    const badge = host.querySelector('.badge');
    expect(badge?.querySelector('svg')).not.toBeNull();
    expect(badge?.className).toContain(`t-${DELTA_FLAG_STYLE[code].tone}`);
  });

  it('names the affected nutrients, currency and country in the label', async () => {
    expect(
      (await renderBadge('EXCEEDS_UL', { nutrients: ['ZINC'] })).host.textContent?.trim(),
    ).toBe('Above upper limit: ZINC');
    expect(
      (await renderBadge('FX_UNAVAILABLE', { currency: 'TRY' })).host.textContent?.trim(),
    ).toBe('Shown in TRY, no rate');
    expect(
      (
        await renderBadge('UNAVAILABLE_IN_COUNTRY', { country: 'Türkiye' })
      ).host.textContent?.trim(),
    ).toBe('Not sold in Türkiye');
    expect((await renderBadge('ABOVE_CEILING_NO_CREDIT')).host.textContent?.trim()).toBe(
      'Above ceiling, no credit',
    );
  });

  it('renders an interactive badge as a button that emits its code', async () => {
    const { fixture, host } = await renderBadge('EXCEEDS_UL', {
      nutrients: ['ZINC'],
      interactive: true,
    });
    const emitted: string[] = [];
    fixture.componentInstance.activate.subscribe((code) => emitted.push(code));
    const button = host.querySelector('button');
    expect(button?.getAttribute('type')).toBe('button');
    button?.click();
    expect(emitted).toEqual(['EXCEEDS_UL']);
  });
});

describe('FlagBadgesComponent', () => {
  beforeEach(() => TestBed.configureTestingModule({ imports: [translocoTesting()] }));

  const flags: ProductFlag[] = [
    { code: 'EXCEEDS_UL', nutrientCodes: ['ZINC'] },
    { code: 'MARKET_MEDIAN_REFERENCE_USED', nutrientCodes: ['COQ10'] },
    { code: 'PRESENCE_ONLY_NUTRIENTS', nutrientCodes: ['LUTEIN'] },
    { code: 'PRICE_STALE', nutrientCodes: [] },
    { code: 'FX_STALE', nutrientCodes: [] },
  ];

  async function render(inputs: Record<string, unknown>) {
    const fixture = TestBed.createComponent(FlagBadgesComponent);
    fixture.componentRef.setInput('flags', flags);
    for (const [name, value] of Object.entries(inputs)) {
      fixture.componentRef.setInput(name, value);
    }
    await fixture.whenStable();
    return { fixture, host: fixture.nativeElement as HTMLElement };
  }

  const codes = (host: HTMLElement) =>
    Array.from(host.querySelectorAll('.badge')).map((badge) => badge.getAttribute('data-code'));

  it('lists every flag in server order', async () => {
    const { host } = await render({});
    expect(codes(host)).toEqual(flags.map((flag) => flag.code));
    expect(host.querySelector('ul')?.getAttribute('role')).toBe('list');
  });

  it('omits flags shown elsewhere and collapses method notes', async () => {
    const { host } = await render({ omit: ['FX_STALE'], collapseMethodNotes: true });
    expect(codes(host)).toEqual(['EXCEEDS_UL', 'PRICE_STALE', 'METHOD_NOTES']);
    expect(host.textContent).toContain('2 method notes');
  });

  it('renders nothing without flags', async () => {
    const fixture = TestBed.createComponent(FlagBadgesComponent);
    fixture.componentRef.setInput('flags', []);
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).querySelector('ul')).toBeNull();
  });

  it('emits the flag of an interactive badge', async () => {
    const { fixture, host } = await render({ interactiveCodes: ['EXCEEDS_UL'] });
    const emitted: ProductFlag[] = [];
    fixture.componentInstance.activate.subscribe((flag) => emitted.push(flag));
    host.querySelector<HTMLButtonElement>('button.badge')?.click();
    expect(emitted).toEqual([flags[0]]);
  });
});
