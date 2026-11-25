import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
} from '@angular/core';
import { Location } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';

import {
  NUTRIENT_CATEGORIES,
  NutrientCategory,
  NutrientOverrides,
  RANKING_MODES,
  RankingMode,
  STOCK_FILTERS,
  StockFilter,
} from '../../core/api/api-types';
import { SessionStore } from '../../core/auth/session.store';
import { Formatter } from '../../core/format/formatter';
import { QueryState, QueryStore } from '../../core/state/query.store';
import { RegionStore } from '../../core/state/region.store';
import { ErrorSummaryComponent } from '../../shared/form/error-summary.component';
import { FieldErrorEntry } from '../../shared/form/field-errors';
import { IconComponent } from '../../shared/icon/icon.component';
import { FIELD_ERRORS_STATE } from '../results/shared/navigation-state';
import { ReferenceDataStore, setLabel } from '../results/shared/reference-data.store';
import { OverrideChipsComponent } from './components/override-chips.component';
import {
  RequiredNutrientsEditorComponent,
  RequiredRow,
} from './components/required-nutrients-editor.component';
import { ValueSliderComponent } from './components/value-slider.component';
import {
  CONTROL,
  readFieldErrors,
  rowsFromRequired,
  serverEntries,
  toRequired,
  validateRows,
} from './query-form';

/**
 * Query builder (project specification §10.2; design hand-off §4.2). The form starts from the
 * query in the URL (so "Edit query" from the results keeps every setting), validates the parts
 * the contract states for required nutrients, and on submit stores the query in `QueryStore`
 * and navigates to `/results` with it in the URL. Server field errors (`validation_failed`)
 * come back from the results page through the navigation state and are shown next to their
 * controls and in the error summary.
 */
