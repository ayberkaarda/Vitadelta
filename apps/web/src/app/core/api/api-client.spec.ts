import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Observable, firstValueFrom } from 'rxjs';

import { ApiClient } from './api-client';
import { ApiProblem } from './api-problem';

interface Case {
  readonly name: string;
  readonly call: (api: ApiClient) => Observable<unknown>;
  readonly method: string;
  readonly url: string;
  readonly body?: unknown;
  readonly credentials?: boolean;
  readonly csrf?: boolean;
}

const ID = '3b2f0c4e-6a1d-4f0e-9c3a-1a2b3c4d5e01';

const CASES: readonly Case[] = [
  { name: 'getLegal', call: (a) => a.getLegal(), method: 'GET', url: '/api/v1/legal' },
  {
    name: 'listCountries',
    call: (a) => a.listCountries(),
    method: 'GET',
    url: '/api/v1/countries',
  },
  {
    name: 'listNutrients',
    call: (a) => a.listNutrients(),
    method: 'GET',
    url: '/api/v1/nutrients',
  },
  {
    name: 'getDefaultWeights',
    call: (a) => a.getDefaultWeights(),
    method: 'GET',
    url: '/api/v1/weights/defaults',
  },
  {
    name: 'getSessionQuota',
    call: (a) => a.getSessionQuota(),
    method: 'GET',
    url: '/api/v1/session/quota',
    credentials: true,
  },
  {
    name: 'queryRecommendations',
    call: (a) =>
      a.queryRecommendations({
        country: 'TR',
        stockFilter: 'IN_STOCK_ONLY',
        excludeExceedingUl: false,
        page: 1,
        pageSize: 20,
      }),
    method: 'POST',
    url: '/api/v1/recommendations/query',
    body: {
      country: 'TR',
      stockFilter: 'IN_STOCK_ONLY',
      excludeExceedingUl: false,
      page: 1,
      pageSize: 20,
    },
    credentials: true,
  },
  {
    name: 'compareProducts',
    call: (a) =>
      a.compareProducts({
        country: 'TR',
        baselineProductId: ID,
        candidateProductId: ID,
        stockFilter: 'IN_STOCK_ONLY',
      }),
    method: 'POST',
    url: '/api/v1/recommendations/compare',
    body: {
      country: 'TR',
      baselineProductId: ID,
      candidateProductId: ID,
      stockFilter: 'IN_STOCK_ONLY',
    },
  },
  {
    name: 'searchProducts',
    call: (a) => a.searchProducts({ country: 'TR', q: 'gold', page: 2 }),
    method: 'GET',
    url: '/api/v1/products?country=TR&q=gold&page=2',
  },
  {
    name: 'getProduct',
    call: (a) => a.getProduct(ID, { country: 'TR', displayCurrency: 'EUR' }),
    method: 'GET',
    url: `/api/v1/products/${ID}?country=TR&displayCurrency=EUR`,
  },
  { name: 'listPlans', call: (a) => a.listPlans(), method: 'GET', url: '/api/v1/plans' },
  {
    name: 'listFxCurrencies',
    call: (a) => a.listFxCurrencies(),
    method: 'GET',
    url: '/api/v1/fx/currencies',
  },
  {
    name: 'getFxRates',
    call: (a) => a.getFxRates('TRY'),
    method: 'GET',
    url: '/api/v1/fx/rates?base=TRY',
  },
  {
    name: 'register',
    call: (a) => a.register({ email: 'a@example.com', password: 'long-password-1' }),
    method: 'POST',
    url: '/api/v1/auth/register',
    body: { email: 'a@example.com', password: 'long-password-1' },
    credentials: true,
  },
  {
    name: 'verifyEmail',
    call: (a) => a.verifyEmail({ token: 'tok' }),
    method: 'POST',
    url: '/api/v1/auth/verify-email',
    body: { token: 'tok' },
  },
  {
    name: 'resendVerification',
    call: (a) => a.resendVerification({ email: 'a@example.com' }),
    method: 'POST',
    url: '/api/v1/auth/resend-verification',
    body: { email: 'a@example.com' },
  },
  {
    name: 'login',
    call: (a) => a.login({ email: 'a@example.com', password: 'pw' }),
    method: 'POST',
    url: '/api/v1/auth/login',
    body: { email: 'a@example.com', password: 'pw' },
    credentials: true,
  },
  {
    name: 'refreshSession',
    call: (a) => a.refreshSession(),
    method: 'POST',
    url: '/api/v1/auth/refresh',
    body: {},
    credentials: true,
    csrf: true,
  },
  {
    name: 'logout',
    call: (a) => a.logout(),
    method: 'POST',
    url: '/api/v1/auth/logout',
    body: {},
    credentials: true,
    csrf: true,
  },
  {
    name: 'forgotPassword',
    call: (a) => a.forgotPassword({ email: 'a@example.com' }),
    method: 'POST',
    url: '/api/v1/auth/forgot-password',
    body: { email: 'a@example.com' },
  },
  {
    name: 'resetPassword',
    call: (a) => a.resetPassword({ token: 't', newPassword: 'another-password' }),
    method: 'POST',
    url: '/api/v1/auth/reset-password',
    body: { token: 't', newPassword: 'another-password' },
  },
  { name: 'getMe', call: (a) => a.getMe(), method: 'GET', url: '/api/v1/me' },
  {
    name: 'updateMe',
    call: (a) => a.updateMe({ locale: 'tr' }),
    method: 'PATCH',
    url: '/api/v1/me',
    body: { locale: 'tr' },
  },
  {
    name: 'deleteMe',
    call: (a) => a.deleteMe({ password: 'pw' }),
    method: 'DELETE',
    url: '/api/v1/me',
    body: { password: 'pw' },
  },
  {
    name: 'listSavedProducts',
    call: (a) => a.listSavedProducts({ country: 'TR' }),
    method: 'GET',
    url: '/api/v1/me/saved-products?country=TR',
  },
  {
    name: 'saveProduct',
    call: (a) => a.saveProduct({ productId: ID, countryCode: 'TR' }),
    method: 'POST',
    url: '/api/v1/me/saved-products',
    body: { productId: ID, countryCode: 'TR' },
  },
  {
    name: 'removeSavedProduct',
    call: (a) => a.removeSavedProduct(ID, 'TR'),
    method: 'DELETE',
    url: `/api/v1/me/saved-products/${ID}?country=TR`,
  },
  { name: 'listAlerts', call: (a) => a.listAlerts(), method: 'GET', url: '/api/v1/me/alerts' },
  {
    name: 'createAlert',
    call: (a) => a.createAlert({ productId: ID, countryCode: 'TR', trigger: 'PRICE_DROP' }),
    method: 'POST',
    url: '/api/v1/me/alerts',
    body: { productId: ID, countryCode: 'TR', trigger: 'PRICE_DROP' },
  },
  {
    name: 'updateAlert',
    call: (a) => a.updateAlert('al/1', { active: false }),
    method: 'PATCH',
    url: '/api/v1/me/alerts/al%2F1',
    body: { active: false },
  },
  {
    name: 'deleteAlert',
    call: (a) => a.deleteAlert('al-1'),
    method: 'DELETE',
    url: '/api/v1/me/alerts/al-1',
  },
  {
    name: 'listNotifications',
    call: (a) => a.listNotifications({ unreadOnly: true, page: 1 }),
    method: 'GET',
    url: '/api/v1/me/notifications?unreadOnly=true&page=1',
  },
  {
    name: 'markNotificationRead',
    call: (a) => a.markNotificationRead('n-1'),
    method: 'POST',
    url: '/api/v1/me/notifications/n-1/read',
  },
  {
    name: 'markAllNotificationsRead',
    call: (a) => a.markAllNotificationsRead(),
    method: 'POST',
    url: '/api/v1/me/notifications/read-all',
  },
  {
    name: 'getUnreadNotificationCount',
    call: (a) => a.getUnreadNotificationCount(),
    method: 'GET',
    url: '/api/v1/me/notifications/unread-count',
  },
  {
    name: 'redeemPromoCode',
    call: (a) => a.redeemPromoCode({ code: 'LAUNCH-ABC' }),
    method: 'POST',
    url: '/api/v1/me/subscription/promo',
    body: { code: 'LAUNCH-ABC' },
  },
  {
    name: 'getAdminHealth',
    call: (a) => a.getAdminHealth(),
    method: 'GET',
    url: '/api/v1/admin/health',
  },
  {
    name: 'getImportJob',
    call: (a) => a.getImportJob('job-1'),
    method: 'GET',
    url: '/api/v1/admin/imports/job-1',
  },
  {
    name: 'listUsers',
    call: (a) => a.listUsers({ q: 'example', page: 1, pageSize: 50 }),
    method: 'GET',
    url: '/api/v1/admin/users?q=example&page=1&pageSize=50',
  },
  {
    name: 'grantSubscription',
    call: (a) => a.grantSubscription('u-1', { planCode: 'PRO', durationDays: 30 }),
    method: 'POST',
    url: '/api/v1/admin/users/u-1/subscription',
    body: { planCode: 'PRO', durationDays: 30 },
  },
  {
    name: 'setUserStatus',
    call: (a) => a.setUserStatus('u-1', { status: 'DISABLED' }),
    method: 'POST',
    url: '/api/v1/admin/users/u-1/status',
    body: { status: 'DISABLED' },
  },
  {
    name: 'listPromoCodes',
    call: (a) => a.listPromoCodes(),
    method: 'GET',
    url: '/api/v1/admin/promo-codes',
  },
  {
    name: 'createPromoCode',
    call: (a) =>
      a.createPromoCode({ planCode: 'PRO', durationDays: 30, maxRedemptions: 10, expiresAt: null }),
    method: 'POST',
    url: '/api/v1/admin/promo-codes',
    body: { planCode: 'PRO', durationDays: 30, maxRedemptions: 10, expiresAt: null },
  },
  {
    name: 'deletePromoCode',
    call: (a) => a.deletePromoCode('LAUNCH-ABC'),
    method: 'DELETE',
    url: '/api/v1/admin/promo-codes/LAUNCH-ABC',
  },
  {
    name: 'refreshFxRates',
    call: (a) => a.refreshFxRates(),
    method: 'POST',
    url: '/api/v1/admin/fx/refresh',
  },
  {
    name: 'getOutboxStats',
    call: (a) => a.getOutboxStats(),
    method: 'GET',
    url: '/api/v1/admin/outbox/stats',
  },
];

