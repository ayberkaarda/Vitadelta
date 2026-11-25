import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

let nextId = 0;

/** One bar of a coverage strip, in multiples of the reference (1 = 100 %). */
export interface StripBar {
  /** "A" / "B" in the compare variant; `null` for a single bar. */
  readonly letter: string | null;
  /** Coverage before any cap. */
  readonly ratio: number;
  /** Coverage that still earns credit (capped at the ceiling or the upper limit). */
  readonly credited: number;
  /** `fill` = accent (B, drawer), `alt` = muted (A). */
  readonly tone: 'fill' | 'alt';
}

interface DrawnBar {
  readonly letter: string | null;
  readonly y: number;
  readonly fillWidth: number;
  readonly hatchX: number;
  readonly hatchWidth: number;
  readonly tone: 'fill' | 'alt';
}

const BAR_PITCH = 14;
const MIN_LABEL_GAP = 14;

/**
 * Coverage strip (design hand-off §5.3): one 8 px track per product, the credited part filled,
 * the part above the ceiling or upper limit hatched (no credit), the reference line at 100 %,
 * the ceiling line (dashed) and the upper-limit line. Scale `max(5, rMax × 1.2)` multiples of the
 * reference. Decorative (`aria-hidden`): the caller states the same facts in text.
 *
 * ```html
 * <vd-coverage-strip [bars]="[{ letter: null, ratio: 4.375, credited: 4.17, tone: 'fill' }]"
 *   [ceiling]="4.17" [refLabel]="'100 % (80 mg)'" [ceilLabel]="'ceiling 417 %'" />
 * ```
 */
@Component({
  selector: 'vd-coverage-strip',
  template: `
    <svg width="100%" [attr.height]="height()" aria-hidden="true" focusable="false">
      <defs>
        <pattern
          [attr.id]="hatchId"
          width="4"
          height="4"
          patternUnits="userSpaceOnUse"
          patternTransform="rotate(45)"
        >
          <rect class="svg-track" width="4" height="4" />
          <line class="hatch" x1="0" y1="0" x2="0" y2="4" />
        </pattern>
      </defs>
      @for (bar of drawn(); track $index) {
        @if (bar.letter) {
          <text class="txt strong" x="0" [attr.y]="bar.y + 8">{{ bar.letter }}</text>
        }
        <rect
          class="svg-track"
          [attr.x]="offset() + '%'"
          [attr.y]="bar.y"
          [attr.width]="100 - offset() + '%'"
          height="8"
          rx="2"
        />
        @if (bar.fillWidth > 0) {
          <rect
            [attr.class]="bar.tone === 'fill' ? 'svg-fill' : 'svg-fill-alt'"
            [attr.x]="offset() + '%'"
            [attr.y]="bar.y"
            [attr.width]="bar.fillWidth + '%'"
            height="8"
            rx="2"
          />
        }
        @if (bar.hatchWidth > 0) {
          <rect
            [attr.x]="bar.hatchX + '%'"
            [attr.y]="bar.y"
            [attr.width]="bar.hatchWidth + '%'"
            height="8"
            [attr.fill]="'url(#' + hatchId + ')'"
          />
        }
      }
      <line
        class="ref"
        [attr.x1]="refX() + '%'"
        [attr.x2]="refX() + '%'"
        y1="0"
        [attr.y2]="barsHeight()"
      />
      @if (ulX(); as x) {
        <line class="ul" [attr.x1]="x + '%'" [attr.x2]="x + '%'" y1="0" [attr.y2]="barsHeight()" />
      }
      @if (ceilX(); as x) {
        <line
          class="ceil"
          [attr.x1]="x + '%'"
          [attr.x2]="x + '%'"
          y1="0"
          [attr.y2]="barsHeight()"
        />
      }
      @if (showRefLabel()) {
        <text class="txt" [attr.x]="refX() + '%'" [attr.y]="labelY()" text-anchor="middle">
          {{ refLabel() }}
        </text>
      }
      @if (ceilX(); as x) {
        @if (ceilLabel(); as label) {
          <text class="txt strong" [attr.x]="x + '%'" [attr.y]="labelY()" text-anchor="middle">
            {{ label }}
          </text>
        }
      }
    </svg>
  `,
  styles: `
    :host {
      display: block;
      min-width: 12rem;
    }
    svg {
      display: block;
      width: 100%;
      overflow: visible;
    }
    .ref {
      stroke: var(--vd-color-border-strong);
      stroke-width: 1;
    }
    .ceil {
      stroke: var(--vd-color-text);
      stroke-width: 2;
      stroke-dasharray: 3 2;
    }
    .ul {
      stroke: var(--vd-color-warning);
      stroke-width: 2;
    }
    .hatch {
      stroke: var(--vd-nocredit);
      stroke-width: 1.5;
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
export class CoverageStripComponent {
  readonly bars = input.required<readonly StripBar[]>();
  /** Coverage at the utility ceiling; `null` hides the ceiling line. */
  readonly ceiling = input<number | null>(null);
  /** Upper limit in multiples of the reference; `null` when no limit applies. */
  readonly ul = input<number | null>(null);
  /** Translated label of the reference line ("100 % (80 mg)"). */
  readonly refLabel = input.required<string>();
  /** Translated label of the ceiling line ("ceiling 417 %"). */
  readonly ceilLabel = input<string | null>(null);

  protected readonly hatchId = `vd-hatch-${nextId++}`;

  /** Left margin reserved for the A/B letters. */
  protected readonly offset = computed(() => (this.bars().some((bar) => bar.letter) ? 4 : 0));

  private readonly scale = computed(() => {
    const values = this.bars().map((bar) => bar.ratio);
    const ceiling = this.ceiling();
    const ul = this.ul();
    if (ceiling !== null) {
      values.push(ceiling);
    }
    if (ul !== null) {
      values.push(ul);
    }
    return Math.max(5, Math.max(0, ...values) * 1.2);
  });

  private pos(ratio: number): number {
    const span = 100 - this.offset();
    return this.offset() + (span * Math.max(0, ratio)) / this.scale();
  }

  protected readonly barsHeight = computed(() => this.bars().length * BAR_PITCH);
  protected readonly labelY = computed(() => this.barsHeight() + 10);
  protected readonly height = computed(() => this.barsHeight() + 14);

  protected readonly drawn = computed<readonly DrawnBar[]>(() =>
    this.bars().map((bar, index) => {
      const credited = Math.min(bar.credited, bar.ratio);
      const start = this.offset();
      const fillEnd = this.pos(credited);
      const end = this.pos(bar.ratio);
      return {
        letter: bar.letter,
        y: index * BAR_PITCH + 2,
        fillWidth: Math.max(0, fillEnd - start),
        hatchX: fillEnd,
        hatchWidth: Math.max(0, end - fillEnd),
        tone: bar.tone,
      };
    }),
  );

  protected readonly refX = computed(() => this.pos(1));
  protected readonly ceilX = computed(() => {
    const ceiling = this.ceiling();
    return ceiling === null ? null : this.pos(ceiling);
  });
  protected readonly ulX = computed(() => {
    const ul = this.ul();
    return ul === null ? null : this.pos(ul);
  });

  /** The reference label is dropped when it would collide with the ceiling label. */
  protected readonly showRefLabel = computed(() => {
    const ceil = this.ceilX();
    return ceil === null || !this.ceilLabel() || Math.abs(ceil - this.refX()) >= MIN_LABEL_GAP;
  });
}