@Component({
  selector: 'vd-query-page',
  imports: [
    ErrorSummaryComponent,
    IconComponent,
    OverrideChipsComponent,
    RequiredNutrientsEditorComponent,
    RouterLink,
    TranslocoPipe,
    ValueSliderComponent,
  ],
  templateUrl: './query-page.component.html',
  styleUrl: './query-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class QueryPageComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly location = inject(Location);
  private readonly store = inject(QueryStore);
  protected readonly region = inject(RegionStore);
  protected readonly session = inject(SessionStore);
  protected readonly reference = inject(ReferenceDataStore);
  protected readonly format = inject(Formatter);

  protected readonly control = CONTROL;
  protected readonly modes = RANKING_MODES;
  protected readonly stockFilters = STOCK_FILTERS;
  protected readonly categoryList = NUTRIENT_CATEGORIES;
  /** Weights card starts collapsed on phones (design hand-off §4.2 "Mobile"). */
  protected readonly weightsOpen =
    typeof matchMedia !== 'function' || !matchMedia('(max-width: 48rem)').matches;

  protected readonly country = signal<string | null>(null);
  protected readonly stockFilter = signal<StockFilter>('IN_STOCK_ONLY');
  protected readonly mode = signal<RankingMode>('VALUE');
  protected readonly lambda = signal<number | null>(null);
  protected readonly categories = signal<Partial<Record<NutrientCategory, number>>>({});
  protected readonly overrides = signal<NutrientOverrides>({});
  protected readonly rows = signal<readonly RequiredRow[]>([]);
  protected readonly excludeUl = signal(false);
  /** Error summary of the last submit (or of the server answer); kept until the next submit. */
  protected readonly summaryErrors = signal<readonly FieldErrorEntry[]>([]);
  /** Error per control id; updated while the user fixes the fields. */
  protected readonly errors = signal<ReadonlyMap<string, string>>(new Map());
  protected readonly countryCode = computed(() => this.country() ?? this.region.countryCode());
  protected readonly countryInfo = computed(
    () => this.region.countries().find((entry) => entry.code === this.countryCode()) ?? null,
  );
  protected readonly defaults = this.reference.defaults;
  protected readonly lambdaValue = computed(
    () => this.lambda() ?? this.defaults()?.lambdaDefault ?? null,
  );

  protected readonly quota = computed(() => {
    const quota = this.session.status() === 'anonymous' ? this.session.anonymousQuota() : null;
    return quota ? { ...quota, left: Math.max(0, quota.queriesLimit - quota.queriesUsed) } : null;
  });

  protected readonly weightsSummary = computed(() => {
    const changed = NUTRIENT_CATEGORIES.flatMap((category) => {
      const value = this.categories()[category];
      return value === undefined ? [] : [{ category, value: this.format.amount(value, 1) }];
    });
    return { changed, overrides: Object.keys(this.overrides()).length };
  });

  constructor() {
    void this.reference.load();
    const subscription = this.route.queryParamMap.subscribe((params) =>
      this.fill(this.store.restore(params)),
    );
    inject(DestroyRef).onDestroy(() => subscription.unsubscribe());
    const serverErrors = readFieldErrors(
      this.router.currentNavigation()?.extras.state ?? this.location.getState(),
      FIELD_ERRORS_STATE,
    );
    if (serverErrors.length > 0) {
      this.showErrors(serverEntries(serverErrors, this.mode()));
    }
  }

  protected categoryValue(category: NutrientCategory): number {
    const own = this.categories()[category];
    if (own !== undefined) {
      return own;
    }
    const defaults = this.defaults();
    return defaults ? defaults.categories[category] : 0;
  }

  protected setCategory(category: NutrientCategory, value: number): void {
    this.categories.set({ ...this.categories(), [category]: value });
    this.clearError(CONTROL.category(category));
    this.clearError(CONTROL.weightsReset);
  }

  /** The market chosen in the form is also the visitor's country (header, currency, formats). */
  protected selectCountry(code: string): void {
    this.country.set(code);
    this.region.setCountry(code);
  }

  protected setOverrides(value: NutrientOverrides): void {
    this.overrides.set(value);
    this.clearError(CONTROL.weightsReset);
  }

  protected resetWeights(): void {
    this.categories.set({});
    this.overrides.set({});
    this.clearError(CONTROL.weightsReset);
  }

  protected setRows(rows: RequiredRow[]): void {
    this.rows.set(rows);
    const current = [...this.errors()];
    const others = current.filter(([id]) => !id.startsWith('q-req-'));
    if (others.length !== current.length) {
      this.errors.set(
        new Map([
          ...others,
          ...validateRows(rows).map((e): [string, string] => [e.controlId, e.messageKey]),
        ]),
      );
    }
  }

  protected submit(event: Event): void {
    event.preventDefault();
    const entries = validateRows(this.rows());
    this.showErrors(entries);
    if (entries.length > 0) {
      return;
    }
    const mode = this.mode();
    this.store.patch({
      country: this.countryCode(),
      stockFilter: this.stockFilter(),
      mode,
      lambda: mode === 'CUSTOM' ? this.lambda() : null,
      categories: { ...this.categories() },
      overrides: { ...this.overrides() },
      required: toRequired(this.rows()),
      excludeExceedingUl: this.excludeUl(),
    });
    void this.router.navigate(['/results'], { queryParams: this.store.queryParams() });
  }

  protected setLabel = setLabel;

  private fill(state: QueryState): void {
    this.country.set(state.country);
    this.stockFilter.set(state.stockFilter);
    this.mode.set(state.mode);
    this.lambda.set(state.lambda);
    this.categories.set({ ...state.categories });
    this.overrides.set({ ...state.overrides });
    this.rows.set(rowsFromRequired(state.required));
    this.excludeUl.set(state.excludeExceedingUl);
  }

  private showErrors(entries: readonly FieldErrorEntry[]): void {
    this.summaryErrors.set(entries);
    this.errors.set(new Map(entries.map((entry) => [entry.controlId, entry.messageKey])));
  }

  private clearError(controlId: string): void {
    if (this.errors().has(controlId)) {
      const next = new Map(this.errors());
      next.delete(controlId);
      this.errors.set(next);
    }
  }
}
