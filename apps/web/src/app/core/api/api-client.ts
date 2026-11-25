import { HttpClient, HttpErrorResponse, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, of, throwError } from 'rxjs';

import { ApiProblem } from './api-problem';
import {
  AcceptedMessage,
  AdminHealth,
  AdminUser,
  AdminUserPage,
  AlertList,
  AlertSubscription,
  AuthTokenResponse,
  CompareRequest,
  CompareResponse,
  CountryList,
  CreateAlertRequest,
  CreatePromoCodeRequest,
  DefaultWeights,
  DeleteAccountRequest,
  EmailRequest,
  FxCurrencyList,
  FxRateList,
  FxRefreshResult,
  GetProductParams,
  GrantSubscriptionRequest,
  Health,
  ImportJob,
  ImportJobDetail,
  LegalInfo,
  ListNotificationsParams,
  ListPlansParams,
  ListSavedProductsParams,
  ListUsersParams,
  LoginRequest,
  Me,
  NotificationPage,
  NutrientList,
  OutboxStats,
  PlanList,
  ProductDetailResponse,
  ProductPage,
  ProductsImportDocument,
  PromoCode,
  PromoCodeList,
  PromoRedeemRequest,
  RecommendationQueryRequest,
  RecommendationQueryResponse,
  RegisterRequest,
  ResetPasswordRequest,
  SaveProductRequest,
  SavedProductList,
  SavedProductRef,
  SearchProductsParams,
  SessionQuota,
  SetUserStatusRequest,
  Subscription,
  SubscriptionActivation,
  TokenRequest,
  UnreadCount,
  UpdateAlertRequest,
  UpdateMeRequest,
} from './api-types';

/** Base path of the public API (same origin; nginx proxies `/api/`). */
export const API_BASE = '/api/v1';

/** CSRF header required by `POST /auth/refresh` and `POST /auth/logout`. */
export const CSRF_HEADER = 'X-Requested-With';
export const CSRF_VALUE = 'vitadelta';

type QueryValue = string | number | boolean | null | undefined;

function toParams(query: Readonly<Record<string, QueryValue>> | undefined): HttpParams {
  let params = new HttpParams();
  if (!query) {
    return params;
  }
  for (const [key, value] of Object.entries(query)) {
    if (value !== null && value !== undefined && value !== '') {
      params = params.set(key, String(value));
    }
  }
  return params;
}

function segment(value: string): string {
  return encodeURIComponent(value);
}

function mapProblem<T>(source: Observable<T>): Observable<T> {
  return source.pipe(catchError((error: unknown) => throwError(() => ApiProblem.from(error))));
}

const CSRF_HEADERS = new HttpHeaders({ [CSRF_HEADER]: CSRF_VALUE });

/**
 * Thin typed client for every operation of `contracts/public-api.openapi.yaml`. Method names
 * are the contract `operationId`s. Every call errors with an {@link ApiProblem}.
 *
 * `withCredentials` is set on the operations that read or write the `vd_anon` or `vd_refresh`
 * cookie; the bearer token is attached by the auth interceptor, never here.
 */
@Injectable({ providedIn: 'root' })
export class ApiClient {
  private readonly http = inject(HttpClient);

  private get<T>(path: string, query?: Readonly<Record<string, QueryValue>>): Observable<T> {
    return mapProblem(this.http.get<T>(API_BASE + path, { params: toParams(query) }));
  }

  private post<T>(
    path: string,
    body: unknown,
    options: { withCredentials?: boolean; headers?: HttpHeaders } = {},
  ): Observable<T> {
    return mapProblem(this.http.post<T>(API_BASE + path, body, options));
  }

  private patch<T>(path: string, body: unknown): Observable<T> {
    return mapProblem(this.http.patch<T>(API_BASE + path, body));
  }

