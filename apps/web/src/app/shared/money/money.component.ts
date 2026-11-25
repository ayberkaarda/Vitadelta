import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { FxMeta } from '../../core/api/api-types';
import { Formatter } from '../../core/format/formatter';

let nextId = 0;

/**
 * Money in the display currency (design hand-off §3 "Money", project specification §10.3).
 *
 * The amount is formatted with `Intl.NumberFormat` in the active locale (2 decimals). Below it a
 * sub-line shows the native amount when the value was converted, otherwise "price · N days".
 * The amount is a button whose tooltip (hover, focus; Esc closes) lists price, retailer, the
 * demo-price note and, when converted, the native amount and the ECB rate with its date, plus
 * the stale / unavailable rate notes from `meta.fx`.
 *
 * ```html
 * <vd-money [amount]="item.cost.costPerDay" [currency]="item.cost.currency"
 *   [nativeAmount]="item.cost.costPerDayNative" [nativeCurrency]="meta.nativeCurrency" [fx]="meta.fx"
 *   [price]="item.cost.effectivePrice" [daysSupply]="item.cost.daysSupply"
 *   [retailer]="item.selectedOffer.retailer" [checkedAt]="item.selectedOffer.checkedAt" />
 * ```
 */
@Component({
  selector: 'vd-money',
  imports: [TranslocoPipe],
  template: `
    <button
      type="button"
      class="money-btn"
      [attr.aria-describedby]="tooltipId"
      (mouseenter)="open.set(true)"
      (mouseleave)="open.set(false)"
      (focus)="open.set(true)"
      (blur)="open.set(false)"
      (keydown.escape)="open.set(false)"
    >
      <span class="amount u">{{ amountText() }}</span>
      @if (converted()) {
        <span class="sub">{{ nativeText() }}</span>
      } @else if (supply(); as sub) {
        <span class="sub">{{ 'money.days_supply' | transloco: sub }}</span>
      }
    </button>
    <div class="tip" role="tooltip" [id]="tooltipId" [hidden]="!open()">
      @if (price() !== null) {
        <div>
          <span class="k">{{ 'money.tooltip.price' | transloco }}</span>
          {{ priceText() }}
        </div>
      }
      @if (retailer(); as name) {
        <div>
          <span class="k">{{ 'money.tooltip.retailer' | transloco }}</span>
          {{ name }}
        </div>
      }
      @if (checkedAt(); as checked) {
        <div>{{ 'money.tooltip.demo_checked' | transloco: { date: format.date(checked) } }}</div>
      }
      @if (converted()) {
        <div>
          <span class="k">{{ 'money.tooltip.native' | transloco }}</span>
          {{ nativeText() }}
        </div>
        @if (rateLine(); as rate) {
          <div>{{ 'money.tooltip.rate' | transloco: rate }}</div>
        }
      }
      @if (fx()?.stale) {
        <div>{{ 'money.tooltip.stale' | transloco }}</div>
      }
      @if (fx()?.status === 'UNAVAILABLE') {
        <div>{{ 'money.tooltip.unavailable' | transloco: { currency: currency() } }}</div>
      }
    </div>
  `,
  styleUrl: './money.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MoneyComponent {
  protected readonly format = inject(Formatter);

  /** Amount in `currency` (the display currency of the response). */
  readonly amount = input.required<number>();
  readonly currency = input.required<string>();
  /** The same amount in the country's native currency (`*Native` field). */
  readonly nativeAmount = input<number | null>(null);
  readonly nativeCurrency = input<string | null>(null);
  /** `meta.fx` of the response. */
  readonly fx = input<FxMeta | null>(null);
  /** Effective price (display currency) for the "price · N days" sub-line and the tooltip. */
  readonly price = input<number | null>(null);
  readonly daysSupply = input<number | null>(null);
  readonly retailer = input<string | null>(null);
  readonly checkedAt = input<string | null>(null);

  protected readonly tooltipId = `vd-money-tip-${nextId++}`;
  protected readonly open = signal(false);

  protected readonly converted = computed(() => {
    const fx = this.fx();
    const native = this.nativeCurrency();
    return (
      fx !== null &&
      fx.status === 'OK' &&
      native !== null &&
      native !== this.currency() &&
      this.nativeAmount() !== null
    );
  });

  protected readonly amountText = computed(() => this.format.money(this.amount(), this.currency()));

  protected readonly priceText = computed(() => {
    const price = this.price();
    return price === null ? '' : this.format.money(price, this.currency());
  });

  protected readonly nativeText = computed(() => {
    const native = this.nativeAmount();
    const currency = this.nativeCurrency();
    return native === null || currency === null ? '' : this.format.money(native, currency);
  });

  protected readonly supply = computed(() => {
    const price = this.price();
    const days = this.daysSupply();
    if (price === null || days === null) {
      return null;
    }
    return {
      price: this.format.amount(price),
      days: this.format.amount(days, Number.isInteger(days) ? 0 : 2),
    };
  });

  protected readonly rateLine = computed(() => {
    const fx = this.fx();
    if (!fx || fx.rate === null || fx.asOf === null) {
      return null;
    }
    return {
      base: fx.base,
      quote: fx.quote,
      rate: this.format.rate(fx.rate),
      date: this.format.date(fx.asOf),
    };
  });
}
