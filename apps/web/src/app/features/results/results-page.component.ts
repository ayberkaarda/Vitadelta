import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Params, Router, RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { ApiProblem } from '../../core/api/api-problem';
import {
  RankedProduct,
  RankingMode,
  RecommendationQueryRequest,
  RecommendationQueryResponse,
} from '../../core/api/api-types';
import { loginQueryParams } from '../../core/auth/return-url';
import { SessionStore } from '../../core/auth/session.store';
import { Formatter } from '../../core/format/formatter';
import { CurrencyStore } from '../../core/state/currency.store';
import { QUERY_PARAM, QueryStore } from '../../core/state/query.store';
import { RegionStore } from '../../core/state/region.store';
import { DisclaimerComponent } from '../../shared/disclaimer/disclaimer.component';
import { IconComponent } from '../../shared/icon/icon.component';
import { LockedResultsComponent } from '../../shared/locked-results/locked-results.component';
import { FxNoticeComponent } from '../../shared/money/fx-notice.component';
import { PaginationComponent } from '../../shared/pagination/pagination.component';
import { ProvenanceMarkerComponent } from '../../shared/provenance/provenance-marker.component';
import { SkeletonComponent } from '../../shared/state/skeleton.component';
import { EmphasizedTextComponent, emphasize } from '../../shared/text/emphasized-text.component';
import { BreakdownDrawerComponent } from './components/breakdown-drawer.component';
import { CompareBarComponent, SelectedProduct } from './components/compare-bar.component';
import { QuotaExhaustedComponent } from './components/quota-exhausted.component';
import { ResultsEmptyComponent } from './components/results-empty.component';
import { ResultsErrorComponent } from './components/results-error.component';
import { BreakdownRequest, ResultsTableComponent } from './components/results-table.component';
import { ProductActionState, ProductActionsService } from './shared/product-actions.service';
import { FIELD_ERRORS_STATE, PRESELECT_PARAM } from './shared/navigation-state';
import { ReferenceDataStore, setLabel } from './shared/reference-data.store';

type Status = 'loading' | 'ready' | 'error';

/**
 * Ranked results of the query in the URL (project specification §10.2, §10.3; design hand-off
 * §4.3, §4.4). The query is restored from the URL on every change, so a results link is
 * shareable. Everything shown comes from the response: locked rows only from
 * `meta.visibility`, the quota from `meta.principal` or the `403 quota_exceeded` problem.
 */
