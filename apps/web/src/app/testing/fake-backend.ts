// Stateful in-memory stand-in for the public API, used by the cross-feature flow tests. It
// serves the real translation files from `public/i18n` and answers the operations the flows
// need with synthetic data (fictional brands, demo prices). Every response is delivered
// asynchronously, like a real network call.

import {
  HttpBackend,
  HttpErrorResponse,
  HttpEvent,
  HttpHeaders,
  HttpRequest,
  HttpResponse,
} from '@angular/common/http';
import { Injectable } from '@angular/core';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { Observable } from 'rxjs';

import {
  AlertListItem,
  AlertSubscription,
  DefaultWeights,
  Me,
  Notification,
  PlanList,
  RankedProduct,
  RecommendationQueryResponse,
  SavedProduct,
} from '../core/api/api-types';
import {
  FIXTURE_COUNTRIES,
  FX_IDENTITY_TRY,
  FX_STALE_EUR,
  fixtureMe,
  fixtureQueryMeta,
  fixtureRankedProduct,
  fixtureTokenResponse,
  fixtureUser,
} from './api-fixtures';

const API = '/api/v1';
const I18N_ROOT = resolve(process.cwd(), 'public/i18n');
const EUR_RATE = 0.0225;

/** One recorded request (for assertions). */
export interface RecordedRequest {
  readonly method: string;
  readonly path: string;
  readonly query: Readonly<Record<string, string>>;
  readonly body: unknown;
  readonly authorization: string | null;
}

interface Account {
  readonly id: string;
  readonly email: string;
  password: string;
  role: 'USER' | 'ADMIN';
  verified: boolean;
  /** PRO paid through the payment provider (set by a simulated provider webhook). */
  providerPro: boolean;
  /** A completed provider checkout made this account a provider customer. */
  billingCustomer: boolean;
}

/** Synthetic hosted pages of the payment provider (test-mode shapes, never opened). */
export const FAKE_CHECKOUT_URL = 'https://checkout.stripe.com/c/pay/cs_test_flowSynthetic01';
export const FAKE_PORTAL_URL = 'https://billing.stripe.com/p/session/test_flowSynthetic01';

type Reply = readonly [status: number, body: unknown];

const PRODUCTS: readonly RankedProduct[] = (
  [
    ['01', 'GOLD-A', 'Fixture Labs', 'Gold Multi A', 13.3333, 800],
    ['02', 'GOLD-B', 'Fixture Labs', 'Gold Multi B', 15, 900],
    ['03', 'DAILY-60', 'Bosphorus Health', 'Daily Complete 60', 9, 540],
    ['04', 'CORE-90', 'Meridian Vitals', 'Core Multi 90', 13, 1170],
    ['05', 'ACTIVE-60', 'Northline Nutrition', 'Active Plus 60', 21, 1260],
    ['06', 'ONE-30', 'Redwood Daily', 'One Daily 30', 11.5, 345],
  ] as const
).map(([suffix, code, brand, name, perDay, price], index) =>
  fixtureRankedProduct({
    productId: `3b2f0c4e-6a1d-4f0e-9c3a-1a2b3c4d5e${suffix}`,
    code,
    brand,
    name,
    rank: index + 1,
    cost: {
      ...fixtureRankedProduct().cost,
      effectivePrice: price,
      effectivePriceNative: price,
      costPerDay: perDay,
      costPerDayNative: perDay,
    },
  }),
);

/** Anonymous visitors see this many rows of a ranking; the rest are locked. */
export const ANONYMOUS_VISIBLE = 3;

@Injectable()
export class FakeBackend implements HttpBackend {
  readonly requests: RecordedRequest[] = [];
  /** The address bar at the moment each request (API and translations) was sent. */
  readonly sentFrom: string[] = [];

  private readonly accounts = new Map<string, Account>();
  /** Access tokens the API accepts. */
  private readonly validTokens = new Set<string>();
  private tokenCounter = 0;
  /** Whether the browser holds a valid `vd_refresh` cookie (and for whom). */
  private refreshFor: Account | null = null;
  private refreshBroken = false;
  private anonymousUsed = 0;
  private verificationToken: string | null = null;
  private readonly saved = new Map<string, string>();
  private readonly alerts: AlertSubscription[] = [];
  private unread = 0;
  private billingProvider = false;

  /** Signs a user in on "page load" (a valid refresh cookie). */
  seedSession(role: 'USER' | 'ADMIN' = 'USER'): void {
    const account: Account = {
      id: role === 'ADMIN' ? 'admin-1' : fixtureUser().id,
      email: role === 'ADMIN' ? 'admin@example.com' : 'user@example.com',
      password: 'correct-horse-battery',
      role,
      verified: true,
      providerPro: false,
      billingCustomer: false,
    };
    this.accounts.set(account.email, account);
    this.refreshFor = account;
  }