describe('ApiClient', () => {
  let api: ApiClient;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(ApiClient);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it.each(CASES)('$name sends $method $url', async (testCase) => {
    const result = firstValueFrom(testCase.call(api), { defaultValue: undefined });
    const request = http.expectOne((req) => req.urlWithParams === testCase.url);
    expect(request.request.method).toBe(testCase.method);
    if ('body' in testCase) {
      expect(request.request.body).toEqual(testCase.body);
    }
    expect(request.request.withCredentials).toBe(testCase.credentials === true);
    expect(request.request.headers.get('X-Requested-With')).toBe(
      testCase.csrf ? 'vitadelta' : null,
    );
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush({ ok: true });
    // DELETE operations answer 204 and resolve without a value.
    await expect(result).resolves.toEqual(testCase.method === 'DELETE' ? undefined : { ok: true });
  });

  it('covers every operation of the contract', () => {
    const covered = new Set([
      ...CASES.map((c) => c.name),
      'getHealth',
      'importProducts',
      'importOffers',
    ]);
    const methods = Object.getOwnPropertyNames(ApiClient.prototype).filter(
      (name) => name !== 'constructor' && !['get', 'post', 'patch', 'delete'].includes(name),
    );
    expect(methods.sort()).toEqual([...covered].sort());
    expect(methods).toHaveLength(48);
  });

  it('returns a DOWN health body sent with 503 as a value', async () => {
    const result = firstValueFrom(api.getHealth());
    http
      .expectOne('/api/v1/health')
      .flush({ status: 'DOWN' }, { status: 503, statusText: 'Service Unavailable' });
    await expect(result).resolves.toEqual({ status: 'DOWN' });
  });

  it('maps other health failures to ApiProblem', async () => {
    const result = firstValueFrom(api.getHealth());
    http.expectOne('/api/v1/health').flush(null, { status: 502, statusText: 'Bad Gateway' });
    await expect(result).rejects.toBeInstanceOf(ApiProblem);
  });

  it('sends the products import document as JSON', async () => {
    const file = new Blob(['{"schema_version":1,"products":[]}'], { type: '' });
    const result = firstValueFrom(api.importProducts(file));
    const request = http.expectOne('/api/v1/admin/imports/products');
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('Content-Type')).toBe('application/json');
    expect(request.request.body).toBe(file);
    request.flush({ id: 'j' });
    await expect(result).resolves.toEqual({ id: 'j' });
  });

  it('uploads the offers CSV as multipart form data in field "file"', async () => {
    const csv = new Blob(['product_code\n'], { type: 'text/csv' });
    const result = firstValueFrom(api.importOffers(csv, 'offers.csv'));
    const request = http.expectOne('/api/v1/admin/imports/offers');
    const body = request.request.body as FormData;
    expect(body).toBeInstanceOf(FormData);
    expect((body.get('file') as File).name).toBe('offers.csv');
    request.flush({ id: 'j2' });
    await expect(result).resolves.toEqual({ id: 'j2' });
  });

  it('resolves null when the refresh answers 204 without a session cookie', async () => {
    const result = firstValueFrom(api.refreshSession());
    http.expectOne('/api/v1/auth/refresh').flush(null, { status: 204, statusText: 'No Content' });
    await expect(result).resolves.toBeNull();
  });

  it('keeps a refused refresh (401) an ApiProblem', async () => {
    const result = firstValueFrom(api.refreshSession());
    http
      .expectOne('/api/v1/auth/refresh')
      .flush(
        { type: 'https://vitadelta.dev/problems/unauthorized', status: 401 },
        { status: 401, statusText: 'Unauthorized' },
      );
    const error = await result.catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiProblem);
    expect((error as ApiProblem).status).toBe(401);
  });

  it('fails with a typed ApiProblem carrying the problem code', async () => {
    const result = firstValueFrom(api.getMe());
    http.expectOne('/api/v1/me').flush(
      {
        type: 'https://vitadelta.dev/problems/unauthorized',
        title: 'Unauthorized',
        status: 401,
        requestId: 'r-9',
      },
      { status: 401, statusText: 'Unauthorized' },
    );
    const error = await result.catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiProblem);
    expect((error as ApiProblem).code).toBe('unauthorized');
    expect((error as ApiProblem).requestId).toBe('r-9');
  });
});
