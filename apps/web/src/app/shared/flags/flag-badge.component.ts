import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { DeltaRowFlag, ProductFlagCode } from '../../core/api/api-types';
import { IconComponent } from '../icon/icon.component';
import { DELTA_FLAG_STYLE, FlagStyle, PRODUCT_FLAG_STYLE, toneClass } from './flag-style';

/**
 * One product or delta-row flag: icon + label + tone, never colour alone (design hand-off §3).
 *
 * ```html
 * <vd-flag-badge code="EXCEEDS_UL" [nutrients]="['ZINC']" [interactive]="true" (activate)="scrollTo($event)" />
 * <vd-flag-badge code="DIMINISHING" />
 * ```
 *
 * With `interactive` the badge is a button (used for `EXCEEDS_UL` to jump to the affected
 * nutrient rows) and emits `activate` with its code.
 */
@Component({
  selector: 'vd-flag-badge',
  imports: [IconComponent, TranslocoPipe],
  template: `
    @if (interactive()) {
      <button
        type="button"
        class="badge as-link"
        [class]="toneClassName()"
        [attr.data-code]="code()"
        (click)="activate.emit(code())"
      >
        <vd-icon [name]="style().icon" />
        <span>{{ style().labelKey | transloco: params() }}</span>
      </button>
    } @else {
      <span class="badge" [class]="toneClassName()" [attr.data-code]="code()">
        <vd-icon [name]="style().icon" />
        <span>{{ style().labelKey | transloco: params() }}</span>
      </span>
    }
  `,
  styles: `
    :host {
      display: inline-flex;
    }
    .as-link {
      cursor: pointer;
      font: inherit;
      font-size: var(--vd-font-size-xs);
      font-weight: var(--vd-font-weight-semibold);
      text-decoration: underline;
      text-underline-offset: 2px;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FlagBadgeComponent {
  readonly code = input.required<ProductFlagCode | DeltaRowFlag>();
  /** Affected nutrient codes (`ProductFlag.nutrientCodes`). */
  readonly nutrients = input<readonly string[]>([]);
  /** Currency named by `FX_UNAVAILABLE` (the native currency shown instead). */
  readonly currency = input<string | null>(null);
  /** Country named by `UNAVAILABLE_IN_COUNTRY`. */
  readonly country = input<string | null>(null);
  readonly interactive = input(false);
  readonly activate = output<ProductFlagCode | DeltaRowFlag>();

  protected readonly style = computed<FlagStyle>(() => {
    const code = this.code();
    return code in PRODUCT_FLAG_STYLE
      ? PRODUCT_FLAG_STYLE[code as ProductFlagCode]
      : DELTA_FLAG_STYLE[code as DeltaRowFlag];
  });
  protected readonly toneClassName = computed(() => `badge ${toneClass(this.style().tone)}`);
  protected readonly params = computed(() => ({
    nutrients: this.nutrients().join(', '),
    currency: this.currency() ?? '',
    country: this.country() ?? '',
  }));
}