  /** Makes every issued access token invalid (as after expiry). */
  expireAccessTokens(): void {
    this.validTokens.clear();
  }

  /** The refresh cookie is gone or revoked: `/auth/refresh` answers 401. */
  breakRefresh(): void {
    this.refreshBroken = true;
  }

  /** Configures the payment provider: `checkoutAvailable` and the billing routes. */
  enableBillingProvider(): void {
    this.billingProvider = true;
  }

  /**
   * Simulates the provider's signed webhook after a completed checkout: the signed-in account
   * becomes PRO with `source = PROVIDER` and a provider customer.
   */
  completeCheckout(): void {
    if (this.refreshFor) {
      this.refreshFor.providerPro = true;
      this.refreshFor.billingCustomer = true;
    }
  }

  /** Simulates a new notification created by the alert evaluator. */
  addUnread(count: number): void {
    this.unread += count;
  }

  /** The token of the last verification e-mail (the link `/auth/verify#token=…`). */
  lastVerificationToken(): string | null {
    return this.verificationToken;
  }

  count(method: string, path: string): number {
    return this.requests.filter((r) => r.method === method && r.path === path).length;
  }

  handle(request: HttpRequest<unknown>): Observable<HttpEvent<unknown>> {
    this.sentFrom.push(`${window.location.href} -> ${request.method} ${request.urlWithParams}`);
    return new Observable<HttpEvent<unknown>>((subscriber) => {
      const timer = setTimeout(() => {
        const url = new URL(request.urlWithParams, 'http://app.test/');
        const [status, body] = this.route(request, url);
        if (status >= 400) {
          subscriber.error(
            new HttpErrorResponse({
              status,
              error: body,
              url: url.pathname,
              headers: new HttpHeaders({ 'Content-Type': 'application/problem+json' }),
            }),
          );
          return;
        }
        subscriber.next(new HttpResponse({ status, body, url: url.pathname }));
        subscriber.complete();
      }, 0);
      return () => clearTimeout(timer);
    });
  }

  private route(request: HttpRequest<unknown>, url: URL): Reply {
    const path = url.pathname;
    if (path.startsWith('/i18n/')) {
      return [200, JSON.parse(readFileSync(resolve(I18N_ROOT, path.slice(6)), 'utf8'))];
    }
    const query = Object.fromEntries(url.searchParams.entries());
    const authorization = request.headers.get('Authorization');
    this.requests.push({
      method: request.method,
      path: path.slice(API.length),
      query,
      body: request.body,
      authorization,
    });
    const op = `${request.method} ${path.slice(API.length)}`;
    const body = (request.body ?? {}) as Record<string, unknown>;

    switch (op) {
      case 'GET /legal':
        return [200, { privacyContact: 'privacy@example.com' }];
      case 'GET /countries':
        return [200, { items: FIXTURE_COUNTRIES }];
      case 'GET /fx/currencies':
        return [200, { items: [{ code: 'EUR', asOf: '2026-11-17', stale: true }] }];
      case 'GET /nutrients':
        return [200, { items: [] }];
      case 'GET /weights/defaults':
        return [200, DEFAULTS];
      case 'GET /session/quota':
        return [200, this.anonymousQuota()];
      case 'GET /plans':
        return [200, this.plans()];
      case 'POST /auth/refresh':
        return this.refresh();
      case 'POST /auth/logout':
        this.refreshFor = null;
        return [204, null];
      case 'POST /auth/register':
        return this.register(body);
      case 'POST /auth/verify-email':
        return this.verify(body);
      case 'POST /auth/login':
        return this.login(body);
      case 'POST /recommendations/query':
        return this.query(body, authorization);
    }

    const account = this.authenticated(authorization);
    if (!account) {
      return problem('unauthorized', 401);
    }
    switch (op) {
      case 'GET /me':
        return [200, this.me(account)];
      case 'GET /me/notifications/unread-count':
        return [200, { unreadCount: this.unread }];
      case 'GET /me/notifications':
        return [200, this.notifications()];
      case 'POST /me/saved-products':
        this.saved.set(String(body['productId']), '2026-11-25T09:00:00Z');
        return [
          201,
          { productId: body['productId'], countryCode: 'TR', savedAt: '2026-11-25T09:00:00Z' },
        ];
      case 'GET /me/saved-products':
        return [200, this.savedList(query)];
      case 'POST /me/alerts':
        return this.createAlert(body);
      case 'GET /me/alerts':
        return [200, { items: this.alertList() }];
      case 'POST /billing/checkout-session':
        return this.checkout(account);
      case 'POST /billing/portal-session':
        return this.portal(account);
    }
    if (path.startsWith(`${API}/admin/`)) {
      return account.role === 'ADMIN' ? [200, { items: [] }] : problem('forbidden', 403);
    }
    return problem('product_not_found', 404);
  }

