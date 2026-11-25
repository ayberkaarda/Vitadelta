import { Injectable, computed, inject, signal } from '@angular/core';
import { ParamMap, Params, convertToParamMap } from '@angular/router';

import {
  CompareRequest,
  INPUT_UNITS,
  InputUnit,
  NUTRIENT_CATEGORIES,
  NutrientCategory,
  RANKING_MODES,
  RankingMode,
  RecommendationQueryRequest,
  RequiredNutrient,
  STOCK_FILTERS,
  StockFilter,
} from '../api/api-types';
import { RegionStore } from './region.store';

/** Everything the query builder controls; the source of every ranking and compare request. */
export interface QueryState {
  readonly country: string | null;
  readonly stockFilter: StockFilter;
  readonly mode: RankingMode;
  /** CUSTOM mode only; `null` uses the server default. */
  readonly lambda: number | null;
  /** Only categories changed by the user; missing ones use the server default. */
  readonly categories: Readonly<Partial<Record<NutrientCategory, number>>>;
  readonly overrides: Readonly<Record<string, number>>;
  readonly required: readonly RequiredNutrient[];
  readonly excludeExceedingUl: boolean;
  readonly page: number;
  readonly pageSize: number;
}

export const DEFAULT_QUERY: QueryState = {
  country: null,
  stockFilter: 'IN_STOCK_ONLY',
  mode: 'VALUE',
  lambda: null,
  categories: {},
  overrides: {},
  required: [],
  excludeExceedingUl: false,
  page: 1,
  pageSize: 20,
};

/** URL query parameter names (stable: they appear in shared links). */
export const QUERY_PARAM = {
  country: 'country',
  stock: 'stock',
  mode: 'mode',
  lambda: 'lambda',
  categories: 'w',
  overrides: 'o',
  required: 'req',
  ul: 'ul',
  page: 'page',
  pageSize: 'size',
} as const;

const STOCK_TOKENS: Readonly<Record<StockFilter, string>> = {
  IN_STOCK_ONLY: 'in',
  INCLUDE_OUT_OF_STOCK: 'all',
};
const COUNTRY_PATTERN = /^[A-Z]{2}$/;
const CODE_PATTERN = /^[A-Z][A-Z0-9_]{0,63}$/;
const MAX_PAGE_SIZE = 100;

function formatNumber(value: number): string {
  return String(value);
}

function parseNumber(raw: string | undefined | null): number | null {
  if (raw === undefined || raw === null || raw.trim() === '') {
    return null;
  }
  const value = Number(raw);
  return Number.isFinite(value) ? value : null;
}

function parsePositiveInt(raw: string | null, fallback: number, max?: number): number {
  const value = parseNumber(raw);
  if (value === null || !Number.isInteger(value) || value < 1) {
    return fallback;
  }
  return max !== undefined ? Math.min(value, max) : value;
}

function parsePairs(raw: string | null): [string, string][] {
  if (!raw) {
    return [];
  }
  return raw
    .split(',')
    .map((entry) => entry.split(':'))
    .filter((parts): parts is [string, string] => parts.length === 2 && parts[0] !== '')
    .map(([key, value]) => [key.trim(), value.trim()]);
}

