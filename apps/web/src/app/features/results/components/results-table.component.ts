import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { Params, RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';

import { QueryMeta, RankedProduct, RankingMode } from '../../../core/api/api-types';
import { ScoreBarComponent } from '../../../shared/charts/score-bar.component';
import { FlagBadgesComponent } from '../../../shared/flags/flag-badges.component';
import { IconComponent } from '../../../shared/icon/icon.component';
import { MoneyComponent } from '../../../shared/money/money.component';
import { ProvenanceMarkerComponent } from '../../../shared/provenance/provenance-marker.component';
import { ProductActionButtonsComponent } from '../shared/product-action-buttons.component';
import { ProductActionNoticeComponent } from '../shared/product-action-notice.component';
import { ProductActionState } from '../shared/product-actions.service';

/** Request to open the breakdown drawer, optionally focused on some nutrients. */
export interface BreakdownRequest {
  readonly item: RankedProduct;
  readonly codes: readonly string[];
}

/**
 * Ranked list exactly as the server returned it (design hand-off §4.3): selection checkbox, rank,
 * product with provenance, cost per day, score bars with raw values, flags and the row actions.
 * The active ranking criterion's bar uses the accent fill. Below 48rem the rows become cards.
 * The drawer it opens restores focus to the element that had it (the Breakdown button or flag).
 */
@Component({
  selector: 'vd-results-table',
  imports: [
    FlagBadgesComponent,
    IconComponent,
    MoneyComponent,
    ProductActionButtonsComponent,
    ProductActionNoticeComponent,
    ProvenanceMarkerComponent,
    RouterLink,
    ScoreBarComponent,
    TranslocoPipe,
  ],
  templateUrl: './results-table.component.html',
  styleUrl: './results-table.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ResultsTableComponent {
  readonly items = input.required<readonly RankedProduct[]>();
  readonly meta = input.required<QueryMeta>();
  readonly mode = input.required<RankingMode>();
  readonly states = input.required<ReadonlyMap<string, ProductActionState>>();
  readonly selected = input<readonly string[]>([]);
  /** Query parameters carried to the product page links. */
  readonly linkParams = input<Params>({});

  readonly toggleSelect = output<RankedProduct>();
  readonly breakdown = output<BreakdownRequest>();

  protected readonly columns = computed(() => (this.mode() === 'CUSTOM' ? 9 : 8));

  protected isSelected(item: RankedProduct): boolean {
    return this.selected().includes(item.productId);
  }

  protected sortOf(column: RankingMode): 'ascending' | 'descending' | null {
    if (this.mode() !== column) {
      return null;
    }
    return column === 'PRICE' ? 'ascending' : 'descending';
  }
}
