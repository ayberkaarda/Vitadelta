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
/** `CountryCode` of the contract. */
const COUNTRY_PATTERN = /^[A-Z]{2}$/;
/** `NutrientCode` of the contract. */
const CODE_PATTERN = /^[A-Z][A-Z0-9_]{0,39}$/;
/** Plain decimal as written by {@link serializeQuery} (no exponent, hex or infinity). */
const DECIMAL_PATTERN = /^-?\d{1,12}(\.\d{1,12})?$/;
const INTEGER_PATTERN = /^\d{1,10}$/;
/** Bounds of the contract (`Ranking.lambda`, `CategoryWeights`, `NutrientOverrides`, paging). */
export const QUERY_BOUNDS = {
  lambda: { min: 0, max: 1 },
  category: { min: 0, max: 2 },
  override: { min: 0, max: 3 },
  page: { min: 1, max: 2_147_483_647 },
  pageSize: { min: 1, max: 100 },
} as const;
/** `InputUnit`: IU is accepted only for vitamin D. */
const IU_NUTRIENT = 'VIT_D';

/** Registries a restored query is checked against; `null` when that registry is not loaded. */
export interface QueryRegistry {
  readonly countries: ReadonlySet<string> | null;
  readonly nutrients: ReadonlySet<string> | null;
}

const NO_REGISTRY: QueryRegistry = { countries: null, nutrients: null };

function formatNumber(value: number): string {
  return String(value);
}

function parseNumber(raw: string | undefined | null): number | null {
  if (raw === undefined || raw === null) {
    return null;
  }
  const text = raw.trim();
  if (!DECIMAL_PATTERN.test(text)) {
    return null;
  }
  const value = Number(text);
  return Number.isFinite(value) ? value : null;
}

function inRange(value: number | null, range: { readonly min: number; readonly max: number }) {
  return value !== null && value >= range.min && value <= range.max ? value : null;
}

function parseInteger(
  raw: string | null,
  fallback: number,
  range: { readonly min: number; readonly max: number },
): number {
  const text = raw?.trim() ?? '';
  if (!INTEGER_PATTERN.test(text)) {
    return fallback;
  }
  return inRange(Number(text), range) ?? fallback;
}

function knownCode(code: string, registry: ReadonlySet<string> | null): boolean {
  return CODE_PATTERN.test(code) && (registry === null || registry.has(code));
}

/**
 * One `req` entry: `CODE` (present only) or `CODE:amount:unit` with an amount above 0 and a
 * contract unit (IU only for vitamin D). Any other shape is dropped as a whole.
 */
function parseRequired(
  entry: string,
  nutrients: ReadonlySet<string> | null,
): RequiredNutrient | null {
  const parts = entry.split(':').map((part) => part.trim());
  const code = parts[0] ?? '';
  if (!knownCode(code, nutrients)) {
    return null;
  }
  if (parts.length === 1) {
    return { code };
  }
  if (parts.length !== 3) {
    return null;
  }
  const amount = parseNumber(parts[1]);
  const unit = INPUT_UNITS.find((candidate) => candidate === parts[2]) as InputUnit | undefined;
  if (amount === null || !(amount > 0) || !unit || (unit === 'IU' && code !== IU_NUTRIENT)) {
    return null;
  }
  return { code, minAmountPerDay: amount, unit };
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
 * Restores a query from URL parameters. Every part is checked against the contract (patterns,
 * numeric bounds, int32 paging, complete required-nutrient tuples) and, when given, against the
 * country and nutrient registries; an invalid part is dropped and its default applies.
 */
export function parseQuery(
  source: ParamMap | Params,
  registry: QueryRegistry = NO_REGISTRY,
): QueryState {
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
    const value = inRange(parseNumber(raw), QUERY_BOUNDS.category);
    if (category && value !== null) {
      categories[category] = value;
    }
  }

  const overrides: Record<string, number> = {};
  for (const [code, raw] of parsePairs(get(QUERY_PARAM.overrides))) {
    const value = inRange(parseNumber(raw), QUERY_BOUNDS.override);
    if (knownCode(code, registry.nutrients) && value !== null) {
      overrides[code] = value;
    }
  }

  const required: RequiredNutrient[] = [];
  const seen = new Set<string>();
  for (const entry of (get(QUERY_PARAM.required) ?? '').split(',')) {
    const parsed = parseRequired(entry, registry.nutrients);
    if (parsed && !seen.has(parsed.code)) {
      seen.add(parsed.code);
      required.push(parsed);
    }
  }

  const countryValid =
    country !== null &&
    COUNTRY_PATTERN.test(country) &&
    (registry.countries === null || registry.countries.has(country));
  return {
    country: countryValid ? country : null,
    stockFilter,
    mode,
    lambda:
      mode === 'CUSTOM' ? inRange(parseNumber(get(QUERY_PARAM.lambda)), QUERY_BOUNDS.lambda) : null,
    categories,
    overrides,
    required,
    excludeExceedingUl: get(QUERY_PARAM.ul) === 'exclude',
    page: parseInteger(get(QUERY_PARAM.page), DEFAULT_QUERY.page, QUERY_BOUNDS.page),
    pageSize: parseInteger(
      get(QUERY_PARAM.pageSize),
      DEFAULT_QUERY.pageSize,
      QUERY_BOUNDS.pageSize,
    ),
  };
}

/**
 * Whether `params` carries exactly the query parameters `serializeQuery(state)` writes, i.e. the
 * URL needs no normalisation. Parameters outside {@link QUERY_PARAM} are not compared.
 */
export function isNormalized(params: ParamMap | Params, state: QueryState): boolean {
  const map =
    'get' in params && typeof params.get === 'function'
      ? (params as ParamMap)
      : convertToParamMap(params);
  const expected = serializeQuery(state);
  return Object.values(QUERY_PARAM).every(
    (name) => (map.get(name) ?? undefined) === expected[name],
  );
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
   * Replaces the state with the one encoded in `params` and returns it. Values are validated
   * against the contract, the country registry (once `/countries` is loaded) and the nutrient
   * registry `nutrients` (when given); invalid parts are dropped. A registered country in the URL
   * (a shared results or comparison link) also becomes the visitor's selected country, so the
   * header, the native display currency and number formatting follow the market on screen.
   */
  restore(params: ParamMap | Params, nutrients: readonly string[] | null = null): QueryState {
    const countries = this.region.countries();
    const state = parseQuery(params, {
      countries: countries.length > 0 ? new Set(countries.map((country) => country.code)) : null,
      nutrients: nutrients && nutrients.length > 0 ? new Set(nutrients) : null,
    });
    this.stateSignal.set(state);
    if (
      state.country !== null &&
      countries.length > 0 &&
      state.country !== this.region.countryCode()
    ) {
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