/** Serialises a query into URL parameters; defaults are omitted to keep links short. */
export function serializeQuery(state: QueryState): Record<string, string> {
  const params: Record<string, string> = {};
  if (state.country) {
    params[QUERY_PARAM.country] = state.country;
  }
  if (state.stockFilter !== DEFAULT_QUERY.stockFilter) {
    params[QUERY_PARAM.stock] = STOCK_TOKENS[state.stockFilter];
  }
  if (state.mode !== DEFAULT_QUERY.mode) {
    params[QUERY_PARAM.mode] = state.mode;
  }
  if (state.mode === 'CUSTOM' && state.lambda !== null) {
    params[QUERY_PARAM.lambda] = formatNumber(state.lambda);
  }
  const categories = NUTRIENT_CATEGORIES.flatMap((category) => {
    const value = state.categories[category];
    return value === undefined ? [] : [`${category}:${formatNumber(value)}`];
  });
  if (categories.length > 0) {
    params[QUERY_PARAM.categories] = categories.join(',');
  }
  const overrides = Object.keys(state.overrides)
    .sort()
    .map((code) => `${code}:${formatNumber(state.overrides[code])}`);
  if (overrides.length > 0) {
    params[QUERY_PARAM.overrides] = overrides.join(',');
  }
  if (state.required.length > 0) {
    params[QUERY_PARAM.required] = state.required
      .map((entry) =>
        entry.minAmountPerDay !== undefined && entry.unit !== undefined
          ? `${entry.code}:${formatNumber(entry.minAmountPerDay)}:${entry.unit}`
          : entry.code,
      )
      .join(',');
  }
  if (state.excludeExceedingUl) {
    params[QUERY_PARAM.ul] = 'exclude';
  }
  if (state.page !== DEFAULT_QUERY.page) {
    params[QUERY_PARAM.page] = String(state.page);
  }
  if (state.pageSize !== DEFAULT_QUERY.pageSize) {
    params[QUERY_PARAM.pageSize] = String(state.pageSize);
  }
  return params;
}

/**
 * Restores a query from URL parameters. Malformed parts are dropped (the default applies);
 * value ranges are left to the server, which answers `validation_failed` with field errors.
 */
export function parseQuery(source: ParamMap | Params): QueryState {
  const map =
    'get' in source && typeof source.get === 'function'
      ? (source as ParamMap)
      : convertToParamMap(source);
  const get = (name: string): string | null => map.get(name);

  const country = get(QUERY_PARAM.country);
  const stockToken = get(QUERY_PARAM.stock);
  const stockFilter =
    STOCK_FILTERS.find((filter) => STOCK_TOKENS[filter] === stockToken) ??
    DEFAULT_QUERY.stockFilter;
  const modeToken = get(QUERY_PARAM.mode);
  const mode = RANKING_MODES.find((entry) => entry === modeToken) ?? DEFAULT_QUERY.mode;

  const categories: Partial<Record<NutrientCategory, number>> = {};
  for (const [key, raw] of parsePairs(get(QUERY_PARAM.categories))) {
    const category = NUTRIENT_CATEGORIES.find((entry) => entry === key);
    const value = parseNumber(raw);
    if (category && value !== null) {
      categories[category] = value;
    }
  }

  const overrides: Record<string, number> = {};
  for (const [code, raw] of parsePairs(get(QUERY_PARAM.overrides))) {
    const value = parseNumber(raw);
    if (CODE_PATTERN.test(code) && value !== null) {
      overrides[code] = value;
    }
  }

  const required: RequiredNutrient[] = [];
  const seen = new Set<string>();
  for (const entry of (get(QUERY_PARAM.required) ?? '').split(',')) {
    const [code, amountRaw, unitRaw] = entry.split(':').map((part) => part.trim());
    if (!code || !CODE_PATTERN.test(code) || seen.has(code)) {
      continue;
    }
    seen.add(code);
    const amount = parseNumber(amountRaw);
    const unit = INPUT_UNITS.find((candidate) => candidate === unitRaw) as InputUnit | undefined;
    required.push(amount !== null && unit ? { code, minAmountPerDay: amount, unit } : { code });
  }

  return {
    country: country && COUNTRY_PATTERN.test(country) ? country : null,
    stockFilter,
    mode,
    lambda: mode === 'CUSTOM' ? parseNumber(get(QUERY_PARAM.lambda)) : null,
    categories,
    overrides,
    required,
    excludeExceedingUl: get(QUERY_PARAM.ul) === 'exclude',
    page: parsePositiveInt(get(QUERY_PARAM.page), DEFAULT_QUERY.page),
    pageSize: parsePositiveInt(get(QUERY_PARAM.pageSize), DEFAULT_QUERY.pageSize, MAX_PAGE_SIZE),
  };
}

