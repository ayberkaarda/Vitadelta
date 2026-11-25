import type { Page } from '@playwright/test';

import type {
  AdminUserPage,
  AlertListItem,
  CompareResponse,
  ImportJob,
  ImportJobDetail,
  NotificationPage,
  NutrientList,
  ProductDetailResponse,
  PromoCodeList,
  RecommendationQueryResponse,
  SavedProductList,
} from '../../src/app/core/api/api-types';
import {
  FIXTURE_COUNTRIES,
  fixtureMe,
  fixtureTokenResponse,
  fixtureUser,
} from '../../src/app/testing/api-fixtures';
import {
  FIXTURE_DEFAULTS,
  FX_NATIVE_TRY,
  GOLD_A_ID,
  GOLD_B_ID,
  goldA,
  goldAlert,
  goldB,
  goldenCompare,
  zincForte,
} from '../../src/app/features/results/testing/golden.fixtures';
import {
  PRODUCT_NUTRIENTS,
  productDetail,
} from '../../src/app/features/product/testing/product.fixtures';

/**
 * Mocked API answers whose catalog, account and server text is one long unbroken token, for the
 * layout checks of `06-long-text.e2e.ts`. Everything is synthetic: the golden-pair fixtures of
 * the unit tests with their brand, product, retailer, nutrient, notification, e-mail and file
 * texts replaced. No request reaches the API; an unexpected call is answered 404 and recorded.
 */

/** 60 characters without a single break opportunity. */
export const LONG_TOKEN = 'Longunbrokentoken'.repeat(4).slice(0, 60);
/** The longest product code the contract allows (40 characters, no hyphen). */
export const LONG_CODE = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ABCD';
/** The longest promo code the contract allows (16-character prefix, hyphen, 16 characters). */
export const LONG_PROMO = 'LONGPROMOPREFIX0-7K3M9QXT2B4N6PRW';
export const LONG_EMAIL = `${LONG_TOKEN.toLowerCase()}@example.com`;
export const LONG_FILE = `${LONG_TOKEN}.csv`;

const IMPORT_JOB_ID = '6c5d4e3f-2a1b-4c0d-9e8f-7a6b5c4d3e2f';

const long = { brand: LONG_TOKEN, name: LONG_TOKEN } as const;
const longOffer = {
  retailer: LONG_TOKEN,
  url: 'https://shop.example.com/products/long',
  checkedAt: '2026-11-20T09:00:00Z',
};

function ranking(): RecommendationQueryResponse {
  const a = goldA({ ...long, code: LONG_CODE, selectedOffer: longOffer });
  return {
    meta: {
      country: 'TR',
      nativeCurrency: 'TRY',
      currency: 'TRY',
      fx: FX_NATIVE_TRY,
      catalogVersion: 42,
      marketSize: 3,
      mRef: 2.77,
      excluded: { noStock: 0, noOffer: 0, requiredNutrients: 0, exceedsUl: 0 },
      visibility: { shown: 3, total: 3, locked: 0, reason: null },
      principal: { type: 'USER', plan: 'FREE' },
      page: 1,
      pageSize: 20,
      total: 3,
    },
    items: [a, goldB({ brand: LONG_TOKEN }), zincForte({ name: LONG_TOKEN })],
  };
}

function comparison(): CompareResponse {
  const golden = goldenCompare();
  return goldenCompare({
    productA: { ...golden.productA, ...long, code: LONG_CODE, selectedOffer: longOffer },
    productB: { ...golden.productB, ...long, selectedOffer: longOffer },
  });
}

function detail(): ProductDetailResponse {
  const base = productDetail();
  const [first, ...rest] = base.product.nutrients;
  return {
    ...base,
    product: {
      ...base.product,
      ...long,
      code: LONG_CODE,
      nutrients: [{ ...first, formLabel: LONG_TOKEN }, ...rest],
      offers: base.product.offers.map((offer) => ({ ...offer, retailer: LONG_TOKEN })),
      selectedOffer: longOffer,
    },
  };
}

function nutrients(): NutrientList {
  return {
    items: PRODUCT_NUTRIENTS.items.map((item, index) =>
      index === 0 ? { ...item, nameEn: LONG_TOKEN } : item,
    ),
  };
}

function saved(): SavedProductList {
  const a = goldA();
  return {
    meta: { country: 'TR', nativeCurrency: 'TRY', currency: 'TRY', fx: FX_NATIVE_TRY },
    items: [
      {
        productId: GOLD_A_ID,
        code: LONG_CODE,
        ...long,
        dosageForm: a.dosageForm,
        provenance: a.provenance,
        countryCode: 'TR',
        savedAt: '2026-11-24T08:30:00Z',
        availabilityStatus: 'AVAILABLE',
        cost: a.cost,
        selectedOffer: longOffer,
        flags: [],
        alerts: [goldAlert()],
      },
    ],
  };
}

function alerts(): { items: AlertListItem[] } {
  const a = goldA();
  return {
    items: [
      {
        ...goldAlert(),
        product: {
          id: GOLD_A_ID,
          code: LONG_CODE,
          ...long,
          availabilityStatus: 'AVAILABLE',
          provenance: a.provenance,
        },
      },
    ],
  };
}

