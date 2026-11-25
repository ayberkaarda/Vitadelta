// Synthetic API payloads for the feature tests and the screenshot mocks. Brands, products,
// retailers and prices are fictional demo data; the GOLD-A / GOLD-B numbers follow the golden
// pair of the project specification (§1.1, §6.11) and the contract examples.

import {
  AlertSubscription,
  BreakdownRow,
  CompareResponse,
  DefaultWeights,
  NutrientList,
  PlanList,
  ProductDetailResponse,
  QueryMeta,
  RankedProduct,
  RecommendationQueryResponse,
} from '../../../core/api/api-types';

export const GOLD_A_ID = '3b2f0c4e-6a1d-4f0e-9c3a-1a2b3c4d5e01';
export const GOLD_B_ID = '3b2f0c4e-6a1d-4f0e-9c3a-1a2b3c4d5e02';

const SYNTHETIC = { synthetic: true, source: null, sourceRef: null, sourceEntryDate: null };

export const FX_NATIVE_TRY = {
  base: 'TRY',
  quote: 'TRY',
  rate: 1,
  asOf: null,
  source: null,
  stale: false,
  status: 'OK',
} as const;

const MG_ROW: BreakdownRow = {
  code: 'MAGNESIUM',
  category: 'MINERAL',
  amountPerDay: 100,
  ulAmountPerDay: 100,
  unit: 'mg',
  reference: 375,
  referenceSource: 'REGULATORY',
  coverageRatio: 0.2667,
  utility: 0.2667,
  weight: 1,
  contribution: 0.2667,
  cappedAtUl: false,
  cappedAtUMax: false,
  ulBelowReference: true,
  belowPresenceFloor: false,
};
const B12_ROW: BreakdownRow = {
  ...MG_ROW,
  code: 'VIT_B12',
  category: 'VITAMIN',
  amountPerDay: 2.5,
  ulAmountPerDay: 2.5,
  unit: 'mcg',
  reference: 2.5,
  coverageRatio: 1,
  utility: 1,
  contribution: 1,
  ulBelowReference: false,
};
const VIT_C_A: BreakdownRow = {
  ...B12_ROW,
  code: 'VIT_C',
  amountPerDay: 300,
  ulAmountPerDay: 300,
  unit: 'mg',
  reference: 80,
  coverageRatio: 3.75,
  utility: 1.4626,
  contribution: 1.4626,
};
const VIT_C_B: BreakdownRow = {
  ...VIT_C_A,
  amountPerDay: 350,
  ulAmountPerDay: 350,
  coverageRatio: 4.375,
  utility: 1.5,
  contribution: 1.5,
  cappedAtUMax: true,
};
const VIT_D_ROW: BreakdownRow = {
  ...B12_ROW,
  code: 'VIT_D',
  amountPerDay: 10,
  ulAmountPerDay: 10,
  unit: 'mcg',
  reference: 5,
  coverageRatio: 2,
  utility: 1.2426,
  contribution: 1.2426,
};
const ZINC_ROW: BreakdownRow = {
  ...B12_ROW,
  code: 'ZINC',
  category: 'MINERAL',
  amountPerDay: 10,
  ulAmountPerDay: 10,
  unit: 'mg',
  reference: 10,
  coverageRatio: 1,
  utility: 1,
  contribution: 1,
};

export const GOLD_A_BREAKDOWN: readonly BreakdownRow[] = [
  MG_ROW,
  B12_ROW,
  VIT_C_A,
  VIT_D_ROW,
  ZINC_ROW,
];
export const GOLD_B_BREAKDOWN: readonly BreakdownRow[] = [
  MG_ROW,
  B12_ROW,
  VIT_C_B,
  VIT_D_ROW,
  ZINC_ROW,
];

function top(rows: readonly BreakdownRow[]): BreakdownRow[] {
  return [...rows].sort((a, b) => b.contribution - a.contribution).slice(0, 5);
}

