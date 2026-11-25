import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { Params, RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';

import { QueryMeta } from '../../../core/api/api-types';
import { EmptyStateComponent } from '../../../shared/state/empty-state.component';

type ExcludedCountsView = QueryMeta['excluded'];

/**
 * Empty ranking (`total = 0`, design hand-off §4.4): what the market lost to each filter, from
 * `meta.excluded`, and the two ways out: edit the required nutrients or include out-of-stock
 * offers (only offered while the query is in-stock only).
 */
@Component({
  selector: 'vd-results-empty',
  imports: [EmptyStateComponent, RouterLink, TranslocoPipe],
  template: `
    @let e = excluded();
    <div class="card">
      <vd-empty-state icon="search" [heading]="'results.empty.title' | transloco">
        <p>{{ 'results.empty.text' | transloco }}</p>
        <dl class="kv counts">
          <dt>{{ 'results.empty.required' | transloco }}</dt>
          <dd>{{ e.requiredNutrients }}</dd>
          <dt>{{ 'results.empty.exceeds_ul' | transloco }}</dt>
          <dd>{{ e.exceedsUl }}</dd>
          <dt>{{ 'results.empty.no_stock' | transloco }}</dt>
          <dd>{{ e.noStock }}</dd>
          <dt>{{ 'results.empty.no_offer' | transloco: { country: countryName() } }}</dt>
          <dd>{{ e.noOffer }}</dd>
        </dl>
        <div vdStateActions>
          <a
            class="btn btn-primary"
            routerLink="/"
            fragment="required"
            [queryParams]="editParams()"
            >{{ 'results.empty.edit_required' | transloco }}</a
          >
          @if (inStockOnly()) {
            <button type="button" class="btn" (click)="includeOutOfStock.emit()">
              {{ 'results.empty.include_out_of_stock' | transloco }}
            </button>
          }
        </div>
      </vd-empty-state>
    </div>
  `,
  styles: `
    .card {
      padding: 0;
    }
    .counts {
      text-align: left;
    }
    .counts dd {
      text-align: right;
      font-weight: var(--vd-font-weight-semibold);
    }
    [vdStateActions] {
      display: contents;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ResultsEmptyComponent {
  readonly excluded = input.required<ExcludedCountsView>();
  readonly countryName = input.required<string>();
  readonly inStockOnly = input(true);
  readonly editParams = input<Params>({});
  readonly includeOutOfStock = output();
}
