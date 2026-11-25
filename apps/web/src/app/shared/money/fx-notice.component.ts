import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { Formatter } from '../../core/format/formatter';
import { MoneyContext } from '../../core/state/currency.store';
import { IconComponent } from '../icon/icon.component';

/**
 * Explains the FX state of a money-bearing response (`meta.fx`):
 * - `UNAVAILABLE`: info notice "Prices are shown in TRY" (amounts stayed native);
 * - stale rate: neutral "Exchange rate stale" badge plus the rate and its date;
 * - otherwise nothing.
 *
 * ```html
 * <vd-fx-notice [meta]="response.meta" />
 * ```
 */
@Component({
  selector: 'vd-fx-notice',
  imports: [IconComponent, TranslocoPipe],
  template: `
    @let m = meta();
    @if (m.fx.status === 'UNAVAILABLE') {
      <div class="notice" role="status" data-state="unavailable">
        <vd-icon name="fx" />
        <div class="body">
          <p class="nt">{{ 'fx.unavailable_title' | transloco: { currency: m.nativeCurrency } }}</p>
          <p>
            {{
              'fx.unavailable_body'
                | transloco: { base: m.fx.base, quote: m.fx.quote, currency: m.nativeCurrency }
            }}
          </p>
        </div>
      </div>
    } @else if (stale(); as s) {
      <p class="stale" data-state="stale">
        <span class="badge">
          <vd-icon name="clock" />
          <span>{{ 'flag.FX_STALE' | transloco }}</span>
        </span>
        <span class="muted">{{ 'fx.stale_body' | transloco: s }}</span>
      </p>
    }
  `,
  styles: `
    .stale {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--vd-space-2);
      font-size: var(--vd-font-size-sm);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FxNoticeComponent {
  private readonly format = inject(Formatter);

  readonly meta = input.required<MoneyContext>();

  protected readonly stale = computed(() => {
    const fx = this.meta().fx;
    if (!fx.stale || fx.rate === null || fx.asOf === null) {
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
