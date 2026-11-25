import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { meterWidth } from './bar-geometry';

/**
 * Usage meter (design hand-off §3, §5.5) for the server's `UsageMeter` counters: label, "3 of 5"
 * (or "12 · unlimited"), and a 6 px bar; the warning tone and "limit reached" text appear when
 * the server counters say `used >= limit`. `role="meter"` carries the values.
 *
 * ```html
 * <vd-usage-meter [label]="'account.usage.saved' | transloco" [used]="u.used" [limit]="u.limit" />
 * ```
 */
@Component({
  selector: 'vd-usage-meter',
  imports: [TranslocoPipe],
  template: `
    <div
      class="meter"
      role="meter"
      [attr.aria-label]="label()"
      aria-valuemin="0"
      [attr.aria-valuemax]="limit()"
      [attr.aria-valuenow]="used()"
      [attr.aria-valuetext]="valueKey() | transloco: { used: used(), limit: limit() }"
    >
      <div class="top">
        <span>{{ label() }}</span>
        <span class="n" [class.full]="reached()">{{
          valueKey() | transloco: { used: used(), limit: limit() }
        }}</span>
      </div>
      <svg width="100%" height="6" aria-hidden="true" focusable="false">
        <rect class="svg-track" width="100%" height="6" rx="3" />
        @if (width() > 0) {
          <rect
            [attr.class]="reached() ? 'svg-warn' : 'svg-fill'"
            [attr.width]="width() + '%'"
            height="6"
            rx="3"
          />
        }
      </svg>
    </div>
  `,
  styles: `
    .meter {
      display: flex;
      flex-direction: column;
      gap: 6px;
    }
    .top {
      display: flex;
      justify-content: space-between;
      align-items: baseline;
      gap: var(--vd-space-2);
      font-size: var(--vd-font-size-sm);
    }
    .n {
      font-variant-numeric: tabular-nums;
      font-weight: var(--vd-font-weight-bold);
    }
    .n.full {
      color: var(--vd-color-warning);
    }
    svg {
      display: block;
      width: 100%;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UsageMeterComponent {
  /** Translated label text. */
  readonly label = input.required<string>();
  readonly used = input.required<number>();
  /** `null` means unlimited. */
  readonly limit = input.required<number | null>();

  protected readonly reached = computed(() => {
    const limit = this.limit();
    return limit !== null && this.used() >= limit;
  });
  protected readonly width = computed(() => meterWidth(this.used(), this.limit()));
  protected readonly valueKey = computed(() => {
    if (this.limit() === null) {
      return 'usage.unlimited';
    }
    return this.reached() ? 'usage.limit_reached' : 'usage.of';
  });
}