  private delete(
    path: string,
    options: { body?: unknown; query?: Readonly<Record<string, QueryValue>> } = {},
  ): Observable<void> {
    return mapProblem(
      this.http
        .request('DELETE', API_BASE + path, {
          body: options.body,
          params: toParams(options.query),
        })
        .pipe(map((): undefined => undefined)),
    );
  }

  // System

  /** `GET /health`; a 503 with a `DOWN` body is returned as a value, not an error. */
  getHealth(): Observable<Health> {
    return this.http.get<Health>(`${API_BASE}/health`).pipe(
      catchError((error: unknown) => {
        if (
          error instanceof HttpErrorResponse &&
          error.status === 503 &&
          (error.error as Partial<Health> | null)?.status === 'DOWN'
        ) {
          return of<Health>({ status: 'DOWN' });
        }
        return throwError(() => ApiProblem.from(error));
      }),
    );
  }

  getLegal(): Observable<LegalInfo> {
    return this.get('/legal');
  }

  // Reference

  listCountries(): Observable<CountryList> {
    return this.get('/countries');
  }

  listNutrients(): Observable<NutrientList> {
    return this.get('/nutrients');
  }

  getDefaultWeights(): Observable<DefaultWeights> {
    return this.get('/weights/defaults');
  }

  // Session

  getSessionQuota(): Observable<SessionQuota> {
    return mapProblem(
      this.http.get<SessionQuota>(`${API_BASE}/session/quota`, { withCredentials: true }),
    );
  }

  // Recommendations

  queryRecommendations(body: RecommendationQueryRequest): Observable<RecommendationQueryResponse> {
    return this.post('/recommendations/query', body, { withCredentials: true });
  }

  compareProducts(body: CompareRequest): Observable<CompareResponse> {
    return this.post('/recommendations/compare', body);
  }

  // Products

  searchProducts(query: SearchProductsParams): Observable<ProductPage> {
    return this.get('/products', query);
  }

  getProduct(id: string, query: GetProductParams): Observable<ProductDetailResponse> {
    return this.get(`/products/${segment(id)}`, query);
  }

  // Plans and FX

  listPlans(query: ListPlansParams = {}): Observable<PlanList> {
    return this.get('/plans', query);
  }

  listFxCurrencies(): Observable<FxCurrencyList> {
    return this.get('/fx/currencies');
  }

  getFxRates(base: string): Observable<FxRateList> {
    return this.get('/fx/rates', { base });
  }

  // Auth

  register(body: RegisterRequest): Observable<AcceptedMessage> {
    return this.post('/auth/register', body, { withCredentials: true });
  }

  verifyEmail(body: TokenRequest): Observable<void> {
    return this.post('/auth/verify-email', body);
  }

  resendVerification(body: EmailRequest): Observable<AcceptedMessage> {
    return this.post('/auth/resend-verification', body);
  }

  login(body: LoginRequest): Observable<AuthTokenResponse> {
    return this.post('/auth/login', body, { withCredentials: true });
  }

  /**
   * `POST /auth/refresh`: the rotated session, or `null` for `204 No Content` (the request
   * carried no refresh cookie, so there is no session to restore).
   */
  refreshSession(): Observable<AuthTokenResponse | null> {
    return mapProblem(
      this.http
        .post<AuthTokenResponse>(
          `${API_BASE}/auth/refresh`,
          {},
          { withCredentials: true, headers: CSRF_HEADERS, observe: 'response' },
        )
        .pipe(map((response) => (response.status === 204 ? null : response.body))),
    );
  }

  logout(): Observable<void> {
    return this.post('/auth/logout', {}, { withCredentials: true, headers: CSRF_HEADERS });
  }

  forgotPassword(body: EmailRequest): Observable<AcceptedMessage> {
    return this.post('/auth/forgot-password', body);
  }

  resetPassword(body: ResetPasswordRequest): Observable<void> {
    return this.post('/auth/reset-password', body);
  }

  // Account

  getMe(): Observable<Me> {
    return this.get('/me');
  }