  private anonymousQuota() {
    return {
      principal: 'ANONYMOUS',
      queriesUsed: this.anonymousUsed,
      queriesLimit: 3,
      visibleResults: ANONYMOUS_VISIBLE,
    };
  }

  private issue(account: Account): Reply {
    const token = `access-${++this.tokenCounter}`;
    this.validTokens.add(token);
    const user = fixtureUser({ id: account.id, email: account.email, role: account.role });
    return [200, fixtureTokenResponse(token, user)];
  }

  private refresh(): Reply {
    if (this.refreshBroken || !this.refreshFor) {
      return problem('unauthorized', 401);
    }
    return this.issue(this.refreshFor);
  }

  private register(body: Record<string, unknown>): Reply {
    const email = String(body['email']);
    if (!this.accounts.has(email)) {
      this.accounts.set(email, {
        id: '7d6c5b4a-3928-4170-8a9b-0c1d2e3f4a5b',
        email,
        password: String(body['password']),
        role: 'USER',
        verified: false,
        providerPro: false,
        billingCustomer: false,
      });
      this.verificationToken = 'dmVyaWZ5LXN5bnRoZXRpYy1mbG93LXRva2Vu';
    }
    return [202, { messageKey: 'auth.register.check_email' }];
  }

  private verify(body: Record<string, unknown>): Reply {
    if (body['token'] !== this.verificationToken) {
      return problem('token_invalid', 400);
    }
    for (const account of this.accounts.values()) {
      account.verified = true;
    }
    return [204, null];
  }

  private login(body: Record<string, unknown>): Reply {
    const account = this.accounts.get(String(body['email']));
    if (!account || account.password !== body['password']) {
      return problem('invalid_credentials', 401);
    }
    this.refreshFor = account;
    this.refreshBroken = false;
    return this.issue(account);
  }

  private authenticated(authorization: string | null): Account | null {
    const token = authorization?.replace(/^Bearer /, '') ?? '';
    if (!this.validTokens.has(token)) {
      return null;
    }
    return this.refreshFor;
  }