export function goldAlert(overrides: Partial<AlertSubscription> = {}): AlertSubscription {
  return {
    id: '5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d',
    productId: GOLD_A_ID,
    countryCode: 'TR',
    trigger: 'PRICE_DROP',
    thresholdPercent: 5,
    baselinePrice: 800,
    baselineCurrency: 'TRY',
    active: true,
    createdAt: '2026-11-24T08:30:00Z',
    lastTriggeredAt: null,
    ...overrides,
  };
}

export function goldA(overrides: Partial<RankedProduct> = {}): RankedProduct {
  return {
    productId: GOLD_A_ID,
    code: 'GOLD-A',
    rank: 1,
    brand: 'Fixture Labs',
    name: 'Gold Multi A',
    dosageForm: 'TABLET',
    provenance: SYNTHETIC,
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
    breakdown: [...GOLD_A_BREAKDOWN],
    topContributors: top(GOLD_A_BREAKDOWN),
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

export function goldB(overrides: Partial<RankedProduct> = {}): RankedProduct {
  return goldA({
    productId: GOLD_B_ID,
    code: 'GOLD-B',
    rank: 2,
    name: 'Gold Multi B',
    cost: {
      effectivePrice: 900,
      effectivePriceNative: 900,
      currency: 'TRY',
      servingsPerContainer: 60,
      daysSupply: 60,
      costPerDay: 15,
      costPerDayNative: 15,
      priceStale: false,
      outOfStock: false,
    },
    contentScore: 5.0093,
    contentScoreDisplay: 100,
    valueScore: 0.334,
    valueScoreDisplay: 89.5573,
    breakdown: [...GOLD_B_BREAKDOWN],
    topContributors: top(GOLD_B_BREAKDOWN),
    selectedOffer: {
      retailer: 'Demo Store',
      url: 'https://shop.example.com/products/gold-b',
      checkedAt: '2026-11-20T09:00:00Z',
    },
    ...overrides,
  });
}

/** A third synthetic product with an upper-limit flag and a stale price. */
export function zincForte(overrides: Partial<RankedProduct> = {}): RankedProduct {
  return goldA({
    productId: '3b2f0c4e-6a1d-4f0e-9c3a-1a2b3c4d5e07',
    code: 'MV-ZN40',
    rank: 3,
    brand: 'Meridian Vitals',
    name: 'Zinc Forte 60',
    cost: {
      effectivePrice: 420,
      effectivePriceNative: 420,
      currency: 'TRY',
      servingsPerContainer: 60,
      daysSupply: 60,
      costPerDay: 7,
      costPerDayNative: 7,
      priceStale: true,
      outOfStock: false,
    },
    contentScore: 1.3,
    contentScoreDisplay: 26,
    valueScore: 0.1857,
    valueScoreDisplay: 49.8,
    flags: [
      { code: 'EXCEEDS_UL', nutrientCodes: ['ZINC'] },
      { code: 'PRICE_STALE', nutrientCodes: [] },
      { code: 'MARKET_MEDIAN_REFERENCE_USED', nutrientCodes: ['COQ10'] },
    ],
    breakdown: [
      {
        ...ZINC_ROW,
        amountPerDay: 40,
        ulAmountPerDay: 40,
        coverageRatio: 2.5,
        utility: 1.3,
        contribution: 1.3,
        cappedAtUl: true,
      },
    ],
    topContributors: [],
    ...overrides,
  });
}

export function anonymousMeta(overrides: Partial<QueryMeta> = {}): QueryMeta {
  return {
    country: 'TR',
    nativeCurrency: 'TRY',
    currency: 'TRY',
    fx: FX_NATIVE_TRY,
    catalogVersion: 42,
    marketSize: 9,
    mRef: 2.77,
    excluded: { noStock: 1, noOffer: 0, requiredNutrients: 0, exceedsUl: 0 },
    visibility: { shown: 3, total: 8, locked: 5, reason: 'SIGN_UP_REQUIRED' },
    principal: { type: 'ANONYMOUS', queriesUsed: 2, queriesLimit: 3 },
    page: 1,
    pageSize: 20,
    total: 8,
    ...overrides,
  };
}

/** Anonymous ranking: three visible items, five locked positions (no data for them). */
export function anonymousResponse(): RecommendationQueryResponse {
  return {
    meta: anonymousMeta(),
    items: [goldA(), goldB(), zincForte()],
  };
}

/** Signed-in FREE user: GOLD-A saved with an active price-drop alert, nothing locked. */
export function userResponse(): RecommendationQueryResponse {
  return {
    meta: anonymousMeta({
      visibility: { shown: 3, total: 3, locked: 0, reason: null },
      principal: { type: 'USER', plan: 'FREE' },
      total: 3,
      marketSize: 4,
    }),
    items: [goldA({ saved: true, alerts: [goldAlert()] }), goldB(), zincForte()],
  };
}

export function emptyResponse(): RecommendationQueryResponse {
  return {
    meta: anonymousMeta({
      excluded: { noStock: 1, noOffer: 0, requiredNutrients: 5, exceedsUl: 2 },
      visibility: { shown: 0, total: 0, locked: 0, reason: null },
      total: 0,
      mRef: null,
    }),
    items: [],
  };
}

/** GT-1: GOLD-A vs GOLD-B, NOT_WORTH_IT (contract example). */
export function goldenCompare(overrides: Partial<CompareResponse> = {}): CompareResponse {
  const a = goldA();
  const b = goldB();
  return {
    meta: {
      country: 'TR',
      nativeCurrency: 'TRY',
      currency: 'TRY',
      fx: FX_NATIVE_TRY,
      catalogVersion: 42,
      marketSize: 2,
    },
    swapped: false,
    verdict: 'NOT_WORTH_IT',
    productA: {
      productId: a.productId,
      code: a.code,
      brand: a.brand,
      name: a.name,
      dosageForm: a.dosageForm,
      provenance: a.provenance,
      cost: a.cost,
      contentScore: a.contentScore,
      valueScore: a.valueScore,
      flags: [],
      selectedOffer: a.selectedOffer,
    },
    productB: {
      productId: b.productId,
      code: b.code,
      brand: b.brand,
      name: b.name,
      dosageForm: b.dosageForm,
      provenance: b.provenance,
      cost: b.cost,
      contentScore: b.contentScore,
      valueScore: b.valueScore,
      flags: [],
      selectedOffer: b.selectedOffer,
    },
    deltaCost: 1.6667,
    deltaCostNative: 1.6667,
    deltaContent: 0.0374,
    marginalRate: 44.5806,
    mRef: 2.6817,
    ratio: 16.6237,
    rows: [
      {
        code: 'MAGNESIUM',
        category: 'MINERAL',
        amountA: 100,
        amountB: 100,
        deltaAmount: 0,
        unit: 'mg',
        rA: 0.2667,
        rB: 0.2667,
        uA: 0.2667,
        uB: 0.2667,
        weight: 1,
        deltaContribution: 0,
        flags: [],
      },
      {
        code: 'VIT_B12',
        category: 'VITAMIN',
        amountA: 2.5,
        amountB: 2.5,
        deltaAmount: 0,
        unit: 'mcg',
        rA: 1,
        rB: 1,
        uA: 1,
        uB: 1,
        weight: 1,
        deltaContribution: 0,
        flags: [],
      },
      {
        code: 'VIT_C',
        category: 'VITAMIN',
        amountA: 300,
        amountB: 350,
        deltaAmount: 50,
        unit: 'mg',
        rA: 3.75,
        rB: 4.375,
        uA: 1.4626,
        uB: 1.5,
        weight: 1,
        deltaContribution: 0.0374,
        flags: ['DIMINISHING', 'ABOVE_CEILING_NO_CREDIT'],
      },
      {
        code: 'VIT_D',
        category: 'VITAMIN',
        amountA: 10,
        amountB: 10,
        deltaAmount: 0,
        unit: 'mcg',
        rA: 2,
        rB: 2,
        uA: 1.2426,
        uB: 1.2426,
        weight: 1,
        deltaContribution: 0,
        flags: [],
      },
      {
        code: 'ZINC',
        category: 'MINERAL',
        amountA: 10,
        amountB: 10,
        deltaAmount: 0,
        unit: 'mg',
        rA: 1,
        rB: 1,
        uA: 1,
        uB: 1,
        weight: 1,
        deltaContribution: 0,
        flags: [],
      },
    ],
    explanation: {
      key: 'explain.verdict.not_worth_it',
      params: {
        currency: 'TRY',
        deltaCostPerDay: 1.6667,
        deltaContent: 0.0374,
        marginalRate: 44.5806,
        mRef: 2.6817,
        ratio: 16.6237,
      },
    },
    ...overrides,
  };
}

/** `INSUFFICIENT_DATA`: A has no content with these weights, so there is no market rate. */
export function insufficientCompare(): CompareResponse {
  const golden = goldenCompare();
  return goldenCompare({
    verdict: 'INSUFFICIENT_DATA',
    productA: { ...golden.productA, contentScore: 0, valueScore: 0 },
    deltaContent: 1,
    marginalRate: null,
    mRef: null,
    ratio: null,
    explanation: {
      key: 'explain.verdict.insufficient_data',
      params: { currency: 'TRY', deltaCostPerDay: 1.6667, deltaContent: 1 },
    },
  });
}

export const FIXTURE_NUTRIENTS: NutrientList = {
  items: [
    {
      code: 'VIT_A',
      nameEn: 'Vitamin A',
      category: 'VITAMIN',
      canonicalUnit: 'mcg',
      presenceFloorPerDay: null,
      sortOrder: 10,
      referenceSetCodes: ['EU_NRV'],
      forms: [],
    },
    {
      code: 'VIT_B12',
      nameEn: 'Vitamin B12',
      category: 'VITAMIN',
      canonicalUnit: 'mcg',
      presenceFloorPerDay: null,
      sortOrder: 20,
      referenceSetCodes: ['EU_NRV'],
      forms: [],
    },
    {
      code: 'VIT_C',
      nameEn: 'Vitamin C',
      category: 'VITAMIN',
      canonicalUnit: 'mg',
      presenceFloorPerDay: null,
      sortOrder: 30,
      referenceSetCodes: ['EU_NRV'],
      forms: [],
    },
    {
      code: 'VIT_D',
      nameEn: 'Vitamin D',
      category: 'VITAMIN',
      canonicalUnit: 'mcg',
      presenceFloorPerDay: null,
      sortOrder: 40,
      referenceSetCodes: ['EU_NRV'],
      forms: [{ code: 'D3_CHOLECALCIFEROL', nameEn: 'Vitamin D3 (cholecalciferol)' }],
    },
    {
      code: 'MAGNESIUM',
      nameEn: 'Magnesium',
      category: 'MINERAL',
      canonicalUnit: 'mg',
      presenceFloorPerDay: null,
      sortOrder: 50,
      referenceSetCodes: ['EU_NRV'],
      forms: [],
    },
    {
      code: 'ZINC',
      nameEn: 'Zinc',
      category: 'MINERAL',
      canonicalUnit: 'mg',
      presenceFloorPerDay: null,
      sortOrder: 60,
      referenceSetCodes: ['EU_NRV'],
      forms: [],
    },
    {
      code: 'COQ10',
      nameEn: 'Coenzyme Q10',
      category: 'COENZYME',
      canonicalUnit: 'mg',
      presenceFloorPerDay: 30,
      sortOrder: 70,
      referenceSetCodes: [],
      forms: [],
    },
  ],
};

export const FIXTURE_DEFAULTS: DefaultWeights = {
  // The generated `DefaultCategoryWeights` type intersects with `Record<string, never>`
  // (allOf with a property-less object), so the literal needs a cast.
  categories: { VITAMIN: 1, MINERAL: 1, COENZYME: 0.8, OTHER: 0.5 },
  categoryRange: { min: 0, max: 2 },
  overrideDefault: 1,
  overrideRange: { min: 0, max: 3 },
  lambdaDefault: 0.5,
  lambdaRange: { min: 0, max: 1 },
  engine: {
    diminishingBeta: 0.35,
    utilityCeiling: 1.5,
    verdictWorthItMaxRatio: 1,
    verdictMarginalMaxRatio: 1.5,
  },
};

export const FIXTURE_PLANS: PlanList = {
  meta: { nativeCurrency: 'TRY', currency: 'TRY', fx: FX_NATIVE_TRY },
  items: [
    {
      code: 'FREE',
      name: 'Free',
      savedProductsLimit: 5,
      alertSubscriptionsLimit: 3,
      notificationsPerMonthLimit: 30,
      price: null,
      checkoutAvailable: false,
    },
    {
      code: 'PRO',
      name: 'Pro',
      savedProductsLimit: null,
      alertSubscriptionsLimit: null,
      notificationsPerMonthLimit: null,
      price: {
        amount: 199,
        amountNative: 199,
        currency: 'TRY',
        nativeCurrency: 'TRY',
        billingPeriod: 'MONTHLY',
      },
      checkoutAvailable: false,
    },
  ],
};

/** Product detail of GOLD-A in TR (two demo offers, one out of stock and stale). */
export function goldADetail(): ProductDetailResponse {
  return {
    meta: { country: 'TR', nativeCurrency: 'TRY', currency: 'TRY', fx: FX_NATIVE_TRY },
    product: {
      id: GOLD_A_ID,
      code: 'GOLD-A',
      brand: 'Fixture Labs',
      name: 'Gold Multi A',
      dosageForm: 'TABLET',
      unitsPerContainer: 60,
      unitsPerServing: 1,
      servingsPerDay: 1,
      gtin: null,
      synthetic: true,
      provenance: SYNTHETIC,
      availability: { status: 'AVAILABLE', note: null },
      nutrients: [
        {
          code: 'MAGNESIUM',
          category: 'MINERAL',
          amountPerServing: 100,
          amountPerDay: 100,
          unit: 'mg',
          ulAmountPerServing: null,
          formCode: 'UNKNOWN',
          formLabel: 'magnesium citrate',
          conversionAssumed: false,
        },
        {
          code: 'VIT_B12',
          category: 'VITAMIN',
          amountPerServing: 2.5,
          amountPerDay: 2.5,
          unit: 'mcg',
          ulAmountPerServing: null,
          formCode: 'UNKNOWN',
          formLabel: 'methylcobalamin',
          conversionAssumed: false,
        },
        {
          code: 'VIT_C',
          category: 'VITAMIN',
          amountPerServing: 300,
          amountPerDay: 300,
          unit: 'mg',
          ulAmountPerServing: null,
          formCode: 'UNKNOWN',
          formLabel: 'ascorbic acid',
          conversionAssumed: false,
        },
        {
          code: 'VIT_D',
          category: 'VITAMIN',
          amountPerServing: 10,
          amountPerDay: 10,
          unit: 'mcg',
          ulAmountPerServing: null,
          formCode: 'D3_CHOLECALCIFEROL',
          formLabel: 'D3, label 400 IU',
          conversionAssumed: false,
        },
        {
          code: 'ZINC',
          category: 'MINERAL',
          amountPerServing: 10,
          amountPerDay: 10,
          unit: 'mg',
          ulAmountPerServing: null,
          formCode: 'UNKNOWN',
          formLabel: null,
          conversionAssumed: false,
        },
      ],
      offers: [
        {
          retailer: 'Demo Store',
          price: 800,
          priceNative: 800,
          currency: 'TRY',
          inStock: true,
          url: 'https://shop.example.com/products/gold-a',
          checkedAt: '2026-11-20T09:00:00Z',
          priceStale: false,
        },
        {
          retailer: 'Demo Market',
          price: 845,
          priceNative: 845,
          currency: 'TRY',
          inStock: false,
          url: 'https://market.example.com/gold-a',
          checkedAt: '2026-10-12T09:00:00Z',
          priceStale: true,
        },
      ],
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
      selectedOffer: {
        retailer: 'Demo Store',
        url: 'https://shop.example.com/products/gold-a',
        checkedAt: '2026-11-20T09:00:00Z',
      },
      flags: [],
      saved: false,
      alerts: [],
    },
  };
}
