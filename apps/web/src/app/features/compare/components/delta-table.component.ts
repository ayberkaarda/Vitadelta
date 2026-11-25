import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { DeltaRow, EngineParameters } from '../../../core/api/api-types';
import { Formatter, MINUS } from '../../../core/format/formatter';
import { DeltaBarComponent } from '../../../shared/charts/delta-bar.component';
import { FlagBadgeComponent } from '../../../shared/flags/flag-badge.component';
import { IconComponent } from '../../../shared/icon/icon.component';
import { CoverageStripComponent, StripBar } from '../../results/shared/coverage-strip.component';
import { ceilingRatio } from '../../results/shared/reference-data.store';

let nextId = 0;

/** One plain-language sentence of an expanded row: an i18n key and its formatted params. */
interface Reading {
  readonly key: string;
  readonly params: Readonly<Record<string, string>>;
}

/** One delta row with its display texts precomputed. */
interface RowView {
  readonly row: DeltaRow;
  readonly detailId: string;
  readonly changed: boolean;
  readonly amountA: string | null;
  readonly amountB: string | null;
  readonly delta: string;
  readonly coverageA: string | null;
  readonly coverageB: string | null;
  readonly utilityA: string;
  readonly utilityB: string;
  readonly weight: string;
  readonly bars: readonly StripBar[];
  readonly readings: readonly Reading[];
}

/**
 * Per-nutrient delta table of a comparison (design hand-off §4.5, §5.2, §5.3): daily amounts of
 * A and B, the signed change, coverage and utility A → B, weight, the change in contribution as
 * a delta bar (one shared scale per table) and the server's row flags. Rows with flags expand
 * into the A/B coverage strip and one plain sentence per flag. The footer shows the response's
 * `deltaContent`. Below 48rem changed rows come first as cards; unchanged ones fold away.
 */
@Component({
  selector: 'vd-delta-table',
  imports: [
    CoverageStripComponent,
    DeltaBarComponent,
    FlagBadgeComponent,
    IconComponent,
    TranslocoPipe,
  ],
  templateUrl: './delta-table.component.html',
  styleUrl: './delta-table.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DeltaTableComponent {
  private readonly format = inject(Formatter);

  readonly rows = input.required<readonly DeltaRow[]>();
  /** `CompareResponse.deltaContent` (the footer total; never re-summed here). */
  readonly deltaContent = input.required<number>();
  readonly engine = input<EngineParameters | null>(null);
  /** Reference set label of the market ("EU NRV"). */
  readonly reference = input.required<string>();

  protected readonly headingId = `vd-delta-${nextId++}`;
  private readonly prefix = `${this.headingId}-row`;

  protected readonly expanded = signal<ReadonlySet<string>>(new Set());
  protected readonly showUnchanged = signal(false);

  protected readonly ceiling = computed(() => ceilingRatio(this.engine()));
  protected readonly ceilingLabel = computed(() => {
    const ceiling = this.ceiling();
    return ceiling === null ? null : this.format.percent(ceiling);
  });
  protected readonly refLabel = computed(() => this.format.percent(1));

  protected readonly maxAbs = computed(() =>
    Math.max(0, ...this.rows().map((row) => Math.abs(row.deltaContribution))),
  );

  protected readonly unchangedCount = computed(
    () => this.views().filter((view) => !view.changed).length,
  );

  protected readonly totalText = computed(() => this.format.signed(this.deltaContent(), 4));

  protected readonly views = computed<readonly RowView[]>(() =>
    this.rows().map((row, index) => ({
      row,
      detailId: `${this.prefix}-${index}`,
      changed: row.deltaAmount !== 0,
      amountA: row.amountA > 0 ? this.format.quantity(row.amountA, row.unit) : null,
      amountB: row.amountB > 0 ? this.format.quantity(row.amountB, row.unit) : null,
      delta: this.deltaText(row),
      coverageA: row.rA === null ? null : this.format.percent(row.rA),
      coverageB: row.rB === null ? null : this.format.percent(row.rB),
      utilityA: this.format.amount(row.uA, 4),
      utilityB: this.format.amount(row.uB, 4),
      weight: this.format.weight(row.weight),
      bars: this.barsOf(row),
      readings: this.readingsOf(row),
    })),
  );

  protected toggle(code: string): void {
    this.expanded.update((current) => {
      const next = new Set(current);
      if (next.has(code)) {
        next.delete(code);
      } else {
        next.add(code);
      }
      return next;
    });
  }

  private deltaText(row: DeltaRow): string {
    if (row.deltaAmount === 0) {
      return this.format.amount(0, 0);
    }
    const body = this.format.quantity(Math.abs(row.deltaAmount), row.unit);
    return row.deltaAmount > 0 ? `+${body}` : `${MINUS}${body}`;
  }

  /**
   * One sentence per row flag. For `ABOVE_CEILING_NO_CREDIT` the sentence splits the extra amount
   * at the ceiling when A is below it and B above it: the reference amount is `amountA / rA`
   * (both from the response) and the ceiling ratio comes from the engine parameters. This is
   * display arithmetic only; the credited contribution itself is the server's `deltaContribution`.
   */
  private readingsOf(row: DeltaRow): Reading[] {
    return row.flags.map((flag) => {
      if (flag === 'ABOVE_CEILING_NO_CREDIT') {
        const split = this.ceilingSplit(row);
        if (split) {
          return { key: 'compare.flag_reading.ceiling_split', params: split };
        }
      }
      return { key: `compare.flag_reading.${flag}`, params: {} };
    });
  }

  private ceilingSplit(row: DeltaRow): Record<string, string> | null {
    const ceiling = this.ceiling();
    if (ceiling === null || row.rA === null || row.rA <= 0 || row.amountA <= 0) {
      return null;
    }
    const ceilingAmount = (row.amountA / row.rA) * ceiling;
    if (!(row.amountA < ceilingAmount && ceilingAmount < row.amountB)) {
      return null;
    }
    const approx = (value: number) =>
      this.format.quantity(Number(value.toFixed(value >= 10 ? 0 : value >= 1 ? 1 : 2)), row.unit);
    return {
      delta: approx(row.amountB - row.amountA),
      credited: approx(ceilingAmount - row.amountA),
      wasted: approx(row.amountB - ceilingAmount),
      ceiling: this.format.percent(ceiling),
    };
  }

  private barsOf(row: DeltaRow): StripBar[] {
    const ceiling = this.ceiling();
    const credit = (ratio: number) => (ceiling === null ? ratio : Math.min(ratio, ceiling));
    const bars: StripBar[] = [];
    if (row.rA !== null) {
      bars.push({ letter: 'A', ratio: row.rA, credited: credit(row.rA), tone: 'alt' });
    }
    if (row.rB !== null) {
      bars.push({ letter: 'B', ratio: row.rB, credited: credit(row.rB), tone: 'fill' });
    }
    return bars;
  }
}
