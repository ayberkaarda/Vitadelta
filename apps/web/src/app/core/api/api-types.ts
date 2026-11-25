// Named aliases over the generated contract types plus runtime lists of the contract
// enumerations. The lists are checked against the generated unions at compile time, so a
// contract change that adds a value fails the build until the list (and its i18n keys) follow.

import type { components, operations } from './schema';

type Schemas = components['schemas'];

export type Problem = Schemas['Problem'];
export type FieldError = Schemas['FieldError'];
export type UpgradeHint = Schemas['UpgradeHint'];
export type QuotaExceededProblem = Schemas['QuotaExceededProblem'];
export type EntitlementExceededProblem = Schemas['EntitlementExceededProblem'];
export type Health = Schemas['Health'];
export type HealthStatus = Schemas['HealthStatus'];
export type LegalInfo = Schemas['LegalInfo'];
export type Country = Schemas['Country'];
export type CountryList = Schemas['CountryList'];
export type Nutrient = Schemas['Nutrient'];
export type NutrientList = Schemas['NutrientList'];
export type NutrientCategory = Schemas['NutrientCategory'];
export type CanonicalUnit = Schemas['CanonicalUnit'];
export type InputUnit = Schemas['InputUnit'];
export type DosageForm = Schemas['DosageForm'];
export type AvailabilityStatus = Schemas['AvailabilityStatus'];
export type ReferenceSource = Schemas['ReferenceSource'];
export type StockFilter = Schemas['StockFilter'];
export type RankingMode = Schemas['RankingMode'];
export type Verdict = Schemas['Verdict'];
export type ProductFlagCode = Schemas['ProductFlagCode'];
export type DeltaRowFlag = Schemas['DeltaRowFlag'];
export type Role = Schemas['Role'];
export type PlanCode = Schemas['PlanCode'];
export type AlertTrigger = Schemas['AlertTrigger'];
export type NotificationType = Schemas['NotificationType'];
export type CategoryWeights = Schemas['CategoryWeights'];
export type NutrientOverrides = Schemas['NutrientOverrides'];
export type Weights = Schemas['Weights'];

