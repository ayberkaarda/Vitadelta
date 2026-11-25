// Synthetic API payloads for unit tests. Brands, products, people and prices are fictional
// (demo data); amounts follow the golden pair of the project specification where noted.

import {
  AnonymousQuota,
  AuthTokenResponse,
  Country,
  FxMeta,
  Me,
  QueryMeta,
  RankedProduct,
  RecommendationQueryResponse,
  UserProfile,
} from '../core/api/api-types';

export const FIXTURE_COUNTRIES: readonly Country[] = [
  {
    code: 'TR',
    nameEn: 'Türkiye',
    currency: 'TRY',
    referenceSetCode: 'EU_NRV',
    ulSetCode: 'EFSA',
    defaultLocale: 'tr',
  },
  {
    code: 'DE',
    nameEn: 'Germany',
    currency: 'EUR',
    referenceSetCode: 'EU_NRV',
    ulSetCode: 'EFSA',
    defaultLocale: 'en',
  },
  {
    code: 'US',
    nameEn: 'United States',
    currency: 'USD',
    referenceSetCode: 'US_DV',
    ulSetCode: 'IOM',
    defaultLocale: 'en',
  },
];

/** Identity pair: native currency shown, no conversion. */
export const FX_IDENTITY_TRY: FxMeta = {
  base: 'TRY',
  quote: 'TRY',
  rate: 1,
  asOf: null,
  source: null,
  stale: false,
  status: 'OK',
};

/** Converted to EUR with a stale rate. */
export const FX_STALE_EUR: FxMeta = {
  base: 'TRY',
  quote: 'EUR',
  rate: 0.0225,
  asOf: '2026-11-17',
  source: 'FRANKFURTER',
  stale: true,
  status: 'OK',
};

/** No rate stored for the pair: amounts stay native. */
export const FX_UNAVAILABLE_GBP: FxMeta = {
  base: 'TRY',
  quote: 'GBP',
  rate: null,
  asOf: null,
  source: null,
  stale: false,
  status: 'UNAVAILABLE',
};

export function fixtureUser(overrides: Partial<UserProfile> = {}): UserProfile {
  return {
    id: '9f1c2d3e-4b5a-4c6d-8e7f-0a1b2c3d4e5f',
    email: 'user@example.com',
    emailVerified: true,
    role: 'USER',
    status: 'ACTIVE',
    locale: 'en',
    defaultCountryCode: 'TR',
    displayCurrency: null,
    emailAlertsEnabled: true,
    createdAt: '2026-11-01T10:00:00Z',
    ...overrides,
  };
}

export function fixtureTokenResponse(
  accessToken = 'access-token-1',
  user: UserProfile = fixtureUser(),
): AuthTokenResponse {
  return { accessToken, tokenType: 'Bearer', expiresIn: 900, user };
}

export function fixtureMe(overrides: Partial<Me> = {}): Me {
  return {
    ...fixtureUser(),
    plan: {
      code: 'FREE',
      name: 'Free',
      savedProductsLimit: 5,
      alertSubscriptionsLimit: 3,
      notificationsPerMonthLimit: 30,
    },
    subscription: {
      id: '1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d',
      planCode: 'FREE',
      status: 'ACTIVE',
      source: 'MANUAL',
      startedAt: '2026-11-01T10:00:00Z',
      currentPeriodEnd: null,
      canceledAt: null,
    },
    usage: {
      savedProducts: { used: 2, limit: 5 },
      alertSubscriptions: { used: 1, limit: 3 },
      notificationsThisMonth: { used: 4, limit: 30, periodMonth: '2026-11-01' },
    },
    ...overrides,
  };
}

export function fixtureAnonymousQuota(queriesUsed = 1): AnonymousQuota {
  return { principal: 'ANONYMOUS', queriesUsed, queriesLimit: 3, visibleResults: 3 };
}

/** First product of the synthetic golden pair (13.33 TRY per day). */
export function fixtureRankedProduct(overrides: Partial<RankedProduct> = {}): RankedProduct {
  return {
    productId: '3b2f0c4e-6a1d-4f0e-9c3a-1a2b3c4d5e01',
    code: 'GOLD-A',
    rank: 1,
    brand: 'Fixture Labs',
    name: 'Gold A Multivitamin',
    dosageForm: 'TABLET',
    provenance: { synthetic: true, source: null, sourceRef: null, sourceEntryDate: null },
    cost: {
      effectivePrice: 800,
      effectivePriceNative: 800,
      currency: 'TRY',
      servingsPerContainer: 60,
      daysSupply: 60,
      costPerDay: 13.3333,
      costPerDayNative: 13.3333,
      priceStale: false,
      outOfStock: false,
    },
    contentScore: 4.9719,
    contentScoreDisplay: 99.2537,
    valueScore: 0.3729,
    valueScoreDisplay: 100,
    customScore: null,
    flags: [],
    breakdown: [],
    topContributors: [],
    selectedOffer: {
      retailer: 'Demo Store',
      url: 'https://shop.example.com/products/gold-a',
      checkedAt: '2026-11-20T09:00:00Z',
    },
    saved: false,
    alerts: [],
    ...overrides,
  };
}

export function fixtureQueryMeta(overrides: Partial<QueryMeta> = {}): QueryMeta {
  return {
    country: 'TR',
    nativeCurrency: 'TRY',
    currency: 'TRY',
    fx: FX_IDENTITY_TRY,
    catalogVersion: 42,
    marketSize: 8,
    mRef: 2.68,
    excluded: { noStock: 1, noOffer: 0, requiredNutrients: 0, exceedsUl: 0 },
    visibility: { shown: 3, total: 8, locked: 5, reason: 'SIGN_UP_REQUIRED' },
    principal: { type: 'ANONYMOUS', queriesUsed: 1, queriesLimit: 3 },
    page: 1,
    pageSize: 20,
    total: 8,
    ...overrides,
  };
}

export function fixtureQueryResponse(
  overrides: Partial<RecommendationQueryResponse> = {},
): RecommendationQueryResponse {
  return {
    meta: fixtureQueryMeta(),
    items: [fixtureRankedProduct()],
    ...overrides,
  };
}
