import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterRenderEffect,
  computed,
  inject,
  input,
  output,
} from '@angular/core';
import { Params, RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';

import {
  BreakdownRow,
  Country,
  EngineParameters,
  QueryMeta,
  RankedProduct,
} from '../../../core/api/api-types';
import { Formatter } from '../../../core/format/formatter';
import { DrawerComponent } from '../../../shared/dialog/drawer.component';
import { DisclaimerComponent } from '../../../shared/disclaimer/disclaimer.component';
import { IconComponent } from '../../../shared/icon/icon.component';
import { ProvenanceMarkerComponent } from '../../../shared/provenance/provenance-marker.component';
import { CoverageStripComponent, StripBar } from '../shared/coverage-strip.component';
import { ProductActionButtonsComponent } from '../shared/product-action-buttons.component';
import { ProductActionNoticeComponent } from '../shared/product-action-notice.component';
import { ProductActionState } from '../shared/product-actions.service';
import { ceilingRatio, setLabel } from '../shared/reference-data.store';

/** One breakdown row prepared for display. */
interface RowView {
  readonly row: BreakdownRow;
  readonly target: boolean;
  readonly amount: string;
  readonly reference: string | null;
  readonly sourceKey: string | null;
  readonly sourceLabel: string | null;
  readonly coverage: string | null;
  readonly strip: { bars: StripBar[]; ceiling: number | null; ul: number | null } | null;
  readonly refLine: string | null;
}

let nextId = 0;

/**
 * Score breakdown of one ranked product (design hand-off §4.3): summary stats, one block per
 * `BreakdownRow` (reference and its source, coverage, utility × weight, contribution), markers
 * for rows capped at the ceiling or the upper limit with a coverage strip, the content score total,
 * the disclaimer and the save / alert / product page actions. Rows named by an `EXCEEDS_UL` flag
 * are highlighted and scrolled into view. Built on `vd-drawer` (dialog, focus trap, Esc, focus
 * restore; bottom sheet below 48rem).
 */
@Component({
  selector: 'vd-breakdown-drawer',
  imports: [
    CoverageStripComponent,
    DisclaimerComponent,
    DrawerComponent,
    IconComponent,
    ProductActionButtonsComponent,
    ProductActionNoticeComponent,
    ProvenanceMarkerComponent,
    RouterLink,
    TranslocoPipe,
  ],
  templateUrl: './breakdown-drawer.component.html',
  styleUrl: './breakdown-drawer.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BreakdownDrawerComponent {
  protected readonly format = inject(Formatter);
  protected readonly quantity = inject(Formatter);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /** Product shown; `null` keeps the drawer closed. */
  readonly item = input.required<RankedProduct | null>();
  readonly meta = input.required<QueryMeta>();
  readonly country = input<Country | null>(null);
  readonly engine = input<EngineParameters | null>(null);
  readonly state = input<ProductActionState | null>(null);
  /** Nutrients to highlight (from an `EXCEEDS_UL` flag). */
  readonly highlight = input<readonly string[]>([]);
  readonly linkParams = input<Params>({});
  readonly closed = output();

  protected readonly titleId = `vd-bd-title-${nextId++}`;

  protected readonly referenceSet = computed(() => {
    const code = this.country()?.referenceSetCode;
    return code ? setLabel(code) : '';
  });

  private readonly ceiling = computed(() => ceilingRatio(this.engine()));

  protected readonly rows = computed<readonly RowView[]>(() => {
    const item = this.item();
    if (!item) {
      return [];
    }
    const targets = new Set(this.highlight());
    return item.breakdown.map((row) => this.view(row, targets.has(row.code)));
  });

  protected readonly ceilingLabel = computed(() => {
    const ceiling = this.ceiling();
    return ceiling === null ? null : this.quantity.percent(ceiling);
  });

  constructor() {
    afterRenderEffect(() => {
      const codes = this.highlight();
      if (!this.item() || codes.length === 0) {
        return;
      }
      const target = this.host.nativeElement.ownerDocument.getElementById(`bd-row-${codes[0]}`);
      target?.scrollIntoView?.({ block: 'center' });
    });
  }

  private view(row: BreakdownRow, target: boolean): RowView {
    const raw =
      row.reference !== null && row.reference > 0 ? row.amountPerDay / row.reference : null;
    let strip: RowView['strip'] = null;
    if (raw !== null && row.coverageRatio !== null && (row.cappedAtUMax || row.cappedAtUl)) {
      const ceiling = this.ceiling();
      const credited =
        row.cappedAtUMax && ceiling !== null ? Math.min(ceiling, raw) : row.coverageRatio;
      strip = {
        bars: [{ letter: null, ratio: raw, credited, tone: 'fill' }],
        ceiling: row.cappedAtUMax ? ceiling : null,
        ul: row.cappedAtUl ? row.coverageRatio : null,
      };
    }
    return {
      row,
      target,
      amount: this.quantity.quantity(row.amountPerDay, row.unit),
      reference: row.reference !== null ? this.quantity.quantity(row.reference, row.unit) : null,
      sourceKey:
        row.referenceSource === 'REGULATORY'
          ? null
          : `results.drawer.source.${row.referenceSource}`,
      sourceLabel: row.referenceSource === 'REGULATORY' ? this.referenceSet() : null,
      coverage: row.coverageRatio !== null ? this.quantity.percent(row.coverageRatio) : null,
      strip,
      refLine: row.reference !== null ? this.quantity.quantity(row.reference, row.unit) : null,
    };
  }
}
