import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { Formatter } from '../../../core/format/formatter';

/** Labels closer than this (percent of the width) to the value label are dropped. */
const MIN_LABEL_GAP = 8;

interface ScaleLabel {
  readonly x: number;
  readonly text: string;
}

/**
 * Ratio scale of the verdict card (design hand-off §5.4): worth-it, marginal and not-worth-it
 * segments from 0 to 3 split at the configured thresholds, and a marker at the server's
 * `ratio`. A ratio above the scale sits at the right edge with an "off scale" label. The
 * thresholds come from `GET /weights/defaults` (`engine`); the verdict itself is the server's.
 */
@Component({
  selector: 'vd-ratio-scale',
  imports: [TranslocoPipe],
  template: `
    <svg
      width="100%"
      height="40"
      role="img"
      [attr.aria-label]="
        'compare.scale.label'
          | transloco: { ratio: ratioText(), worth: worthText(), marginal: marginalText() }
      "
    >
      <rect class="worth" x="0" y="8" [attr.width]="worthX() + '%'" height="8" />
      <rect
        class="marginal"
        [attr.x]="worthX() + '%'"
        y="8"
        [attr.width]="marginalX() - worthX() + '%'"
        height="8"
      />
      <rect
        class="notworth"
        [attr.x]="marginalX() + '%'"
        y="8"
        [attr.width]="100 - marginalX() + '%'"
        height="8"
      />
      <line class="sep" [attr.x1]="worthX() + '%'" [attr.x2]="worthX() + '%'" y1="6" y2="18" />
      <line
        class="sep"
        [attr.x1]="marginalX() + '%'"
        [attr.x2]="marginalX() + '%'"
        y1="6"
        y2="18"
      />
      <circle class="marker" [attr.cx]="markerX() + '%'" cy="12" r="6" />
      @for (label of labels(); track label.text) {
        <text class="txt" [attr.x]="label.x + '%'" y="34" text-anchor="middle">
          {{ label.text }}
        </text>
      }
      <text class="txt strong" [attr.x]="valueX() + '%'" y="34" [attr.text-anchor]="valueAnchor()">
        @if (offScale()) {
          {{ 'compare.scale.off_scale' | transloco: { ratio: ratioText() } }}
        } @else {
          {{ 'compare.scale.value' | transloco: { ratio: ratioText() } }}
        }
      </text>
    </svg>
  `,
  styles: `
    :host {
      display: block;
    }
    svg {
      display: block;
      width: 100%;
      overflow: visible;
    }
    .worth {
      fill: var(--vd-verdict-worth);
    }
    .marginal {
      fill: var(--vd-verdict-marginal);
    }
    .notworth {
      fill: var(--vd-verdict-not-worth);
    }
    .sep {
      stroke: var(--vd-color-surface);
      stroke-width: 2;
    }
    .marker {
      fill: var(--vd-color-surface);
      stroke: var(--vd-color-text);
      stroke-width: 2;
    }
    .txt {
      fill: var(--vd-color-text-muted);
      font-size: 11px;
      font-family: var(--vd-font-family);
      font-variant-numeric: tabular-nums;
    }
    .strong {
      fill: var(--vd-color-text);
      font-weight: var(--vd-font-weight-bold);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RatioScaleComponent {
  private readonly format = inject(Formatter);

  /** `CompareResponse.ratio` (never `null` here: the card hides the scale then). */
  readonly ratio = input.required<number>();
  /** `engine.verdictWorthItMaxRatio`. */
  readonly worth = input.required<number>();
  /** `engine.verdictMarginalMaxRatio`. */
  readonly marginal = input.required<number>();

  private readonly max = computed(() => Math.max(3, this.marginal() * 1.5));
  private pos(value: number): number {
    return (Math.min(Math.max(value, 0), this.max()) / this.max()) * 100;
  }

  protected readonly worthX = computed(() => this.pos(this.worth()));
  protected readonly marginalX = computed(() => this.pos(this.marginal()));
  protected readonly offScale = computed(() => this.ratio() > this.max());
  protected readonly markerX = computed(() =>
    this.offScale() ? 98.5 : Math.min(98.5, Math.max(1.5, this.pos(this.ratio()))),
  );

  protected readonly ratioText = computed(() => this.format.amount(this.ratio(), 1));
  protected readonly worthText = computed(() => this.format.amount(this.worth(), 1));
  protected readonly marginalText = computed(() => this.format.amount(this.marginal(), 1));

  protected readonly valueX = computed(() => (this.offScale() ? 100 : this.markerX()));
  protected readonly valueAnchor = computed(() => {
    if (this.offScale() || this.markerX() > 95) {
      return 'end';
    }
    return this.markerX() < 5 ? 'start' : 'middle';
  });

  /** Threshold labels; the one nearest the value label is dropped when they would overlap. */
  protected readonly labels = computed<readonly ScaleLabel[]>(() => {
    // The off-scale label is right-aligned and wide: treat its centre as further left.
    const valueCentre = this.offScale() ? 90 : this.markerX();
    const candidates: ScaleLabel[] = [
      { x: 1, text: this.format.amount(0, 0) },
      { x: this.worthX(), text: this.worthText() },
      { x: this.marginalX(), text: this.marginalText() },
    ];
    return candidates.filter((label) => Math.abs(label.x - valueCentre) >= MIN_LABEL_GAP);
  });
}
