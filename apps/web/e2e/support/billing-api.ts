import type { BrowserContext, Page, Route } from '@playwright/test';

import type { Me, PlanList } from '../../src/app/core/api/api-types';
import {
  FIXTURE_COUNTRIES,
  fixtureMe,
  fixtureTokenResponse,
  fixtureUser,
} from '../../src/app/testing/api-fixtures';
import { FX_NATIVE_TRY } from '../../src/app/features/results/testing/golden.fixtures';

/**
 * Mocked API and payment-provider pages for the checkout flow of `07-checkout.e2e.ts`. The API
 * answers are synthetic (the unit-test fixtures); the provider's hosted pages are stand-ins
 * served by the browser context, so no request ever reaches Stripe and no real session exists.
 * An API request without a mocked answer is answered 404 and recorded.
 */

/** Test-mode shaped URLs of the provider's hosted checkout and billing portal (synthetic). */
export const CHECKOUT_URL = 'https://checkout.stripe.com/c/pay/cs_test_e2eSyntheticCheckout01';
export const PORTAL_URL = 'https://billing.stripe.com/p/session/test_e2eSyntheticPortal01';

const STAND_IN_PAGE = (heading: string) =>
  `<!doctype html><html lang="en"><head><meta charset="utf-8"><title>${heading}</title></head>` +
  `<body><h1>${heading}</h1></body></html>`;

export interface BillingApiOptions {
  /** `Plan.checkoutAvailable` of PRO: a payment provider is configured. */
  readonly checkout: boolean;
  /** The signed-in user's plan and its source at the start. */
  readonly plan: 'FREE' | 'PRO_PROVIDER';
  readonly lang?: 'en' | 'tr';
}

export interface BillingApi {
  /** Requests that had no mocked answer (`METHOD /path`). */
  readonly unmocked: string[];
  /** `POST /billing/*` calls in order. */
  readonly billingCalls: string[];
  /** Provider pages the browser was sent to. */
  readonly providerVisits: string[];
  /** Makes `GET /me` report PRO paid through the provider after `calls` more `/me` answers. */
  landWebhookAfter(calls: number): void;
}

function proMe(base: Me): Me {
  return {
    ...base,
    plan: {
      code: 'PRO',
      name: 'Pro',
      savedProductsLimit: null,
      alertSubscriptionsLimit: null,
      notificationsPerMonthLimit: null,
    },
    subscription: {
      id: '6f7a8b9c-0d1e-4f2a-8b3c-4d5e6f7a8b9c',
      planCode: 'PRO',
      status: 'ACTIVE',
      source: 'PROVIDER',
      startedAt: '2026-11-25T10:00:00Z',
      currentPeriodEnd: '2026-12-25T10:00:00Z',
      canceledAt: null,
    },
    usage: {
      savedProducts: { used: 2, limit: null },
      alertSubscriptions: { used: 1, limit: null },
      notificationsThisMonth: { used: 4, limit: null, periodMonth: '2026-11-01' },
    },
  };
}

function plans(checkout: boolean): PlanList {
  return {
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
        checkoutAvailable: checkout,
      },
    ],
  };
}

/**
 * Answers every `/api/v1/**` request of `page` for a signed-in, verified user and serves the
 * provider's hosted pages of `context` as local stand-ins.
 */
export async function mockBillingApi(
  context: BrowserContext,
  page: Page,
  options: BillingApiOptions,
): Promise<BillingApi> {
  const lang = options.lang ?? 'en';
  const unmocked: string[] = [];
  const billingCalls: string[] = [];
  const providerVisits: string[] = [];
  const user = fixtureUser({ locale: lang });
  const free = fixtureMe({ ...user });
  let paid = options.plan === 'PRO_PROVIDER';
  let webhookIn: number | null = null;

  const standIn = (heading: string) => async (route: Route) => {
    providerVisits.push(route.request().url());
    await route.fulfill({ status: 200, contentType: 'text/html', body: STAND_IN_PAGE(heading) });
  };
  await context.route('https://checkout.stripe.com/**', standIn('Checkout stand-in'));
  await context.route('https://billing.stripe.com/**', standIn('Billing portal stand-in'));

  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace(/^\/api\/v1/, '');
    const op = `${request.method()} ${path}`;
    const json = (body: unknown, status = 200) =>
      route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    switch (op) {
      case 'POST /auth/refresh':
        return json(fixtureTokenResponse('checkout-e2e-token', user));
      case 'GET /countries':
        return json({ items: FIXTURE_COUNTRIES });
      case 'GET /fx/currencies':
        return json({ items: [{ code: 'TRY', asOf: '2026-11-24', stale: false }] });
      case 'GET /legal':
        return json({ privacyContact: 'privacy@example.com' });
      case 'GET /me/notifications/unread-count':
        return json({ unreadCount: 0 });
      case 'GET /plans':
        return json(plans(options.checkout));
      case 'GET /me':
        if (webhookIn !== null) {
          webhookIn -= 1;
          if (webhookIn <= 0) {
            paid = true;
            webhookIn = null;
          }
        }
        return json(paid ? proMe(free) : free);
      case 'POST /billing/checkout-session':
        billingCalls.push(op);
        return json({ url: CHECKOUT_URL });
      case 'POST /billing/portal-session':
        billingCalls.push(op);
        return json({ url: PORTAL_URL });
    }
    unmocked.push(op);
    return route.fulfill({
      status: 404,
      contentType: 'application/problem+json',
      body: JSON.stringify({ type: 'about:blank', title: 'Not Found', status: 404 }),
    });
  });

  return {
    unmocked,
    billingCalls,
    providerVisits,
    landWebhookAfter: (calls: number) => {
      webhookIn = calls;
    },
  };
}