export type DefaultCategoryWeights = Schemas['DefaultCategoryWeights'];
export type DefaultWeights = Schemas['DefaultWeights'];
export type EngineParameters = Schemas['EngineParameters'];
export type Ranking = Schemas['Ranking'];
export type RequiredNutrient = Schemas['RequiredNutrient'];
export type RecommendationQueryRequest = Schemas['RecommendationQueryRequest'];
export type RecommendationQueryResponse = Schemas['RecommendationQueryResponse'];
export type QueryMeta = Schemas['QueryMeta'];
export type FxMeta = Schemas['FxMeta'];
export type MoneyMeta = Schemas['MoneyMeta'];
export type Visibility = Schemas['Visibility'];
export type PrincipalSummary = Schemas['PrincipalSummary'];
export type AnonymousPrincipal = Schemas['AnonymousPrincipal'];
export type Cost = Schemas['Cost'];
export type ProductFlag = Schemas['ProductFlag'];
export type BreakdownRow = Schemas['BreakdownRow'];
export type SelectedOffer = Schemas['SelectedOffer'];
export type ProductProvenance = Schemas['ProductProvenance'];
export type RankedProduct = Schemas['RankedProduct'];
export type CompareRequest = Schemas['CompareRequest'];
export type CompareResponse = Schemas['CompareResponse'];
export type ComparedProduct = Schemas['ComparedProduct'];
export type DeltaRow = Schemas['DeltaRow'];
export type Explanation = Schemas['Explanation'];
export type ExplanationParams = Schemas['ExplanationParams'];
export type ProductPage = Schemas['ProductPage'];
export type ProductSummary = Schemas['ProductSummary'];
export type ProductDetailResponse = Schemas['ProductDetailResponse'];
export type ProductDetail = Schemas['ProductDetail'];
export type ProductNutrient = Schemas['ProductNutrient'];
export type Offer = Schemas['Offer'];
export type PlanList = Schemas['PlanList'];
export type Plan = Schemas['Plan'];
export type PlanLimits = Schemas['PlanLimits'];
export type PlanPrice = Schemas['PlanPrice'];
export type FxCurrency = Schemas['FxCurrency'];
export type FxCurrencyList = Schemas['FxCurrencyList'];
export type FxRateList = Schemas['FxRateList'];
export type FxRefreshResult = Schemas['FxRefreshResult'];
export type UsageMeter = Schemas['UsageMeter'];
export type MonthlyUsageMeter = Schemas['MonthlyUsageMeter'];
export type Usage = Schemas['Usage'];
export type AnonymousQuota = Schemas['AnonymousQuota'];
export type UserQuota = Schemas['UserQuota'];
export type SessionQuota = Schemas['SessionQuota'];
export type UserProfile = Schemas['UserProfile'];
export type Subscription = Schemas['Subscription'];
export type Me = Schemas['Me'];
export type UpdateMeRequest = Schemas['UpdateMeRequest'];
export type DeleteAccountRequest = Schemas['DeleteAccountRequest'];
export type RegisterRequest = Schemas['RegisterRequest'];
export type TokenRequest = Schemas['TokenRequest'];
export type EmailRequest = Schemas['EmailRequest'];
export type LoginRequest = Schemas['LoginRequest'];
export type ResetPasswordRequest = Schemas['ResetPasswordRequest'];
export type AuthTokenResponse = Schemas['AuthTokenResponse'];
export type AcceptedMessage = Schemas['AcceptedMessage'];
export type SaveProductRequest = Schemas['SaveProductRequest'];
export type SavedProductRef = Schemas['SavedProductRef'];
export type SavedProduct = Schemas['SavedProduct'];
export type SavedProductList = Schemas['SavedProductList'];
export type AlertSubscription = Schemas['AlertSubscription'];
export type AlertListItem = Schemas['AlertListItem'];
export type AlertList = Schemas['AlertList'];
export type CreateAlertRequest = Schemas['CreateAlertRequest'];
export type UpdateAlertRequest = Schemas['UpdateAlertRequest'];
export type Notification = Schemas['Notification'];
export type NotificationPage = Schemas['NotificationPage'];
export type UnreadCount = Schemas['UnreadCount'];
export type PromoRedeemRequest = Schemas['PromoRedeemRequest'];
export type SubscriptionActivation = Schemas['SubscriptionActivation'];
export type AdminHealth = Schemas['AdminHealth'];
export type OutboxStats = Schemas['OutboxStats'];
export type ProductsImportDocument = Schemas['ProductsImportDocument'];
export type ImportJob = Schemas['ImportJob'];
export type ImportJobDetail = Schemas['ImportJobDetail'];
export type AdminUser = Schemas['AdminUser'];
export type AdminUserPage = Schemas['AdminUserPage'];
export type GrantSubscriptionRequest = Schemas['GrantSubscriptionRequest'];
export type SetUserStatusRequest = Schemas['SetUserStatusRequest'];
export type CreatePromoCodeRequest = Schemas['CreatePromoCodeRequest'];
export type PromoCode = Schemas['PromoCode'];
export type PromoCodeList = Schemas['PromoCodeList'];

export type SearchProductsParams = operations['searchProducts']['parameters']['query'];
export type GetProductParams = operations['getProduct']['parameters']['query'];
export type ListPlansParams = NonNullable<operations['listPlans']['parameters']['query']>;
export type ListSavedProductsParams = operations['listSavedProducts']['parameters']['query'];
export type ListNotificationsParams = NonNullable<
  operations['listNotifications']['parameters']['query']
>;
export type ListUsersParams = NonNullable<operations['listUsers']['parameters']['query']>;

export const PROBLEM_TYPE_PREFIX = 'https://vitadelta.dev/problems/';

type CodeOf<T> = T extends `${typeof PROBLEM_TYPE_PREFIX}${infer Code}` ? Code : never;