function weightsOf(state: QueryState): RecommendationQueryRequest['weights'] {
  const hasCategories = Object.keys(state.categories).length > 0;
  const hasOverrides = Object.keys(state.overrides).length > 0;
  if (!hasCategories && !hasOverrides) {
    return undefined;
  }
  return {
    ...(hasCategories ? { categories: { ...state.categories } } : {}),
    ...(hasOverrides ? { overrides: { ...state.overrides } } : {}),
  };
}

/**
 * Query builder state shared by the query, results and compare pages, mirrored in the URL query
 * string so results and comparisons are shareable links (project specification §10.2).
 *
 * Pages call {@link restore} with the route's query parameters and navigate with
 * {@link queryParams} after a change; the store itself never touches the router.
 */
@Injectable({ providedIn: 'root' })
export class QueryStore {
  private readonly region = inject(RegionStore);
  private readonly stateSignal = signal<QueryState>(DEFAULT_QUERY);

  readonly state = this.stateSignal.asReadonly();
  /** Country of the query, else the visitor's selected country. */
  readonly country = computed(() => this.stateSignal().country ?? this.region.countryCode());
  /** URL query parameters of the current state (country included once known). */
  readonly queryParams = computed(() =>
    serializeQuery({ ...this.stateSignal(), country: this.country() }),
  );

  /**
   * Replaces the state with the one encoded in `params` and returns it. A country in the URL
   * (a shared results or comparison link) also becomes the visitor's selected country, so the
   * header, the native display currency and number formatting follow the market on screen.
   */
  restore(params: ParamMap | Params): QueryState {
    const state = parseQuery(params);
    this.stateSignal.set(state);
    if (state.country !== null && state.country !== this.region.countryCode()) {
      this.region.setCountry(state.country);
    }
    return state;
  }

  /** Merges a change; any change other than `page` goes back to page 1. */
  patch(change: Partial<QueryState>): void {
    this.stateSignal.update((state) => {
      const next = { ...state, ...change };
      if (change.page === undefined) {
        next.page = 1;
      }
      if (next.mode !== 'CUSTOM') {
        next.lambda = null;
      }
      return next;
    });
  }

  reset(): void {
    this.stateSignal.set(DEFAULT_QUERY);
  }

  /** Request body for `POST /recommendations/query`; `null` while no country is known. */
  toRequest(displayCurrency?: string): RecommendationQueryRequest | null {
    const state = this.stateSignal();
    const country = this.country();
    if (!country) {
      return null;
    }
    const request: RecommendationQueryRequest = {
      country,
      stockFilter: state.stockFilter,
      ranking:
        state.mode === 'CUSTOM' && state.lambda !== null
          ? { mode: state.mode, lambda: state.lambda }
          : { mode: state.mode },
      excludeExceedingUl: state.excludeExceedingUl,
      page: state.page,
      pageSize: state.pageSize,
    };
    const weights = weightsOf(state);
    if (weights) {
      request.weights = weights;
    }
    if (state.required.length > 0) {
      request.requiredNutrients = state.required.map((entry) => ({ ...entry }));
    }
    if (displayCurrency) {
      request.displayCurrency = displayCurrency;
    }
    return request;
  }

  /** Request body for `POST /recommendations/compare` with the same market and weights. */
  toCompareRequest(
    baselineProductId: string,
    candidateProductId: string,
    displayCurrency?: string,
  ): CompareRequest | null {
    const state = this.stateSignal();
    const country = this.country();
    if (!country) {
      return null;
    }
    const request: CompareRequest = {
      country,
      baselineProductId,
      candidateProductId,
      stockFilter: state.stockFilter,
    };
    const weights = weightsOf(state);
    if (weights) {
      request.weights = weights;
    }
    if (displayCurrency) {
      request.displayCurrency = displayCurrency;
    }
    return request;
  }
}
