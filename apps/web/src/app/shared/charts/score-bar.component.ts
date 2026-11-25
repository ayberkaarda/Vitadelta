import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { Formatter } from '../../core/format/formatter';
import { scoreBarWidth } from './bar-geometry';

/** Which score the bar shows; picks the accessible label. */
export type ScoreKind = 'content' | 'value' | 'custom';

/**
 * Score bar (design hand-off §3, §5.1): display integer, hand-written SVG bar on the 0..100
 * display scale and the raw score with 4 decimals. The bar of the active ranking criterion uses
 * `--vd-bar-fill`, the other one `--vd-bar-fill-alt`.
 *
 * ```html
 * <vd-score-bar kind="content" [display]="item.contentScoreDisplay" [raw]="item.contentScore" [active]="true" />
 * ```
 */
@Component({
  selector: 'vd-score-bar',
  imports: [TranslocoPipe],
  template: `
    <div class="sbar">
      <span class="v" aria-hidden="true">{{ displayText() }}</span>
      <svg width="100%" height="8" aria-hidden="true" focusable="false">
        <rect class="svg-track" width="100%" height="8" rx="4" />
        @if (width() > 0) {
          <rect
            [attr.class]="active() ? 'svg-fill' : 'svg-fill-alt'"
            [attr.width]="width() + '%'"
            height="8"
            rx="4"
          />
        }
      </svg>
      <span class="raw" aria-hidden="true">{{ rawText() }}</span>
      <span class="sr-only">{{
        'score.accessible'
          | transloco
            : { label: ('score.' + kind() | transloco), display: displayText(), raw: rawText() }
      }}</span>
    </div>
  `,
  styleUrl: './score-bar.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ScoreBarComponent {
  private readonly format = inject(Formatter);

  readonly kind = input.required<ScoreKind>();
  /** Display-normalised score, 0..100. */
  readonly display = input.required<number>();
  /** Raw score as returned by the API. */
  readonly raw = input.required<number>();
  /** `true` when this score is the active ranking criterion. */
  readonly active = input(false);

  protected readonly width = computed(() => scoreBarWidth(this.display()));
  protected readonly displayText = computed(() =>
    this.format.integer(Math.max(0, Math.min(100, this.display()))),
  );
  protected readonly rawText = computed(() => this.format.amount(this.raw(), 4));
}