function notifications(): NotificationPage {
  const state = (price: number) => ({
    effectivePrice: price,
    inStock: true,
    retailer: LONG_TOKEN,
    url: 'https://shop.example.com/products/long',
  });
  return {
    meta: { page: 1, pageSize: 10, total: 1 },
    items: [
      {
        id: '8e7d6c5b-4a39-4281-9b0c-1d2e3f4a5b6c',
        type: 'ALERT_PRICE_DROP',
        createdAt: '2026-11-25T10:00:00Z',
        readAt: null,
        payload: {
          subscriptionId: goldAlert().id,
          trigger: 'PRICE_DROP',
          productId: GOLD_A_ID,
          productCode: LONG_CODE,
          brand: LONG_TOKEN,
          productName: LONG_TOKEN,
          countryCode: 'TR',
          currency: 'TRY',
          thresholdPercent: 5,
          baselinePrice: 800,
          before: state(800),
          after: state(720),
        },
      },
    ],
  };
}

function users(): AdminUserPage {
  return {
    meta: { page: 1, pageSize: 20, total: 1 },
    items: [
      {
        id: fixtureUser().id,
        email: LONG_EMAIL,
        role: 'USER',
        status: 'ACTIVE',
        plan: 'FREE',
        emailVerified: true,
        createdAt: '2026-11-01T10:00:00Z',
      },
    ],
  };
}

function promoCodes(): PromoCodeList {
  return {
    items: [
      {
        code: LONG_PROMO,
        planCode: 'PRO',
        durationDays: 30,
        maxRedemptions: 100,
        redeemedCount: 3,
        expiresAt: '2026-12-31T23:59:59Z',
        createdBy: null,
      },
    ],
  };
}

function importJob(): ImportJob {
  return {
    id: IMPORT_JOB_ID,
    kind: 'OFFERS_CSV',
    status: 'RUNNING',
    createdAt: '2026-11-25T09:00:00Z',
    finishedAt: null,
    summary: null,
  };
}

function importDetail(): ImportJobDetail {
  return {
    ...importJob(),
    status: 'FAILED',
    finishedAt: '2026-11-25T09:00:02Z',
    summary: { rows: 2, inserted: 0, updated: 0, failed: 2 },
    errors: [
      {
        rowNumber: 2,
        code: 'unknown_product_code',
        message: `unknown_product_code: ${LONG_TOKEN} (row 2)`,
      },
      {
        rowNumber: 3,
        code: 'field_too_long',
        message: `field_too_long: ${LONG_TOKEN} (products[0].nutrients[0].${LONG_TOKEN})`,
      },
    ],
  };
}

export interface LongTextApi {
  /** Requests that had no mocked answer (`METHOD /path`). */
  readonly unmocked: string[];
}

/**
 * Answers every `/api/v1/**` request of `page`. `signedIn` starts the session as an
 * administrator with the long e-mail address (the refresh cookie exchange is mocked too).
 */
export async function mockLongTextApi(page: Page, signedIn: boolean): Promise<LongTextApi> {
  const unmocked: string[] = [];
  const me = fixtureMe({ email: LONG_EMAIL, role: 'ADMIN' });
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace(/^\/api\/v1/, '');
    const op = `${request.method()} ${path}`;
    const json = (body: unknown, status = 200) =>
      route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    switch (op) {
      case 'POST /auth/refresh':
        return signedIn
          ? json(
              fixtureTokenResponse(
                'long-text-token',
                fixtureUser({ email: LONG_EMAIL, role: 'ADMIN' }),
              ),
            )
          : route.fulfill({ status: 204 });
      case 'GET /session/quota':
        return json({ principal: 'ANONYMOUS', queriesUsed: 1, queriesLimit: 3, visibleResults: 3 });
      case 'GET /countries':
        return json({ items: FIXTURE_COUNTRIES });
      case 'GET /fx/currencies':
        return json({ items: [{ code: 'TRY', asOf: '2026-11-24', stale: false }] });
      case 'GET /nutrients':
        return json(nutrients());
      case 'GET /weights/defaults':
        return json(FIXTURE_DEFAULTS);
      case 'GET /legal':
        return json({ privacyContact: 'privacy@example.com' });
      case 'POST /recommendations/query':
        return json(ranking());
      case 'POST /recommendations/compare':
        return json(comparison());
      case `GET /products/${GOLD_A_ID}`:
      case `GET /products/${GOLD_B_ID}`:
        return json(detail());
      case 'GET /me':
        return json(me);
      case 'GET /me/notifications/unread-count':
        return json({ unreadCount: 1 });
      case 'GET /me/notifications':
        return json(notifications());
      case 'GET /me/saved-products':
        return json(saved());
      case 'GET /me/alerts':
        return json(alerts());
      case 'GET /admin/users':
        return json(users());
      case 'GET /admin/promo-codes':
        return json(promoCodes());
      case 'POST /admin/imports/offers':
        return json(importJob(), 202);
      case `GET /admin/imports/${IMPORT_JOB_ID}`:
        return json(importDetail());
    }
    unmocked.push(op);
    return route.fulfill({
      status: 404,
      contentType: 'application/problem+json',
      body: JSON.stringify({ type: 'about:blank', title: 'Not Found', status: 404 }),
    });
  });
  return { unmocked };
}
