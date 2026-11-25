import { TestBed } from '@angular/core/testing';

import { FX_IDENTITY_TRY, FX_STALE_EUR, FX_UNAVAILABLE_GBP } from '../../testing/api-fixtures';
import { provideTestLocale } from '../../testing/test-providers';
import { EN, translocoTesting } from '../../testing/transloco-testing';
import { MoneyContext } from '../../core/state/currency.store';
import { FxNoticeComponent } from './fx-notice.component';
import { MoneyComponent } from './money.component';

function setup(locale = 'en-US') {
  TestBed.configureTestingModule({
    imports: [translocoTesting()],
    providers: [provideTestLocale(locale)],
  });
}

async function renderMoney(inputs: Record<string, unknown>) {
  const fixture = TestBed.createComponent(MoneyComponent);
  for (const [name, value] of Object.entries(inputs)) {
    fixture.componentRef.setInput(name, value);
  }
  await fixture.whenStable();
  return { fixture, host: fixture.nativeElement as HTMLElement };
}

const normalise = (value: string | null | undefined) => (value ?? '').replace(/\s+/g, ' ').trim();

describe('MoneyComponent', () => {
  it('formats the native amount with the price and days supply sub-line', async () => {
    setup();
    const { host } = await renderMoney({
      amount: 13.3333,
      currency: 'TRY',
      nativeAmount: 13.3333,
      nativeCurrency: 'TRY',
      fx: FX_IDENTITY_TRY,
      price: 800,
      daysSupply: 60,
      retailer: 'Demo Store',
      checkedAt: '2026-11-20T09:00:00Z',
    });
    expect(normalise(host.querySelector('.amount')?.textContent)).toBe('TRY 13.33');
    expect(normalise(host.querySelector('.sub')?.textContent)).toBe('800.00 · 60 days');

    const tip = host.querySelector('[role="tooltip"]');
    expect(tip?.hasAttribute('hidden')).toBe(true);
    const text = normalise(tip?.textContent);
    expect(text).toContain('Price TRY 800.00');
    expect(text).toContain('Retailer Demo Store');
    expect(text).toContain('Demo price, checked Nov 20, 2026');
    expect(text).not.toContain('ECB');
  });

  it('formats in the active locale', async () => {
    setup('tr-TR');
    const { host } = await renderMoney({ amount: 1234.5, currency: 'TRY' });
    expect(normalise(host.querySelector('.amount')?.textContent)).toBe('₺1.234,50');
    expect(host.querySelector('.sub')).toBeNull();
  });

  it('shows the native amount and the ECB rate with its date when converted', async () => {
    setup();
    const { host } = await renderMoney({
      amount: 0.3,
      currency: 'EUR',
      nativeAmount: 13.3333,
      nativeCurrency: 'TRY',
      fx: FX_STALE_EUR,
      price: 18,
      daysSupply: 60,
    });
    expect(normalise(host.querySelector('.amount')?.textContent)).toBe('€0.30');
    expect(normalise(host.querySelector('.sub')?.textContent)).toBe('TRY 13.33');
    const text = normalise(host.querySelector('[role="tooltip"]')?.textContent);
    expect(text).toContain('Native amount TRY 13.33');
    expect(text).toContain('1 TRY = 0.02250000 EUR, ECB rate of Nov 17, 2026');
    expect(text).toContain(EN.money.tooltip.stale);
  });

  it('explains an unavailable rate', async () => {
    setup();
    const { host } = await renderMoney({
      amount: 13.3333,
      currency: 'TRY',
      nativeAmount: 13.3333,
      nativeCurrency: 'TRY',
      fx: FX_UNAVAILABLE_GBP,
    });
    const text = normalise(host.querySelector('[role="tooltip"]')?.textContent);
    expect(text).toContain('No exchange rate is available, so the amount stays in TRY.');
  });

  it('opens the tooltip on focus and hover, closes it on blur, leave and Escape', async () => {
    setup();
    const { fixture, host } = await renderMoney({ amount: 1, currency: 'TRY', price: 1 });
    const button = host.querySelector('button');
    const tip = host.querySelector<HTMLElement>('[role="tooltip"]');
    expect(button?.getAttribute('aria-describedby')).toBe(tip?.id);

    button?.dispatchEvent(new Event('focus'));
    await fixture.whenStable();
    expect(tip?.hidden).toBe(false);
    button?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await fixture.whenStable();
    expect(tip?.hidden).toBe(true);

    button?.dispatchEvent(new Event('mouseenter'));
    await fixture.whenStable();
    expect(tip?.hidden).toBe(false);
    button?.dispatchEvent(new Event('mouseleave'));
    await fixture.whenStable();
    expect(tip?.hidden).toBe(true);

    button?.dispatchEvent(new Event('focus'));
    button?.dispatchEvent(new Event('blur'));
    await fixture.whenStable();
    expect(tip?.hidden).toBe(true);
  });
});

describe('FxNoticeComponent', () => {
  beforeEach(() => setup());

  async function render(meta: MoneyContext) {
    const fixture = TestBed.createComponent(FxNoticeComponent);
    fixture.componentRef.setInput('meta', meta);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('explains that amounts stay native when no rate exists', async () => {
    const host = await render({ nativeCurrency: 'TRY', currency: 'TRY', fx: FX_UNAVAILABLE_GBP });
    const notice = host.querySelector('[data-state="unavailable"]');
    expect(notice?.getAttribute('role')).toBe('status');
    expect(normalise(notice?.textContent)).toContain('Prices are shown in TRY');
    expect(normalise(notice?.textContent)).toContain('TRY → GBP');
  });

  it('shows the stale badge with rate and date', async () => {
    const host = await render({ nativeCurrency: 'TRY', currency: 'EUR', fx: FX_STALE_EUR });
    const text = normalise(host.querySelector('[data-state="stale"]')?.textContent);
    expect(text).toContain(EN.flag.FX_STALE);
    expect(text).toContain('1 TRY = 0.02250000 EUR, ECB rate of Nov 17, 2026');
  });

  it('renders nothing for a current rate', async () => {
    const host = await render({ nativeCurrency: 'TRY', currency: 'TRY', fx: FX_IDENTITY_TRY });
    expect(host.textContent?.trim()).toBe('');
  });
});