@Component({
  selector: 'vd-results-page',
  imports: [
    EmphasizedTextComponent,
    BreakdownDrawerComponent,
    CompareBarComponent,
    DisclaimerComponent,
    FxNoticeComponent,
    IconComponent,
    LockedResultsComponent,
    PaginationComponent,
    ProvenanceMarkerComponent,
    QuotaExhaustedComponent,
    ResultsEmptyComponent,
    ResultsErrorComponent,
    ResultsTableComponent,
    RouterLink,
    SkeletonComponent,
    TranslocoPipe,
  ],
  templateUrl: './results-page.component.html',
  styleUrl: './results-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ResultsPageComponent {
  private readonly api = inject(ApiClient);
  private readonly route = inject(ActivatedRoute);
  protected readonly router = inject(Router);
  private readonly store = inject(QueryStore);
  private readonly session = inject(SessionStore);
  private readonly currency = inject(CurrencyStore);
  private readonly region = inject(RegionStore);
  private readonly actions = inject(ProductActionsService);
  protected readonly reference = inject(ReferenceDataStore);
  protected readonly format = inject(Formatter);
  protected readonly emphasize = emphasize;

  private readonly params = toSignal(this.route.queryParamMap, {
    initialValue: this.route.snapshot.queryParamMap,
  });
  private readonly ready = signal(false);
  private lastKey: string | null = null;
  private lastRequest: RecommendationQueryRequest | null = null;
  private sequence = 0;

  protected readonly status = signal<Status>('loading');
  protected readonly response = signal<RecommendationQueryResponse | null>(null);
  protected readonly problem = signal<ApiProblem | null>(null);
  protected readonly mode = signal<RankingMode>('VALUE');
  protected readonly selection = signal<readonly SelectedProduct[]>([]);
  protected readonly selectionNote = signal<string | null>(null);
  protected readonly drawer = signal<BreakdownRequest | null>(null);

  protected readonly meta = computed(() => this.response()?.meta ?? null);
  protected readonly items = computed(() => this.response()?.items ?? []);
  protected readonly country = computed(() => {
    const code = this.meta()?.country ?? this.store.country();
    return this.region.countries().find((entry) => entry.code === code) ?? null;
  });
  protected readonly selectedIds = computed(() => this.selection().map((entry) => entry.id));
  protected readonly inStockOnly = computed(
    () => this.store.state().stockFilter === 'IN_STOCK_ONLY',
  );

  /** Save / alert state per product of the current page (from `saved` and `alerts`). */
  protected readonly states = computed<ReadonlyMap<string, ProductActionState>>(() => {
    const response = this.response();
    const map = new Map<string, ProductActionState>();
    if (!response) {
      return map;
    }
    for (const item of response.items) {
      map.set(
        item.productId,
        this.actions.create(item.productId, response.meta.country, item.saved, item.alerts),
      );
    }
    return map;
  });

  /** Query parameters for links that keep the query (edit, product pages, compare). */
  protected readonly linkParams = computed<Params>(() => {
    this.params();
    return Object.fromEntries(
      Object.entries(this.store.queryParams()).filter(([key]) => key !== QUERY_PARAM.page),
    );
  });

  protected readonly loginParams = computed(() => {
    this.params();
    return loginQueryParams(this.router.url);
  });

  protected readonly summary = computed(() => {
    const meta = this.meta();
    if (!meta) {
      return [];
    }
    const parts: { key: string; params?: Record<string, unknown> }[] = [
      { key: `results.stock.${this.store.state().stockFilter}` },
    ];
    const excluded = meta.excluded;
    const add = (count: number, key: string): void => {
      if (count > 0) {
        parts.push({ key: count === 1 ? `${key}_one` : key, params: { count } });
      }
    };
    add(excluded.noStock, 'results.excluded.no_stock');
    add(excluded.noOffer, 'results.excluded.no_offer');
    add(excluded.requiredNutrients, 'results.excluded.required');
    add(excluded.exceedsUl, 'results.excluded.exceeds_ul');
    const country = this.country();
    if (country) {
      parts.push({
        key: 'results.reference_line',
        params: { reference: setLabel(country.referenceSetCode), ul: setLabel(country.ulSetCode) },
      });
    }
    return parts;
  });

  protected readonly drawerItem = computed(() => this.drawer()?.item ?? null);
  protected readonly drawerState = computed(() => {
    const item = this.drawerItem();
    return item ? (this.states().get(item.productId) ?? null) : null;
  });

  constructor() {
    void this.session.whenReady().then(() => this.ready.set(true));
    void this.reference.loadDefaults();

    effect(() => {
      const params = this.params();
      const ready = this.ready();
      const displayCurrency = this.currency.requestCurrency();
      this.region.countryCode();
      const request = untracked(() => {
        this.store.restore(params);
        return ready ? this.store.toRequest(displayCurrency) : null;
      });
      if (request) {
        untracked(() => void this.run(request));
      }
    });
  }

  /** Runs the request again (retry after an error). */
  protected retry(): void {
    if (this.lastRequest) {
      void this.run(this.lastRequest, true);
    }
  }

  protected goToPage(page: number): void {
    this.store.patch({ page });
    void this.router.navigate(['/results'], { queryParams: this.store.queryParams() });
  }

  protected includeOutOfStock(): void {
    this.store.patch({ stockFilter: 'INCLUDE_OUT_OF_STOCK' });
    void this.router.navigate(['/results'], { queryParams: this.store.queryParams() });
  }

  protected toggleSelect(item: RankedProduct): void {
    const current = this.selection();
    if (current.some((entry) => entry.id === item.productId)) {
      this.selection.set(current.filter((entry) => entry.id !== item.productId));
      this.selectionNote.set(null);
      return;
    }
    const next = [...current, { id: item.productId, name: item.name }];
    if (next.length > 2) {
      const dropped = next.shift();
      this.selectionNote.set(dropped?.name ?? null);
    } else {
      this.selectionNote.set(null);
    }
    this.selection.set(next);
  }

  protected removeSelected(id: string): void {
    this.selection.set(this.selection().filter((entry) => entry.id !== id));
    this.selectionNote.set(null);
  }

  protected compare(): void {
    const [baseline, candidate] = this.selection();
    if (!baseline || !candidate) {
      return;
    }
    void this.router.navigate(['/compare', baseline.id, candidate.id], {
      queryParams: this.linkParams(),
    });
  }

  private async run(request: RecommendationQueryRequest, force = false): Promise<void> {
    const key = JSON.stringify(request);
    if (!force && key === this.lastKey) {
      return;
    }
    this.lastKey = key;
    this.lastRequest = request;
    const sequence = ++this.sequence;
    this.status.set('loading');
    this.problem.set(null);
    this.drawer.set(null);
    this.mode.set(request.ranking?.mode ?? 'VALUE');
    try {
      const response = await firstValueFrom(this.api.queryRecommendations(request));
      if (sequence !== this.sequence) {
        return;
      }
      this.currency.recordMeta(response.meta);
      this.session.applyPrincipal(response.meta.principal);
      this.response.set(response);
      this.status.set('ready');
      this.preselect(response);
    } catch (error: unknown) {
      if (sequence !== this.sequence) {
        return;
      }
      const problem = ApiProblem.from(error);
      if (problem.code === 'validation_failed' && problem.errors.length > 0) {
        this.lastKey = null;
        void this.router.navigate(['/'], {
          queryParams: this.linkParams(),
          state: { [FIELD_ERRORS_STATE]: problem.errors },
          replaceUrl: true,
        });
        return;
      }
      if (problem.code === 'quota_exceeded') {
        this.session.applyQuotaProblem(problem);
      }
      this.response.set(null);
      this.problem.set(problem);
      this.status.set('error');
    }
  }

  /** "Compare with…" from a product page preselects that product once it is listed. */
  private preselect(response: RecommendationQueryResponse): void {
    const id = this.params().get(PRESELECT_PARAM);
    const item = response.items.find((entry) => entry.productId === id);
    if (item && !this.selection().some((entry) => entry.id === item.productId)) {
      this.toggleSelect(item);
    }
  }
}