/** Machine-readable problem codes of the contract (the suffix of `Problem.type`). */
export type ProblemCode = CodeOf<Problem['type']>;

/**
 * Accepts a literal list only when it names every member of `All` and nothing else, so the
 * runtime list cannot drift from the generated union.
 */
function exhaustive<All extends string>() {
  return <const Listed extends readonly All[]>(
    list: Listed & ([Exclude<All, Listed[number]>] extends [never] ? unknown : never),
  ): Listed => list;
}

export const PROBLEM_CODES = exhaustive<ProblemCode>()([
  'validation_failed',
  'country_not_found',
  'product_not_found',
  'product_not_available_in_country',
  'currency_mismatch',
  'scoring_unavailable',
  'unauthorized',
  'forbidden',
  'invalid_credentials',
  'account_disabled',
  'email_not_verified',
  'token_invalid',
  'token_expired',
  'refresh_reuse_detected',
  'quota_exceeded',
  'entitlement_exceeded',
  'promo_invalid',
  'conflict_retry',
  'fx_unsupported_currency',
  'rate_limited',
  'unsupported_iu_form',
  'invalid_offer_url',
  'import_failed',
  'internal_error',
]);

export const PRODUCT_FLAG_CODES = exhaustive<ProductFlagCode>()([
  'EXCEEDS_UL',
  'UL_FORM_DEPENDENT',
  'CONVERSION_ASSUMED',
  'PRICE_STALE',
  'OUT_OF_STOCK',
  'MARKET_MEDIAN_REFERENCE_USED',
  'PRESENCE_ONLY_NUTRIENTS',
  'BELOW_PRESENCE_FLOOR',
  'FX_STALE',
  'FX_UNAVAILABLE',
  'UNAVAILABLE_IN_COUNTRY',
]);

export const DELTA_ROW_FLAGS = exhaustive<DeltaRowFlag>()([
  'NEW_IN_B',
  'REMOVED_IN_B',
  'DIMINISHING',
  'ABOVE_CEILING_NO_CREDIT',
  'ABOVE_UL_NO_CREDIT',
]);

export const VERDICTS = exhaustive<Verdict>()([
  'WORTH_IT',
  'MARGINAL',
  'NOT_WORTH_IT',
  'DOMINATES',
  'EQUIVALENT',
  'INSUFFICIENT_DATA',
]);

export const EXPLANATION_KEYS = exhaustive<Explanation['key']>()([
  'explain.verdict.worth_it',
  'explain.verdict.marginal',
  'explain.verdict.not_worth_it',
  'explain.verdict.dominates',
  'explain.verdict.equivalent',
  'explain.verdict.insufficient_data',
]);

export const NUTRIENT_CATEGORIES = exhaustive<NutrientCategory>()([
  'VITAMIN',
  'MINERAL',
  'COENZYME',
  'OTHER',
]);

export const DOSAGE_FORMS = exhaustive<DosageForm>()([
  'TABLET',
  'CAPSULE',
  'SOFTGEL',
  'GUMMY',
  'POWDER',
  'LIQUID',
]);

export const RANKING_MODES = exhaustive<RankingMode>()(['PRICE', 'RICHNESS', 'VALUE', 'CUSTOM']);

export const STOCK_FILTERS = exhaustive<StockFilter>()(['IN_STOCK_ONLY', 'INCLUDE_OUT_OF_STOCK']);

export const INPUT_UNITS = exhaustive<InputUnit>()(['mg', 'mcg', 'g', 'IU']);

export const PLAN_CODES = exhaustive<PlanCode>()(['FREE', 'PRO']);

/**
 * i18n keys the API sends as data (`UpgradeHint.messageKey`, `AcceptedMessage.messageKey`).
 * They live in the root translation file so every page can render them.
 */
export const SERVER_MESSAGE_KEYS = [
  'quota.exhausted',
  'limits.saved_products_reached',
  'limits.alert_subscriptions_reached',
  'auth.register.check_email',
  'auth.verify.resent',
  'auth.forgot.check_email',
] as const;