  private me(account: Account): Me {
    const free = fixtureMe({
      ...fixtureUser({ id: account.id, email: account.email, role: account.role }),
      emailVerified: account.verified,
      usage: {
        savedProducts: { used: this.saved.size, limit: 5 },
        alertSubscriptions: { used: this.alerts.filter((a) => a.active).length, limit: 3 },
        notificationsThisMonth: { used: 0, limit: 30, periodMonth: '2026-11-01' },
      },
    });
    if (!account.providerPro) {
      return free;
    }
    return {
      ...free,
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
        savedProducts: { used: this.saved.size, limit: null },
        alertSubscriptions: { used: this.alerts.filter((a) => a.active).length, limit: null },
        notificationsThisMonth: { used: 0, limit: null, periodMonth: '2026-11-01' },
      },
    };
  }

  private plans(): PlanList {
    return {
      meta: { nativeCurrency: 'TRY', currency: 'TRY', fx: FX_IDENTITY_TRY },
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
          checkoutAvailable: this.billingProvider,
        },
      ],
    };
  }

  private checkout(account: Account): Reply {
    if (!this.billingProvider) {
      return problem('billing_unavailable', 503);
    }
    if (!account.verified) {
      return problem('email_not_verified', 403);
    }
    if (account.providerPro) {
      return problem('plan_already_active', 409);
    }
    return [200, { url: FAKE_CHECKOUT_URL }];
  }

  private portal(account: Account): Reply {
    if (!this.billingProvider) {
      return problem('billing_unavailable', 503);
    }
    if (!account.billingCustomer) {
      return problem('no_billing_account', 409);
    }
    return [200, { url: FAKE_PORTAL_URL }];
  }

  private query(body: Record<string, unknown>, authorization: string | null): Reply {
    const signedIn = authorization !== null && this.authenticated(authorization) !== null;
    if (authorization !== null && !signedIn) {
      return problem('unauthorized', 401);
    }
    const euro = body['displayCurrency'] === 'EUR';
    const rate = euro ? EUR_RATE : 1;
    const items = PRODUCTS.map((product) => ({
      ...product,
      cost: {
        ...product.cost,
        currency: euro ? 'EUR' : 'TRY',
        effectivePrice: round(product.cost.effectivePriceNative * rate),
        costPerDay: round(product.cost.costPerDayNative * rate),
      },
      saved: signedIn && this.saved.has(product.productId),
      alerts: signedIn ? this.alerts.filter((a) => a.productId === product.productId) : [],
    }));
    const meta = fixtureQueryMeta({
      currency: euro ? 'EUR' : 'TRY',
      fx: euro ? FX_STALE_EUR : FX_IDENTITY_TRY,
      mRef: round(2.77 * rate),
      marketSize: PRODUCTS.length,
      total: PRODUCTS.length,
    });
    if (signedIn) {
      const response: RecommendationQueryResponse = {
        meta: {
          ...meta,
          visibility: { shown: items.length, total: items.length, locked: 0, reason: null },
          principal: { type: 'USER', plan: 'FREE' },
        },
        items,
      };
      return [200, response];
    }
    this.anonymousUsed += 1;
    const response: RecommendationQueryResponse = {
      meta: {
        ...meta,
        visibility: {
          shown: ANONYMOUS_VISIBLE,
          total: items.length,
          locked: items.length - ANONYMOUS_VISIBLE,
          reason: 'SIGN_UP_REQUIRED',
        },
        principal: { type: 'ANONYMOUS', queriesUsed: this.anonymousUsed, queriesLimit: 3 },
      },
      items: items.slice(0, ANONYMOUS_VISIBLE),
    };
    return [200, response];
  }

  private createAlert(body: Record<string, unknown>): Reply {
    const productId = String(body['productId']);
    const alert: AlertSubscription = {
      id: `alert-${this.alerts.length + 1}`,
      productId,
      countryCode: 'TR',
      trigger: 'PRICE_DROP',
      thresholdPercent: 5,
      baselinePrice: PRODUCTS.find((p) => p.productId === productId)?.cost.effectivePrice ?? null,
      baselineCurrency: 'TRY',
      active: true,
      createdAt: '2026-11-25T09:05:00Z',
      lastTriggeredAt: null,
    };
    this.alerts.push(alert);
    // Creating an alert saves the product implicitly (project specification §8.7).
    this.saved.set(productId, '2026-11-25T09:05:00Z');
    return [201, alert];
  }

  private savedList(query: Readonly<Record<string, string>>) {
    const euro = query['displayCurrency'] === 'EUR';
    const items: SavedProduct[] = PRODUCTS.filter((p) => this.saved.has(p.productId)).map((p) => ({
      productId: p.productId,
      code: p.code,
      brand: p.brand,
      name: p.name,
      dosageForm: p.dosageForm,
      provenance: p.provenance,
      countryCode: 'TR',
      savedAt: this.saved.get(p.productId) ?? '',
      availabilityStatus: 'AVAILABLE',
      cost: {
        ...p.cost,
        currency: euro ? 'EUR' : 'TRY',
        costPerDay: round(p.cost.costPerDayNative * (euro ? EUR_RATE : 1)),
        effectivePrice: round(p.cost.effectivePriceNative * (euro ? EUR_RATE : 1)),
      },
      selectedOffer: p.selectedOffer,
      flags: [],
      alerts: this.alerts.filter((a) => a.productId === p.productId),
    }));
    return {
      meta: {
        country: 'TR',
        nativeCurrency: 'TRY',
        currency: euro ? 'EUR' : 'TRY',
        fx: euro ? FX_STALE_EUR : FX_IDENTITY_TRY,
      },
      items,
    };
  }

  private alertList(): AlertListItem[] {
    return this.alerts.map((alert) => {
      const product = PRODUCTS.find((p) => p.productId === alert.productId) ?? PRODUCTS[0];
      return {
        ...alert,
        product: {
          id: product.productId,
          code: product.code,
          brand: product.brand,
          name: product.name,
          availabilityStatus: 'AVAILABLE',
          provenance: product.provenance,
        },
      };
    });
  }

  private notifications() {
    const items: Notification[] = Array.from({ length: this.unread }, (_, index) => ({
      id: `n-${index + 1}`,
      type: 'NOTIFICATION_CAP_REACHED',
      createdAt: '2026-11-25T10:00:00Z',
      readAt: null,
      payload: { periodMonth: '2026-11-01', limit: 30 },
    }));
    return { meta: { page: 1, pageSize: 10, total: items.length }, items };
  }
}

const DEFAULTS: DefaultWeights = {
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

function round(value: number): number {
  return Math.round(value * 10000) / 10000;
}

function problem(code: string, status: number): Reply {
  return [
    status,
    {
      type: `https://vitadelta.dev/problems/${code}`,
      title: code,
      status,
      requestId: 'flow-request',
    },
  ];
}
