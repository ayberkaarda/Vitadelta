import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { Formatter } from '../../core/format/formatter';
import { deltaBarGeometry } from './bar-geometry';

/**
 * Delta bar (design hand-off §5.2): zero axis in the middle, gain to the right in
 * `--vd-delta-gain`, loss to the left in `--vd-delta-loss`, a dot for no change, and the signed
 * value (U+2212 minus, 4 decimals) on the right. Bars of one table share `maxAbs`.
 *
 * ```html
 * <vd-delta-bar [delta]="row.deltaContribution" [maxAbs]="maxAbsDelta()" />
 * ```
 */
@Component({
  selector: 'vd-delta-bar',
  imports: [TranslocoPipe],
  template: `
    <div class="dbar">
      <svg width="100%" height="16" aria-hidden="true" focusable="false">
        <line class="svg-axis" x1="50%" x2="50%" y1="0" y2="16" />
        @switch (geometry().kind) {
          @case ('gain') {
            <rect
              class="svg-gain"
              [attr.x]="geometry().x + '%'"
              y="4"
              [attr.width]="geometry().width + '%'"
              height="8"
              rx="2"
            />
          }
          @case ('loss') {
            <rect
              class="svg-loss"
              [attr.x]="geometry().x + '%'"
              y="4"
              [attr.width]="geometry().width + '%'"
              height="8"
              rx="2"
            />
          }
          @default {
            <circle class="svg-fill-alt" cx="50%" cy="8" r="2.5" />
          }
        }
      </svg>
      <span class="v" [class.zero]="geometry().kind === 'zero'" aria-hidden="true">{{
        valueText()
      }}</span>
      <span class="sr-only">{{
        'score.delta_accessible' | transloco: { value: valueText() }
      }}</span>
    </div>
  `,
  styleUrl: './delta-bar.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DeltaBarComponent {
  private readonly format = inject(Formatter);

  readonly delta = input.required<number>();
  /** Largest absolute delta of all rows drawn together. */
  readonly maxAbs = input.required<number>();

  protected readonly geometry = computed(() => deltaBarGeometry(this.delta(), this.maxAbs()));
  protected readonly valueText = computed(() => this.format.signed(this.delta(), 4));
}