  updateMe(body: UpdateMeRequest): Observable<Me> {
    return this.patch('/me', body);
  }

  deleteMe(body: DeleteAccountRequest): Observable<void> {
    return this.delete('/me', { body });
  }

  // Saved products

  listSavedProducts(query: ListSavedProductsParams): Observable<SavedProductList> {
    return this.get('/me/saved-products', query);
  }

  saveProduct(body: SaveProductRequest): Observable<SavedProductRef> {
    return this.post('/me/saved-products', body);
  }

  removeSavedProduct(productId: string, country: string): Observable<void> {
    return this.delete(`/me/saved-products/${segment(productId)}`, { query: { country } });
  }

  // Alerts

  listAlerts(): Observable<AlertList> {
    return this.get('/me/alerts');
  }

  createAlert(body: CreateAlertRequest): Observable<AlertSubscription> {
    return this.post('/me/alerts', body);
  }

  updateAlert(id: string, body: UpdateAlertRequest): Observable<AlertSubscription> {
    return this.patch(`/me/alerts/${segment(id)}`, body);
  }

  deleteAlert(id: string): Observable<void> {
    return this.delete(`/me/alerts/${segment(id)}`);
  }

  // Notifications

  listNotifications(query: ListNotificationsParams = {}): Observable<NotificationPage> {
    return this.get('/me/notifications', query);
  }

  markNotificationRead(id: string): Observable<void> {
    return this.post(`/me/notifications/${segment(id)}/read`, null);
  }

  markAllNotificationsRead(): Observable<void> {
    return this.post('/me/notifications/read-all', null);
  }

  getUnreadNotificationCount(): Observable<UnreadCount> {
    return this.get('/me/notifications/unread-count');
  }

  // Subscription

  redeemPromoCode(body: PromoRedeemRequest): Observable<SubscriptionActivation> {
    return this.post('/me/subscription/promo', body);
  }

  // Admin

  getAdminHealth(): Observable<AdminHealth> {
    return this.get('/admin/health');
  }

  /**
   * `POST /admin/imports/products`. Accepts the parsed document or the selected file; a file
   * is sent as-is with `Content-Type: application/json` so large documents are not re-serialised.
   */
  importProducts(document: ProductsImportDocument | Blob): Observable<ImportJob> {
    const headers = new HttpHeaders({ 'Content-Type': 'application/json' });
    return this.post('/admin/imports/products', document, { headers });
  }

  /** `POST /admin/imports/offers` as `multipart/form-data` with the CSV in field `file`. */
  importOffers(file: Blob, fileName: string): Observable<ImportJob> {
    const form = new FormData();
    form.append('file', file, fileName);
    return this.post('/admin/imports/offers', form);
  }

  getImportJob(jobId: string): Observable<ImportJobDetail> {
    return this.get(`/admin/imports/${segment(jobId)}`);
  }

  listUsers(query: ListUsersParams = {}): Observable<AdminUserPage> {
    return this.get('/admin/users', query);
  }

  grantSubscription(id: string, body: GrantSubscriptionRequest): Observable<Subscription> {
    return this.post(`/admin/users/${segment(id)}/subscription`, body);
  }

  setUserStatus(id: string, body: SetUserStatusRequest): Observable<AdminUser> {
    return this.post(`/admin/users/${segment(id)}/status`, body);
  }

  listPromoCodes(): Observable<PromoCodeList> {
    return this.get('/admin/promo-codes');
  }

  createPromoCode(body: CreatePromoCodeRequest): Observable<PromoCode> {
    return this.post('/admin/promo-codes', body);
  }

  deletePromoCode(code: string): Observable<void> {
    return this.delete(`/admin/promo-codes/${segment(code)}`);
  }

  refreshFxRates(): Observable<FxRefreshResult> {
    return this.post('/admin/fx/refresh', null);
  }

  getOutboxStats(): Observable<OutboxStats> {
    return this.get('/admin/outbox/stats');
  }
}
