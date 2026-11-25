// contract-sha256: e29e22f59118943b95fe82e5122ca3e7686dcb25f2c621d12e3378f2137c7d92
// Types of the public API, derived from contracts/public-api.openapi.yaml.
// Do not edit by hand: run `npm run api:types` in apps/web after changing the contract.

export interface paths {
    "/health": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Minimal liveness status
         * @description Returns only the aggregated status. No dependency names, versions or
         *     backlog figures are exposed. `DOWN` is returned with status 503. The
         *     status is the overall status of `GET /admin/health` (`DOWN` when the
         *     database is down, `DEGRADED` when another component is degraded or
         *     down), cached for at most 10 seconds.
         */
        get: operations["getHealth"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/legal": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Privacy notice information
         * @description Returns the controller contact rendered on the privacy notice page.
         */
        get: operations["getLegal"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/countries": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * List supported countries
         * @description Supported countries with currency, reference set, upper-limit set and default locale.
         */
        get: operations["listCountries"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/nutrients": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Nutrient registry
         * @description Registry with categories, canonical units, the reference sets that
         *     define a value for each nutrient and the registered forms of each
         *     nutrient (form registry; `ANY` and `UNKNOWN` are sentinels and are
         *     never listed).
         */
        get: operations["listNutrients"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/weights/defaults": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Default weights and allowed ranges
         * @description Default category weights, the default per-nutrient override and the
         *     allowed ranges of category weights, overrides and the CUSTOM-mode lambda.
         *
         *     `engine` carries the read-only scoring-engine parameters the web app
         *     needs to draw the utility curve and the verdict scale. They are the
         *     `config` of the scoring service's `GET /internal/v1/health`
         *     (`EngineConfig` of the scoring contract), copied unrounded. The API
         *     reads them on its first need and keeps the last successful answer in
         *     memory; while no answer was ever obtained (scoring service down since
         *     start-up) `engine` is `null` and the operation still answers 200. This
         *     operation never answers 503.
         */
        get: operations["getDefaultWeights"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/session/quota": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Quota counters of the current principal
         * @description For anonymous callers returns the trial counters and creates the `vd_anon`
         *     cookie when it is missing. A missing or invalid (tampered) cookie
         *     creates a new identity; creation is limited to 60 per hour per client
         *     IP (one limit shared with `POST /recommendations/query`), while
         *     requests with a valid cookie are never limited by it. For
         *     signed-in users returns the plan usage summary of `GET /me`.
         *
         *     Problem codes: `unauthorized` (401, invalid bearer token),
         *     `rate_limited` (429, anonymous identity creation).
         */
        get: operations["getSessionQuota"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/recommendations/query": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Ranked recommendations
         * @description Scores every in-market product of the country and returns the ranking.
         *     Products without an offer, and under `IN_STOCK_ONLY` products without
         *     an in-stock offer, are not scored and are counted in `meta.excluded`.
         *
         *     - ANONYMOUS: consumes one trial query (3 per anonymous identity); the
         *       response contains only the top 3 items of the full ranking regardless
         *       of `page`; `page > 1` returns an empty `items` array with the same
         *       `meta.visibility`. Locked items are not sent in any form. The `vd_anon`
         *       cookie is created when missing or invalid; creating it shares the
         *       limit of `GET /session/quota` (60 identities per hour per client IP).
         *       The request is validated before the quota is reserved, and only a
         *       200 response consumes quota: a 422, 500 or 503 after the reservation
         *       refunds it.
         *     - USER / ADMIN: unlimited, full paged results; a page beyond the last
         *       returns an empty `items` array.
         *
         *     Money is in `meta.currency`. Until a rate exists for the requested
         *     display currency, money stays native, `meta.fx.status` is `UNAVAILABLE`
         *     and every item carries the `FX_UNAVAILABLE` flag; a stale rate adds
         *     `FX_STALE`.
         *
         *     Scoring-service rejections: a scoring `422` whose errors all point at
         *     client-supplied values (weights, `ranking.lambda`,
         *     `requiredNutrients[].minAmountPerDay`), for example
         *     `value_out_of_range` or `out_of_range`, is returned as 422
         *     `validation_failed` with the same `errors[].code` and the field
         *     translated to the public path; any other scoring `422` (for example
         *     `duplicate_product_id`, inconsistent catalog input) or scoring `4xx`
         *     is 500 `internal_error`. Scoring timeouts, connection errors, `5xx`
         *     and an open circuit are 503 `scoring_unavailable`.
         *
         *     Every number of the request (weights, overrides, `ranking.lambda`,
         *     `requiredNutrients[].minAmountPerDay`) may have at most 12 integer
         *     digits and at most 9 decimal places once trailing zeros are removed;
         *     a number beyond that (for example `1e-1073741824`) is 422
         *     `validation_failed` with `errors[].code = out_of_range`, before any
         *     quota is reserved.
         *
         *     Problem codes: `validation_failed` (422; `errors[].code` includes
         *     `weights_all_zero`, `unit_requires_form`, `unknown_nutrient_code`,
         *     `lambda_requires_custom`, `duplicate_nutrient`, `out_of_range`,
         *     `value_out_of_range`; also a malformed `displayCurrency`),
         *     `country_not_found` (422), `fx_unsupported_currency` (422),
         *     `quota_exceeded` (403, anonymous only, with `upgradeHint`),
         *     `unauthorized` (401, invalid bearer token), `rate_limited` (429 with
         *     `Retry-After`: anonymous callers 30 queries per day per client IP, or
         *     anonymous identity creation; signed-in callers 30 cache-missing
         *     queries and comparisons together per minute per user and at most 2 in
         *     flight, rankings served from the cache are not counted),
         *     `scoring_unavailable` (503), request
         *     body larger than 64 kB (413), body not `application/json` (415),
         *     `internal_error` (500).
         */
        post: operations["queryRecommendations"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/recommendations/compare": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Pairwise marginal analysis of two products
         * @description Orders the pair so that A is the cheaper product by cost per day (ties:
         *     lower content score, then lower product id) and returns a verdict on
         *     whether B is worth its extra cost. `swapped = true` when A is not the
         *     requested baseline. Allowed for every principal and not counted against
         *     the anonymous quota; it never creates the `vd_anon` cookie.
         *
         *     The market used for `mRef` is every scoreable product of the country
         *     under `stockFilter`, including both products. Both products must be
         *     in market and have an effective price under `stockFilter`: a product
         *     without any offer is 422 `validation_failed` with `errors[].code =
         *     no_offer`, and under `IN_STOCK_ONLY` a product without an in-stock
         *     offer is `no_stock` (field `baselineProductId` or
         *     `candidateProductId`). Comparing a product with itself is 422
         *     `validation_failed` with `errors[].code = same_product` (field
         *     `candidateProductId`). `mRef`, `marginalRate` and `ratio` are
         *     nullable (verdict `INSUFFICIENT_DATA` when `mRef` is null).
         *
         *     Money is in `meta.currency`. Until a rate exists for the requested
         *     display currency, money stays native, `meta.fx.status` is
         *     `UNAVAILABLE` and both products carry the `FX_UNAVAILABLE` flag; a
         *     stale rate adds `FX_STALE`. Scoring-service rejections are mapped as
         *     for `POST /recommendations/query`.
         *
         *     Every weight and override may have at most 12 integer digits and at
         *     most 9 decimal places once trailing zeros are removed; a number beyond
         *     that (for example `1e-1073741824`) is 422 `validation_failed` with
         *     `errors[].code = out_of_range`.
         *
         *     Problem codes: `validation_failed` (422; `errors[].code` includes
         *     `weights_all_zero`, `same_product`, `no_offer`, `no_stock`,
         *     `unknown_nutrient_code`, `out_of_range`, `value_out_of_range`),
         *     `country_not_found` (422), `fx_unsupported_currency` (422),
         *     `product_not_found` (404), `product_not_available_in_country` (404,
         *     status `NOT_SOLD` or `DISCONTINUED` or no availability row),
         *     `unauthorized` (401, invalid bearer token), `rate_limited` (429 with
         *     `Retry-After`, anonymous callers 120 per minute per client IP, shared
         *     with `/products/**`; signed-in callers 30 comparisons and
         *     cache-missing queries together per minute per user and at most 2 in
         *     flight), `scoring_unavailable` (503), request body larger
         *     than 64 kB (413), body not `application/json` (415), `internal_error`
         *     (500).
         */
        post: operations["compareProducts"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/products": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Search products in one country
         * @description Returns in-market (AVAILABLE) products of the country. Products that are
         *     `NOT_SOLD` or `DISCONTINUED` in the country are never returned. The cost
         *     object uses the effective price with out-of-stock offers included and is
         *     `null` when the product has no offer. Results are ordered by `brand`,
         *     then `name`, then `code` (ascending, binary order); `q` matches a
         *     case-insensitive substring of brand, name or code. A page beyond the
         *     last returns an empty `items` array.
         *
         *     This operation does not call the scoring service (no 503). Flags are
         *     limited to the ones the API derives itself: `PRICE_STALE`,
         *     `OUT_OF_STOCK`, `CONVERSION_ASSUMED`, `FX_STALE` and `FX_UNAVAILABLE`.
         *     Until a rate exists for the requested display currency, money stays
         *     native, `meta.fx.status` is `UNAVAILABLE` and every item with a cost
         *     carries `FX_UNAVAILABLE`.
         *
         *     Problem codes: `validation_failed` (422; malformed `country`,
         *     `displayCurrency`, `page`, `pageSize` or `q`), `country_not_found`
         *     (422), `fx_unsupported_currency` (422), `unauthorized` (401, invalid
         *     bearer token), `rate_limited` (429 with `Retry-After`, anonymous
         *     callers 120 per minute per client IP, shared with
         *     `/recommendations/compare`), `internal_error` (500).
         */
        get: operations["searchProducts"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/products/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Product detail in one country
         * @description Formulation, availability, offers and flags of one product in the given
         *     country. For signed-in users `saved` and `alerts` reflect the caller's
         *     saved state and alert subscriptions (active and inactive) in that
         *     country; for anonymous callers they are `false` and `[]`.
         *
         *     `offers` lists every offer of the product in the country (in stock
         *     and out of stock), ordered by `price` ascending, then `checkedAt`
         *     descending, then `retailer` ascending. `cost` and `selectedOffer` use
         *     the effective price with out-of-stock offers included. This operation
         *     does not call the scoring service (no 503); flags are limited to
         *     `PRICE_STALE`, `OUT_OF_STOCK`, `CONVERSION_ASSUMED`, `FX_STALE` and
         *     `FX_UNAVAILABLE`. Until a rate exists for the requested display
         *     currency, money stays native and `meta.fx.status` is `UNAVAILABLE`.
         *
         *     Each nutrient row carries the catalog facts of the requested country:
         *     `reference` (the country's reference set value and the percentage of
         *     it covered per day) and `upperLimit` (the country's UL set row that
         *     applies to the row's form, with `exceeded`). Both are read from the
         *     catalog, never from the scoring service, and are `null` when the set
         *     defines no applicable value. `upperLimit.exceeded` does not add a flag
         *     to `flags`.
         *
         *     Problem codes: `product_not_found` (404, unknown id or an id that is
         *     not a UUID),
         *     `product_not_available_in_country` (404, status `NOT_SOLD` or
         *     `DISCONTINUED` or no availability row), `validation_failed` (422;
         *     malformed `country` or `displayCurrency`), `country_not_found` (422),
         *     `fx_unsupported_currency` (422), `unauthorized` (401, invalid bearer
         *     token), `rate_limited` (429 with `Retry-After`, anonymous callers 120
         *     per minute per client IP), `internal_error` (500).
         */
        get: operations["getProduct"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/plans": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Plan limits and prices
         * @description Returns the FREE and PRO plans. A `null` limit means unlimited. Prices are
         *     converted to the display currency; the native currency of a price is the
         *     currency stored on the plan. Credentials are optional: a valid bearer
         *     token only supplies the user's display-currency preference. When no
         *     rate exists for the requested supported currency, money stays in the
         *     native currency, `meta.currency` equals `meta.nativeCurrency`,
         *     `meta.fx.quote` names the requested currency and `meta.fx.status` is
         *     `UNAVAILABLE`.
         *
         *     Problem codes: `unauthorized` (401, invalid bearer token),
         *     `validation_failed` (422, `displayCurrency` not three upper-case
         *     letters), `fx_unsupported_currency` (422).
         */
        get: operations["listPlans"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/fx/currencies": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Supported display currencies
         * @description The supported display currencies (country currencies plus the
         *     configured extra ISO 4217 codes of `VITADELTA_FX_EXTRA_CURRENCIES`),
         *     ordered by `code` ascending. `asOf` is the newest stored `as_of` of
         *     any rate row whose base or quote is the currency (`null` when no such
         *     row exists); `stale` is `true` when `asOf` is more than 5 days before
         *     the current UTC date, and `false` when `asOf` is `null`. Reads the
         *     stored rates only; it never calls the FX provider.
         */
        get: operations["listFxCurrencies"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/fx/rates": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Newest rates for a base currency
         * @description Newest stored rate (largest `as_of`) per quote currency for the base,
         *     over every other supported currency, ordered by `quote` ascending. A
         *     quote without any stored row for the pair is omitted (an empty
         *     `rates` array before the first refresh); the identity pair is never
         *     listed. Rates are never inverted or derived through a third currency.
         *     `rate` is a JSON number written with exactly the 8 stored decimals
         *     (for example `0.02087500`), never rounded. `stale` is `true` when
         *     `asOf` is more than 5 days before the current UTC date. `source` is
         *     `FRANKFURTER`. Reads the stored rates only.
         *
         *     Problem codes: `validation_failed` (422, missing or malformed `base`,
         *     field `base`), `fx_unsupported_currency` (422, a well-formed base
         *     outside the supported set), `internal_error` (500).
         */
        get: operations["getFxRates"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/auth/register": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Register with e-mail and password
         * @description Enumeration-safe: always responds 202 with the same body and headers. A
         *     new e-mail creates a USER with an ACTIVE FREE subscription and queues the
         *     verification e-mail; an existing e-mail queues an "already registered"
         *     e-mail instead. A present `vd_anon` identity is converted and the cookie
         *     is cleared on both paths. Requires `Content-Type: application/json` and,
         *     when an `Origin` header is present, an allowed origin.
         *
         *     Problem codes: `validation_failed` (422; password shorter than 10
         *     characters or in the breached-password list, malformed e-mail),
         *     `country_not_found` (422), `forbidden` (403, content type or origin
         *     rejected), `rate_limited` (429, 5 per hour per IP).
         */
        post: operations["register"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/auth/verify-email": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Verify the e-mail address
         * @description Consumes a single-use `VERIFY_EMAIL` token (valid 24 hours) taken from the
         *     URL fragment of the verification link.
         *
         *     Problem codes: `token_invalid` (400), `token_expired` (400),
         *     `validation_failed` (422), `rate_limited` (429, 10 per hour per IP).
         */
        post: operations["verifyEmail"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/auth/resend-verification": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Resend the verification e-mail
         * @description Always 202. Issuing a new `VERIFY_EMAIL` token invalidates all older ones.
         *     Suppressed or dropped e-mails still return 202.
         *
         *     Problem codes: `validation_failed` (422), `rate_limited` (429, 5 per hour
         *     per IP).
         */
        post: operations["resendVerification"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/auth/login": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Log in
         * @description Returns a 15-minute access token and sets the `vd_refresh` cookie.
         *     Unverified users can log in and see `emailVerified = false`. Requires
         *     `Content-Type: application/json` and, when an `Origin` header is
         *     present, an allowed origin.
         *
         *     Problem codes: `invalid_credentials` (401, identical for unknown e-mail
         *     and wrong password), `account_disabled` (403, only after a correct
         *     password), `forbidden` (403, content type or origin rejected),
         *     `validation_failed` (422), `rate_limited` (429, 10 per minute per IP, or
         *     account back-off after 10 failures in 15 minutes).
         */
        post: operations["login"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/auth/refresh": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Rotate the refresh token
         * @description Rotates the `vd_refresh` cookie and returns a fresh access token. A token
         *     rotated less than 20 seconds ago returns the same successor (grace
         *     window). Presenting a revoked token outside the grace window revokes the
         *     whole token family. Re-reads the account status. Requires
         *     `X-Requested-With: vitadelta`, `Content-Type: application/json` and, when
         *     an `Origin` header is present, an allowed origin.
         *
         *     Order of checks: the CSRF header, content type and origin checks (403
         *     `forbidden`) and the rate limit (429, every request counts, including
         *     requests without a cookie) are applied first, exactly as before.
         *
         *     No session (204): when the request carries no `vd_refresh` cookie at
         *     all (no cookie of that name, or only one with an empty value), the
         *     answer is `204 No Content` with no body and no `Set-Cookie` header: the
         *     cookie state is left unchanged, no token or account is read, and the
         *     request is not recorded as a failed refresh attempt (no problem
         *     response, no reuse or security event). Clients treat 204 as "signed
         *     out" and do not retry.
         *
         *     A present, non-empty `vd_refresh` cookie keeps its existing behaviour:
         *     an unknown, expired or revoked token (outside the grace window) answers
         *     401, a reused token revokes the family and answers 401
         *     `refresh_reuse_detected`, and these 401 responses clear the cookie as
         *     before.
         *
         *     Problem codes: `unauthorized` (401, unknown, expired or revoked refresh
         *     token), `refresh_reuse_detected` (401), `account_disabled` (403),
         *     `forbidden` (403, CSRF header, content type or origin rejected),
         *     `rate_limited` (429, 60 per minute per IP).
         */
        post: operations["refreshSession"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/auth/logout": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Log out
         * @description Revokes the refresh-token family of the presented cookie and clears the
         *     cookie. Idempotent: responds 204 also when no valid cookie is present.
         *     Requires `X-Requested-With: vitadelta`, `Content-Type: application/json`
         *     and, when an `Origin` header is present, an allowed origin.
         *
         *     Problem codes: `forbidden` (403, CSRF header, content type or origin
         *     rejected).
         */
        post: operations["logout"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/auth/forgot-password": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Request a password reset e-mail
         * @description Always 202. When the account exists, a `RESET_PASSWORD` token (valid one
         *     hour) is created, older unused reset tokens are invalidated and the
         *     e-mail is queued.
         *
         *     Problem codes: `validation_failed` (422), `rate_limited` (429, 5 per hour
         *     per IP).
         */
        post: operations["forgotPassword"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/auth/reset-password": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Set a new password with a reset token
         * @description Consumes a single-use `RESET_PASSWORD` token. On success every outstanding
         *     reset token of the user is invalidated and all refresh-token families
         *     are revoked.
         *
         *     Problem codes: `token_invalid` (400), `token_expired` (400),
         *     `validation_failed` (422, password rules), `rate_limited` (429, 10 per
         *     hour per IP).
         */
        post: operations["resetPassword"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/me": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Profile, plan, subscription and usage meters
         * @description Problem codes: `unauthorized` (401).
         */
        get: operations["getMe"];
        put?: never;
        post?: never;
        /**
         * Delete the account
         * @description Re-checks the password and hard-deletes the account with all dependent
         *     data (refresh tokens, e-mail tokens, subscriptions, promo redemptions,
         *     saved products, alert subscriptions, notifications, usage counters).
         *     The `vd_refresh` cookie is cleared.
         *
         *     Problem codes: `unauthorized` (401, missing or invalid access token),
         *     `invalid_credentials` (401, wrong password), `validation_failed` (422).
         */
        delete: operations["deleteMe"];
        options?: never;
        head?: never;
        /**
         * Update profile settings
         * @description Updates any of `displayCurrency`, `locale`, `defaultCountryCode` and
         *     `emailAlertsEnabled`. `displayCurrency: null` resets the preference to
         *     the country's native currency.
         *
         *     `emailAlertsEnabled: false` stops alert and cap e-mails only: in-app
         *     notifications are still created, and a pending e-mail is evaluated at
         *     send time (it is skipped as ineligible while the preference is
         *     `false`). Auth and plan e-mails are not affected. It is the target of
         *     the preference link and the `List-Unsubscribe` header of alert
         *     e-mails.
         *
         *     Problem codes: `unauthorized` (401), `validation_failed` (422),
         *     `country_not_found` (422), `fx_unsupported_currency` (422).
         */
        patch: operations["updateMe"];
        trace?: never;
    };
    "/me/saved-products": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Saved products in one country
         * @description Every saved product of the caller for the country (no paging), ordered
         *     by `savedAt` descending, then `productId` ascending, with the current
         *     effective price (section 7.4, out-of-stock offers included), its stock
         *     state (`cost.outOfStock` and the `OUT_OF_STOCK` flag), flags and the
         *     caller's active alert subscriptions on the product in that country.
         *     Does not call the scoring service.
         *
         *     A product whose availability in the country is no longer `AVAILABLE`
         *     (`NOT_SOLD`, `DISCONTINUED` or no availability row) stays in the list
         *     with `availabilityStatus` (`NOT_SOLD` for a missing row), `cost` and
         *     `selectedOffer` `null` and the single flag `UNAVAILABLE_IN_COUNTRY`.
         *     An available product carries the flags of `GET /products`
         *     (`CONVERSION_ASSUMED`, `PRICE_STALE`, `OUT_OF_STOCK`, then `FX_STALE`
         *     / `FX_UNAVAILABLE` when it has a cost); an available product without
         *     any offer has `cost` and `selectedOffer` `null`.
         *
         *     Money is converted like every catalog response (`meta.fx`); until a
         *     rate exists for the display currency money stays native with
         *     `meta.fx.status = UNAVAILABLE`. `alerts[].baselinePrice` is always
         *     native (`baselineCurrency`).
         *
         *     Problem codes: `unauthorized` (401), `validation_failed` (422,
         *     malformed `country` or `displayCurrency`), `country_not_found` (422),
         *     `fx_unsupported_currency` (422).
         */
        get: operations["listSavedProducts"];
        put?: never;
        /**
         * Save a product
         * @description Saves the product for the country. Does not require a verified e-mail.
         *
         *     Order of checks: body validation (422) -> country (422
         *     `country_not_found`) -> product (404 `product_not_found`, also for an
         *     unknown id) -> availability (404 `product_not_available_in_country`
         *     unless the product is `AVAILABLE` in the country) -> in one
         *     transaction: lock the user's row, return 200 with the existing row
         *     when it is already saved (idempotent, no limit check, `savedAt`
         *     unchanged), otherwise check the plan limit and insert (201).
         *
         *     The saved-product limit counts the caller's saved rows over every
         *     country. At the limit the answer is 403 `entitlement_exceeded` with
         *     `limit`, `used`, `plan`, `upgradeTo: PRO` and `upgradeHint
         *     { messageKey: limits.saved_products_reached, ctaPath: /upgrade }`.
         *
         *     Problem codes: `unauthorized` (401), `entitlement_exceeded` (403, saved
         *     product limit of the plan reached), `product_not_found` (404),
         *     `product_not_available_in_country` (404), `validation_failed` (422,
         *     missing or malformed `productId` or `countryCode`),
         *     `country_not_found` (422), request body larger than 64 kB (413), body
         *     not `application/json` (415), `internal_error` (500).
         */
        post: operations["saveProduct"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/me/saved-products/{productId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /**
         * Remove a saved product
         * @description Removes the caller's saved-product row for the product and country and,
         *     in the same transaction, sets `active = false` on every alert
         *     subscription of the caller for that product and country (the
         *     subscriptions are kept, with their baseline, and stop counting toward
         *     the active-alert limit). Idempotent: responds 204 also when the
         *     product was not saved, does not exist, or `productId` is not a UUID.
         *
         *     Problem codes: `unauthorized` (401), `validation_failed` (422,
         *     missing or malformed `country`), `country_not_found` (422).
         */
        delete: operations["removeSavedProduct"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/me/alerts": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Alert subscriptions of the caller
         * @description All alert subscriptions of the caller, active and inactive, across
         *     countries (no paging), ordered by `createdAt` descending, then `id`
         *     ascending. `baselinePrice` is in the country's native currency
         *     (`baselineCurrency`) and is never converted. `product` names the
         *     product and its availability in the subscription's country; anything
         *     but `AVAILABLE` (a missing availability row is `NOT_SOLD`) is shown
         *     with the `UNAVAILABLE_IN_COUNTRY` badge.
         *
         *     Problem codes: `unauthorized` (401), `internal_error` (500).
         */
        get: operations["listAlerts"];
        put?: never;
        /**
         * Subscribe to an alert
         * @description Creates an active subscription. A saved-product row is created implicitly
         *     when missing and counts toward the saved-product limit. `thresholdPercent`
         *     applies to `PRICE_DROP` only (default 5.00 when absent or `null`); for
         *     `BACK_IN_STOCK` and `PRICE_CHANGE` a non-null value is 422
         *     `validation_failed` with `errors[].code = threshold_not_allowed`
         *     (field `thresholdPercent`). Requires a verified e-mail.
         *
         *     `baselinePrice` (`PRICE_DROP` only) is the product's current native
         *     effective price (section 7.4, out-of-stock offers included); `null`
         *     when no offer exists, in which case the alert evaluator stores the
         *     first price it sees and does not fire on that event. Other triggers
         *     store no baseline (`null`).
         *
         *     Uniqueness: one subscription per (caller, product, country, trigger).
         *     When it already exists the answer is 200 with the existing
         *     subscription unchanged, active or not: the threshold, baseline and
         *     `active` are not modified and no limit or verification check runs.
         *     Use `PATCH /me/alerts/{id}` to change the threshold or to reactivate.
         *     Requests of one user are serialized by the row lock, so two parallel
         *     identical requests give one 201 and one 200.
         *
         *     Order of checks: body validation (422) -> country (422
         *     `country_not_found`) -> product (404 `product_not_found`, also for an
         *     unknown id) -> availability (404 `product_not_available_in_country`
         *     unless `AVAILABLE`) -> in one transaction: lock the user's row,
         *     existing subscription (200), e-mail verification (403
         *     `email_not_verified`), active-alert limit (403 `entitlement_exceeded`,
         *     `upgradeHint.messageKey = limits.alert_subscriptions_reached`),
         *     saved-product limit when the implicit saved row is missing (403
         *     `entitlement_exceeded`, `limits.saved_products_reached`), insert
         *     (201). A failed check writes nothing.
         *
         *     Problem codes: `unauthorized` (401), `email_not_verified` (403),
         *     `entitlement_exceeded` (403, alert or saved-product limit reached),
         *     `product_not_found` (404), `product_not_available_in_country` (404),
         *     `validation_failed` (422; `errors[].code` includes
         *     `threshold_not_allowed`, `out_of_range`), `country_not_found` (422),
         *     request body larger than 64 kB (413), body not `application/json`
         *     (415), `internal_error` (500).
         */
        post: operations["createAlert"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/me/alerts/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /**
         * Delete an alert subscription
         * @description Deletes the subscription (its `alert_delivery` rows go with it;
         *     notifications already created stay in the inbox). The saved-product
         *     row is kept. An id that does not exist, belongs to another user or is
         *     not a UUID returns the same 404 (`about:blank`); deleting twice is 404
         *     the second time.
         *
         *     Problem codes: `unauthorized` (401), 404 (`about:blank`),
         *     `internal_error` (500).
         */
        delete: operations["deleteAlert"];
        options?: never;
        head?: never;
        /**
         * Activate, deactivate or change the threshold
         * @description Applies `active` and/or `thresholdPercent` in one transaction that
         *     first locks the caller's row. An id that does not exist, belongs to
         *     another user or is not a UUID returns the same 404 (`about:blank`,
         *     identical body apart from `instance` and `requestId`).
         *
         *     - `active: true` on an inactive subscription (activation): 404
         *       `product_not_available_in_country` unless the product is still
         *       `AVAILABLE` in the country, then 403 `email_not_verified`, then the
         *       active-alert limit (403 `entitlement_exceeded`), then, when the
         *       saved-product row for the product and country is missing (it was
         *       removed), the saved-product limit (403 `entitlement_exceeded`) and
         *       the implicit saved row is re-created. The baseline is reset.
         *     - `active: true` on an active or `active: false` on an inactive
         *       subscription changes nothing and runs no check. `active: false`
         *       on an active subscription deactivates it without any check.
         *     - `thresholdPercent` (`PRICE_DROP` only; on another trigger 422
         *       `validation_failed`, `threshold_not_allowed`; `null` is 422
         *       `validation_failed`): a value different from the stored one is
         *       stored and resets the baseline; the same value changes nothing.
         *     - Baseline reset (`PRICE_DROP` only) = the product's current native
         *       effective price (section 7.4, out-of-stock offers included), `null`
         *       when no offer exists.
         *
         *     Order of checks: body shape (422: empty object, `null` or out-of-range
         *     `thresholdPercent`, wrong types) -> subscription lookup (404) ->
         *     trigger-dependent check (422 `threshold_not_allowed`) -> activation
         *     checks above. A failed check writes nothing.
         *
         *     Problem codes: `unauthorized` (401), `email_not_verified` (403),
         *     `entitlement_exceeded` (403), 404 (`about:blank`),
         *     `product_not_available_in_country` (404, activation only),
         *     `validation_failed` (422; `threshold_not_allowed`, `out_of_range`,
         *     an empty body), request body larger than 64 kB (413), body not
         *     `application/json` (415), `internal_error` (500).
         */
        patch: operations["updateAlert"];
        trace?: never;
    };
    "/me/notifications": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Notification inbox
         * @description Notifications of the caller ordered by `createdAt` descending, then
         *     `id` descending, paged with `page` / `pageSize`. With
         *     `unreadOnly=true` only notifications with `readAt = null` are listed
         *     and `meta.total` counts only those. A page beyond the last returns an
         *     empty `items` array. In-app notifications exist regardless of the
         *     e-mail preference and of e-mail delivery.
         *
         *     `type` selects the payload: alert notifications carry a snapshot of
         *     the product (code, brand, name), the country, the native `currency`
         *     and the before / after effective price and stock state; money in a
         *     payload is always native and never converted (no `meta.fx` here).
         *
         *     Problem codes: `unauthorized` (401), `validation_failed` (422,
         *     `unreadOnly` not `true`/`false`, `page < 1`, `pageSize` outside 1 to
         *     100), `internal_error` (500).
         */
        get: operations["listNotifications"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/me/notifications/{id}/read": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Mark one notification as read
         * @description Sets `readAt` to now when it is `null`; an already read notification
         *     keeps its first `readAt` (idempotent, 204). An id that does not exist,
         *     belongs to another user or is not a UUID returns the same 404
         *     (`about:blank`). No request body.
         *
         *     Problem codes: `unauthorized` (401), 404 (`about:blank`),
         *     `internal_error` (500).
         */
        post: operations["markNotificationRead"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/me/notifications/read-all": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Mark all notifications as read
         * @description Sets `readAt` to now on every notification of the caller whose
         *     `readAt` is `null` (one statement); already read notifications keep
         *     their `readAt`. Always 204, also when nothing was unread. No request
         *     body.
         *
         *     Problem codes: `unauthorized` (401), `internal_error` (500).
         */
        post: operations["markAllNotificationsRead"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/me/notifications/unread-count": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Number of unread notifications
         * @description Number of the caller's notifications with `readAt = null` (equals
         *     `meta.total` of `GET /me/notifications?unreadOnly=true`). Polled by
         *     the web app every 60 seconds while signed in.
         *
         *     Problem codes: `unauthorized` (401), `internal_error` (500).
         */
        get: operations["getUnreadNotificationCount"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/me/subscription/promo": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Activate or extend PRO with a promo code
         * @description Code lookup is case-insensitive. Activating PRO cancels the current
         *     subscription and starts an ACTIVE PRO subscription for the code's
         *     duration; an existing PRO period is extended. Every failure (unknown,
         *     expired, exhausted, already redeemed by this user, or a value that is
         *     not a well-formed code) returns the same 400 `promo_invalid` and counts
         *     against both rate limits.
         *
         *     Problem codes: `unauthorized` (401), `email_not_verified` (403),
         *     `promo_invalid` (400), `conflict_retry` (409), `validation_failed`
         *     (422, only for a missing or null `code` or a body that cannot be read
         *     as this request), `rate_limited` (429, 10 per hour per IP and 5 per hour per user).
         */
        post: operations["redeemPromoCode"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/billing/checkout-session": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Start a hosted checkout for the PRO plan
         * @description Creates a hosted checkout session of the payment provider for the
         *     monthly PRO subscription and returns its URL; the web app redirects the
         *     browser there. No request body. The session is bound to the caller (the
         *     user id travels as the session's client reference and as subscription
         *     metadata), never to an e-mail address. The provider returns the browser
         *     to `<web base URL>/upgrade?checkout=success` or
         *     `<web base URL>/upgrade?checkout=canceled`; the plan changes only when
         *     the provider's signed webhook arrives (`POST /billing/webhooks/stripe`),
         *     never because of the return URL.
         *
         *     Problem codes: `unauthorized` (401), `email_not_verified` (403),
         *     `plan_already_active` (409, the caller's ACTIVE plan is already PRO, or
         *     a provider subscription of the caller has not ended, for example a
         *     past-due one, which is settled in the billing portal),
         *     `rate_limited` (429, 10 per hour per user), `billing_unavailable`
         *     (503, no provider configured or the provider cannot be reached).
         */
        post: operations["createCheckoutSession"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/billing/portal-session": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Open the hosted billing portal
         * @description Creates a session of the payment provider's hosted billing portal for
         *     the caller's provider customer and returns its URL. Payment methods,
         *     invoices and cancellation are handled there; a cancellation reaches the
         *     API through the provider's signed webhook. The portal returns the
         *     browser to `<web base URL>/account`. No request body.
         *
         *     Problem codes: `unauthorized` (401), `no_billing_account` (409, the
         *     caller never completed a provider checkout), `rate_limited` (429, 10 per
         *     hour per user), `billing_unavailable` (503).
         */
        post: operations["createBillingPortalSession"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/billing/webhooks/stripe": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Stripe webhook receiver
         * @description Called by Stripe only. Authenticated by the `Stripe-Signature` header
         *     alone: no bearer token, cookie or CSRF header is used. The raw request
         *     body is verified before it is parsed: HMAC-SHA256 with the endpoint's
         *     signing secret over `<t>.<raw body>`, compared in constant time with
         *     every `v1` entry; the timestamp `t` must lie within 5 minutes of the
         *     server clock. A missing, repeated or malformed header, a header
         *     without a matching `v1` signature, a timestamp outside the window, or
         *     a body that is not a JSON event is answered 400 with one generic
         *     `about:blank` body that names no reason.
         *
         *     Handled event types: `checkout.session.completed`, `invoice.paid`,
         *     `invoice.payment_failed`, `customer.subscription.updated`,
         *     `customer.subscription.deleted`. For each, the API reads the current
         *     state of the referenced subscription from Stripe and applies it under
         *     the user's subscription lock (source `PROVIDER`, the Stripe
         *     subscription id as external reference); the user is identified by the
         *     user id the API attached at checkout, never by e-mail. Applied changes
         *     only move forward: a PRO period end never decreases and an ended
         *     provider subscription never becomes active again, so late or
         *     reordered deliveries cannot downgrade a newer state. Each event id is
         *     applied at most once; a repeated delivery answers 200 without
         *     effect. Other event types, events of the other mode (live or test),
         *     and events that do not belong to a subscription created by this API
         *     answer 200 and are ignored.
         *
         *     Exempt from the client rate limits of the other operations; it has its
         *     own cap (300 per minute per client address) and body limit (256 kB).
         *
         *     Problem codes: `rate_limited` (429), `validation_failed` (413),
         *     `billing_unavailable` (503, no provider configured or Stripe cannot be
         *     reached; Stripe retries the delivery), `internal_error` (500).
         */
        post: operations["receiveStripeWebhook"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/admin/health": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Detailed dependency status
         * @description Requires role ADMIN. Scoring service, database, FX freshness, outbox
         *     backlog and dead letters, and the last run of each scheduled job.
         *     Always answers 200 with the body (also when a component is `DOWN`);
         *     it is evaluated on each call and never cached.
         *
         *     - `scoring`: `UP` when `GET /internal/v1/health` answers 200 within
         *       the scoring client's timeouts, else `DOWN` (an open circuit is
         *       `DOWN` without a call).
         *     - `database`: `UP` when a trivial query succeeds, else `DOWN`.
         *     - `fx`: `newestAsOf` = newest `as_of` over all stored rates (`null`
         *       when none), `rateAgeDays` = current UTC date minus `newestAsOf` in
         *       days, `stale = rateAgeDays > 5`; `status` is `UP` when not stale,
         *       `DEGRADED` when stale or no rate is stored, never `DOWN`;
         *       `unsupportedBases` = the unsupported bases of the last FX refresh
         *       run (empty before the first run).
         *     - `outbox`: the counts of `GET /admin/outbox/stats`; `status` is
         *       `DEGRADED` when `failed > 0` or the oldest pending event is older
         *       than 5 minutes (`oldestPendingAt`), else `UP`. Dead letters are
         *       reported but do not change the status.
         *     - Overall `status`: `DOWN` when `database` is `DOWN`; otherwise
         *       `DEGRADED` when any component is not `UP`; otherwise `UP`. The
         *       public `GET /health` returns this same overall status (and 503 for
         *       `DOWN`) without any detail, from a result cached for at most 10
         *       seconds so that public polling cannot multiply calls to the
         *       scoring service or the database.
         *     - `jobs`: one entry per scheduled job, in the order of the enum, with
         *       the end time and outcome of its last run that obtained the job's
         *       advisory lock since the API started (`null` before the first run;
         *       skipped runs are not recorded).
         *
         *     Problem codes: `unauthorized` (401), `forbidden` (403).
         */
        get: operations["getAdminHealth"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/admin/imports/products": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Import a products document
         * @description Requires role ADMIN. Accepts the products JSON document (products,
         *     nutrients, availability and offers) defined by the JSON Schema
         *     `contracts/schemas/import-products.schema.json`; products are upserted
         *     by `code`. The import is asynchronous: the response is `202` with the
         *     job as created (normally `RUNNING`) and its URL in `Location`; the job
         *     status, summary and row errors are read with
         *     `GET /admin/imports/{jobId}`.
         *
         *     The document is validated as a whole before any write: invalid UTF-8
         *     (`invalid_encoding`), invalid JSON (`invalid_json`), schema violations
         *     (`schema_violation`) and the semantic rules of the schema (for example
         *     `unknown_nutrient`, `invalid_form_code`, `form_nutrient_mismatch`,
         *     `unsupported_iu_form`, `currency_mismatch`, `invalid_offer_url`,
         *     `checked_at_in_future`) are reported as job errors. A document with
         *     more than 50 000 products fails with the single error
         *     `too_many_records`; a string value longer than 4096 characters or a
         *     nesting deeper than 64 levels is `invalid_json`. Parser failures carry
         *     a fixed message with line and column only. Validation stops after
         *     1000 errors: the job then lists those errors and one `too_many_errors`
         *     error. Any error fails the whole job: nothing is written,
         *     `catalog_version` is not bumped and no outbox event is written. Form codes are validated against the form
         *     registry table. A successful import bumps `catalog_version` exactly
         *     once; re-importing the same document changes no row.
         *
         *     Problem codes: `unauthorized` (401), `forbidden` (403), body larger than
         *     20 MB (413), body not `application/json` (415), `validation_failed`
         *     (422, empty request body only; content errors are job errors),
         *     `internal_error` (500).
         */
        post: operations["importProducts"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/admin/imports/offers": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Import an offers CSV file
         * @description Requires role ADMIN. Upserts offers by natural key (product, country,
         *     retailer) within the countries covered by the file; for every
         *     (product, country) pair in the file, offers of retailers absent from the
         *     file are deleted. Rows reference products by `product_code`. The file
         *     format and every error code are defined in
         *     `contracts/schemas/offers-csv.columns.md`. The import is asynchronous:
         *     the response is `202` with the job as created (normally `RUNNING`) and
         *     its URL in `Location`.
         *
         *     File-level failures (`invalid_encoding`, `invalid_csv` for an
         *     unparseable file such as an unterminated quoted field,
         *     `invalid_header`, `too_many_records` for more than 200 000 data rows)
         *     and row errors (for example `wrong_column_count`, `field_too_long`,
         *     `currency_mismatch`, `invalid_offer_url`, `checked_at_in_future`) are
         *     reported on the job; validation stops after 1000 errors and adds one
         *     `too_many_errors` error. Any error fails the whole job: nothing is
         *     written, `catalog_version` is not bumped and no `OFFERS_CHANGED` event
         *     is written. A successful import bumps `catalog_version` exactly once;
         *     re-importing the same file changes no row.
         *
         *     Problem codes: `unauthorized` (401), `forbidden` (403), file larger than
         *     20 MB (413), request not `multipart/form-data` (415),
         *     `validation_failed` (422: missing `file` part, or a multipart body the
         *     server cannot parse, for example a part whose file name contains
         *     control characters - field `file`, code `invalid_format`; the file
         *     name is never used; content errors are job errors),
         *     `internal_error` (500).
         */
        post: operations["importOffers"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/admin/imports/{jobId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Import job status, summary and row errors
         * @description Requires role ADMIN. A `RUNNING` job has `finishedAt` and `summary`
         *     `null` and an empty `errors` array; a `SUCCEEDED` job has the write counts in `summary` and
         *     no errors; a `FAILED` job lists every error, ordered by `rowNumber`
         *     and then by detection order, and its `summary` carries the rows read
         *     with all write counts 0.
         *
         *     Problem codes: `unauthorized` (401), `forbidden` (403), 404
         *     (`about:blank`, unknown id or an id that is not a UUID).
         */
        get: operations["getImportJob"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/admin/users": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Search users
         * @description Requires role ADMIN. Never returns password hashes. Results are ordered
         *     newest first.
         *
         *     Problem codes: `unauthorized` (401), `forbidden` (403),
         *     `validation_failed` (422, including a `q` that contains control
         *     characters).
         */
        get: operations["listUsers"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/admin/users/{id}/subscription": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Grant PRO manually
         * @description Requires role ADMIN. Activates PRO for `durationDays` with source
         *     `MANUAL`, or extends an existing PRO period.
         *
         *     Problem codes: `unauthorized` (401), `forbidden` (403), 404
         *     (`about:blank`), `conflict_retry` (409), `validation_failed` (422).
         */
        post: operations["grantSubscription"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/admin/users/{id}/status": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Enable or disable a user
         * @description Requires role ADMIN. Setting `DISABLED` revokes all refresh-token
         *     families of the user in the same transaction. Every `/admin/**`
         *     request re-reads the caller's role and status, so a disabled or
         *     demoted administrator is rejected immediately (`forbidden`, 403). An
         *     administrator cannot change their own status (`forbidden`, 403).
         *
         *     Problem codes: `unauthorized` (401), `forbidden` (403), 404
         *     (`about:blank`), `validation_failed` (422).
         */
        post: operations["setUserStatus"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/admin/promo-codes": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * List promo codes
         * @description Requires role ADMIN.
         *
         *     Problem codes: `unauthorized` (401), `forbidden` (403).
         */
        get: operations["listPromoCodes"];
        put?: never;
        /**
         * Generate a promo code
         * @description Requires role ADMIN. The code is generated server-side as the optional
         *     prefix, a hyphen and 16 Crockford base32 characters from a CSPRNG
         *     (80 bits of entropy); without a prefix the code is the 16 characters
         *     alone. The generated code is returned in this response.
         *
         *     Problem codes: `unauthorized` (401), `forbidden` (403),
         *     `validation_failed` (422).
         */
        post: operations["createPromoCode"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/admin/promo-codes/{code}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /**
         * Delete a promo code
         * @description Requires role ADMIN. Lookup is case-insensitive.
         *
         *     Problem codes: `unauthorized` (401), `forbidden` (403), 404
         *     (`about:blank`).
         */
        delete: operations["deletePromoCode"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/admin/fx/refresh": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Run the FX refresh job now
         * @description Requires role ADMIN. Runs the FX refresh job (`FxRefreshJob`) once,
         *     synchronously, under the job's advisory lock: for every supported base
         *     currency one provider call for all other supported currencies, each
         *     result upserted in its own short transaction. A failed call (timeout,
         *     non-2xx, empty array, missing quote, mixed dates) is logged with a
         *     metric, leaves the stored rates of that base in use and does not stop
         *     the other bases; the response is still 200 and lists the base in
         *     `failedBases`. With four supported currencies a complete run upserts
         *     12 rows.
         *
         *     A base is unsupported only when the provider answers 422 with the
         *     message `invalid currency: <base>` naming exactly the requested base:
         *     it is listed in `unsupportedBases` (not in `failedBases`), logged as
         *     an operator warning and counted, and gets no rate. Any other
         *     rejection (another text, another currency named, 5xx, timeout, empty
         *     answer) is a failure of that base.
         *
         *     Problem codes: `unauthorized` (401), `forbidden` (403),
         *     `conflict_retry` (409, the refresh job is running at this moment and
         *     holds its lock; nothing was fetched).
         */
        post: operations["refreshFxRates"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/admin/outbox/stats": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Outbox backlog counts
         * @description Requires role ADMIN. `pending` counts unprocessed events
         *     (`processed_at IS NULL`, claimed or not), `failed` the unprocessed
         *     events with at least one failed attempt (`attempts > 0`),
         *     `deadLetter` the retained events processed with outcome
         *     `DEAD_LETTER`, and `oldestPendingAt` is the `created_at` of the
         *     oldest unprocessed event. One read-only query; no event is claimed or
         *     changed.
         *
         *     Problem codes: `unauthorized` (401), `forbidden` (403),
         *     `internal_error` (500).
         */
        get: operations["getOutboxStats"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
}
export type webhooks = Record<string, never>;
export interface components {
    schemas: {
        /** @example 8c1d2e3f-4a5b-4c6d-9e7f-0a1b2c3d4e5f */
        RequestId: string;
        /**
         * @description ISO 3166-1 alpha-2 country code of the country registry.
         * @example TR
         */
        CountryCode: string;
        /**
         * @description ISO 4217 currency code.
         * @example TRY
         */
        CurrencyCode: string;
        /**
         * @description Code of the nutrient registry.
         * @example VIT_C
         */
        NutrientCode: string;
        /**
         * @description Human-readable business key of a product.
         * @example GOLD-A
         */
        ProductCode: string;
        /**
         * @description Promo code; optional prefix and hyphen followed by 16 Crockford base32 characters. Matched case-insensitively.
         * @example LAUNCH-7K3M9QXT2B4N6PRW
         */
        PromoCodeValue: string;
        /** @enum {string} */
        Locale: "tr" | "en";
        /**
         * Format: email
         * @description E-mail address. Control characters are rejected (422
         *     `validation_failed`). Addresses are stored, compared and returned
         *     without surrounding white space and in lower case.
         * @example user@example.com
         */
        Email: string;
        /** @description Monetary amount rounded once to 4 decimals. */
        Money: number;
        /** @enum {string} */
        HealthStatus: "UP" | "DEGRADED" | "DOWN";
        /** @enum {string} */
        NutrientCategory: "VITAMIN" | "MINERAL" | "COENZYME" | "OTHER";
        /** @enum {string} */
        CanonicalUnit: "mg" | "mcg";
        /**
         * @description Accepted query unit. `IU` is accepted only for `VIT_D`.
         * @enum {string}
         */
        InputUnit: "mg" | "mcg" | "g" | "IU";
        /** @enum {string} */
        DosageForm: "TABLET" | "CAPSULE" | "SOFTGEL" | "GUMMY" | "POWDER" | "LIQUID";
        /** @enum {string} */
        AvailabilityStatus: "AVAILABLE" | "NOT_SOLD" | "DISCONTINUED";
        /** @enum {string} */
        ReferenceSource: "REGULATORY" | "MARKET_MEDIAN" | "PRESENCE_ONLY";
        /** @enum {string} */
        StockFilter: "IN_STOCK_ONLY" | "INCLUDE_OUT_OF_STOCK";
        /** @enum {string} */
        RankingMode: "PRICE" | "RICHNESS" | "VALUE" | "CUSTOM";
        /** @enum {string} */
        Verdict: "WORTH_IT" | "MARGINAL" | "NOT_WORTH_IT" | "DOMINATES" | "EQUIVALENT" | "INSUFFICIENT_DATA";
        /**
         * @description Product-level safety and data flags. `FX_STALE`, `FX_UNAVAILABLE` and
         *     `UNAVAILABLE_IN_COUNTRY` are added by the API; the others come from the
         *     scoring engine or the effective-price resolution. `FX_STALE` is set
         *     on every money-bearing item when `meta.fx.stale` is `true`,
         *     `FX_UNAVAILABLE` when `meta.fx.status` is `UNAVAILABLE`.
         *     `UNAVAILABLE_IN_COUNTRY` is used only on saved products
         *     (`SavedProduct.flags`) whose availability is not `AVAILABLE`; alert
         *     list items show the same state through `product.availabilityStatus`.
         *     Catalog and recommendation responses never contain unavailable
         *     products and never carry it.
         * @enum {string}
         */
        ProductFlagCode: "EXCEEDS_UL" | "UL_FORM_DEPENDENT" | "CONVERSION_ASSUMED" | "PRICE_STALE" | "OUT_OF_STOCK" | "MARKET_MEDIAN_REFERENCE_USED" | "PRESENCE_ONLY_NUTRIENTS" | "BELOW_PRESENCE_FLOOR" | "FX_STALE" | "FX_UNAVAILABLE" | "UNAVAILABLE_IN_COUNTRY";
        /** @enum {string} */
        DeltaRowFlag: "NEW_IN_B" | "REMOVED_IN_B" | "DIMINISHING" | "ABOVE_CEILING_NO_CREDIT" | "ABOVE_UL_NO_CREDIT";
        /** @enum {string} */
        Role: "USER" | "ADMIN";
        /** @enum {string} */
        UserStatus: "ACTIVE" | "DISABLED";
        /** @enum {string} */
        PlanCode: "FREE" | "PRO";
        /** @enum {string} */
        SubscriptionStatus: "ACTIVE" | "EXPIRED" | "CANCELED";
        /** @enum {string} */
        SubscriptionSource: "MANUAL" | "PROMO" | "PROVIDER";
        /** @enum {string} */
        AlertTrigger: "PRICE_DROP" | "BACK_IN_STOCK" | "PRICE_CHANGE";
        /** @enum {string} */
        NotificationType: "ALERT_PRICE_DROP" | "ALERT_BACK_IN_STOCK" | "ALERT_PRICE_CHANGE" | "NOTIFICATION_CAP_REACHED" | "PLAN_ACTIVATED" | "PLAN_EXPIRED";
        /** @enum {string} */
        ImportJobKind: "PRODUCTS_JSON" | "OFFERS_CSV";
        /** @enum {string} */
        ImportJobStatus: "RUNNING" | "SUCCEEDED" | "FAILED";
        /**
         * @description `UNAVAILABLE` when no rate exists for the pair; money is then returned in the native currency.
         * @enum {string}
         */
        FxStatus: "OK" | "UNAVAILABLE";
        /** @description RFC 9457 problem details. */
        Problem: {
            /**
             * Format: uri
             * @description `https://vitadelta.dev/problems/<code>`, or `about:blank` for generic HTTP errors.
             * @enum {string}
             */
            type: "https://vitadelta.dev/problems/validation_failed" | "https://vitadelta.dev/problems/country_not_found" | "https://vitadelta.dev/problems/product_not_found" | "https://vitadelta.dev/problems/product_not_available_in_country" | "https://vitadelta.dev/problems/currency_mismatch" | "https://vitadelta.dev/problems/scoring_unavailable" | "https://vitadelta.dev/problems/unauthorized" | "https://vitadelta.dev/problems/forbidden" | "https://vitadelta.dev/problems/invalid_credentials" | "https://vitadelta.dev/problems/account_disabled" | "https://vitadelta.dev/problems/email_not_verified" | "https://vitadelta.dev/problems/token_invalid" | "https://vitadelta.dev/problems/token_expired" | "https://vitadelta.dev/problems/refresh_reuse_detected" | "https://vitadelta.dev/problems/quota_exceeded" | "https://vitadelta.dev/problems/entitlement_exceeded" | "https://vitadelta.dev/problems/promo_invalid" | "https://vitadelta.dev/problems/conflict_retry" | "https://vitadelta.dev/problems/fx_unsupported_currency" | "https://vitadelta.dev/problems/rate_limited" | "https://vitadelta.dev/problems/unsupported_iu_form" | "https://vitadelta.dev/problems/invalid_offer_url" | "https://vitadelta.dev/problems/import_failed" | "https://vitadelta.dev/problems/billing_unavailable" | "https://vitadelta.dev/problems/plan_already_active" | "https://vitadelta.dev/problems/no_billing_account" | "https://vitadelta.dev/problems/internal_error" | "about:blank";
            /** @description Short, human-readable summary of the problem type. */
            title: string;
            /** Format: int32 */
            status: number;
            /** @description Human-readable explanation of this occurrence. */
            detail?: string;
            /**
             * Format: uri-reference
             * @description Request path of this occurrence.
             */
            instance?: string;
            requestId: components["schemas"]["RequestId"];
            /** @description Field errors; present for `validation_failed`. */
            errors?: components["schemas"]["FieldError"][];
        };
        FieldError: {
            /** @description JSON path of the offending field, for example `requiredNutrients[0].unit`. */
            field: string;
            /** @description Machine-readable reason, for example `weights_all_zero`, `unit_requires_form`, `required`, `out_of_range`, `unknown_nutrient_code`, `lambda_requires_custom`, `same_product`, `no_offer`, `no_stock`, `value_out_of_range`, `threshold_not_allowed`. */
            code: string;
            message: string;
        };
        /** @description What the web app renders as call to action for a limit error. */
        UpgradeHint: {
            /** @description i18n key of the notice text. */
            messageKey: string;
            /** @description Relative path of the call-to-action link. */
            ctaPath: string;
        };
        /** @description `quota_exceeded` with its extension members. */
        QuotaExceededProblem: components["schemas"]["Problem"] & {
            /** @constant */
            type?: "https://vitadelta.dev/problems/quota_exceeded";
            limit: number;
            used: number;
            /** @enum {string} */
            action: "SIGN_UP";
            upgradeHint: components["schemas"]["UpgradeHint"];
        };
        /** @description `entitlement_exceeded` with its extension members. */
        EntitlementExceededProblem: components["schemas"]["Problem"] & {
            /** @constant */
            type?: "https://vitadelta.dev/problems/entitlement_exceeded";
            limit: number;
            used: number;
            plan: components["schemas"]["PlanCode"];
            upgradeTo: components["schemas"]["PlanCode"];
            upgradeHint: components["schemas"]["UpgradeHint"];
        };
        Health: {
            status: components["schemas"]["HealthStatus"];
        };
        LegalInfo: {
            /**
             * @description Controller contact shown on the privacy notice.
             * @example privacy@example.com
             */
            privacyContact: string;
        };
        /** @description An empty JSON object. */
        EmptyObject: Record<string, never>;
        Country: {
            code: components["schemas"]["CountryCode"];
            nameEn: string;
            currency: components["schemas"]["CurrencyCode"];
            /** @example EU_NRV */
            referenceSetCode: string;
            /** @example EFSA */
            ulSetCode: string;
            defaultLocale: components["schemas"]["Locale"];
        };
        CountryList: {
            items: components["schemas"]["Country"][];
        };
        Nutrient: {
            code: components["schemas"]["NutrientCode"];
            nameEn: string;
            category: components["schemas"]["NutrientCategory"];
            canonicalUnit: components["schemas"]["CanonicalUnit"];
            /** @description Presence floor per day in the canonical unit; `null` when unknown. */
            presenceFloorPerDay: number | null;
            sortOrder: number;
            /** @description Reference sets that define a value for this nutrient. */
            referenceSetCodes: string[];
            /** @description Registered forms of this nutrient from the form registry, ordered by `code`; empty when the nutrient has none. Product rows may also carry the sentinel `UNKNOWN`, which is not listed. */
            forms?: components["schemas"]["NutrientForm"][];
        };
        NutrientForm: {
            /** @example D3_CHOLECALCIFEROL */
            code: string;
            /** @example Vitamin D3 (cholecalciferol) */
            nameEn: string;
        };
        NutrientList: {
            items: components["schemas"]["Nutrient"][];
        };
        NumberRange: {
            min: number;
            max: number;
        };
        /** @description Category weights in [0.0, 2.0]. Missing categories use the default weight. */
        CategoryWeights: {
            VITAMIN?: number;
            MINERAL?: number;
            COENZYME?: number;
            OTHER?: number;
        };
        /** @description Default weight of every category. */
        DefaultCategoryWeights: WithRequired<components["schemas"]["CategoryWeights"], "VITAMIN" | "MINERAL" | "COENZYME" | "OTHER">;
        /** @description Per-nutrient weight multipliers in [0.0, 3.0] (default 1.0). Keys must be registry codes; 0 removes the nutrient from the score but not from the breakdown. */
        NutrientOverrides: {
            [key: string]: number;
        };
        /** @description Weights must not make every registry nutrient 0 (422 `validation_failed`, `weights_all_zero`). */
        Weights: {
            categories?: components["schemas"]["CategoryWeights"];
            overrides?: components["schemas"]["NutrientOverrides"];
        };
        DefaultWeights: {
            categories: components["schemas"]["DefaultCategoryWeights"];
            categoryRange: components["schemas"]["NumberRange"];
            overrideDefault: number;
            overrideRange: components["schemas"]["NumberRange"];
            lambdaDefault: number;
            lambdaRange: components["schemas"]["NumberRange"];
            /** @description Scoring-engine parameters from the scoring service's `GET /internal/v1/health`; `null` while the API has not obtained them since start-up. */
            engine: components["schemas"]["EngineParameters"] | null;
        };
        /**
         * @description Read-only scoring-engine parameters (scoring contract `EngineConfig`,
         *     renamed to camelCase, values copied unrounded). Display only: the web
         *     app never computes a score or a verdict from them.
         */
        EngineParameters: {
            /** @description Slope `beta` of the logarithmic segment of the utility curve (scoring `diminishing_beta`). */
            diminishingBeta: number;
            /** @description Utility ceiling `u_max` (scoring `utility_ceiling`). */
            utilityCeiling: number;
            /** @description Largest compare `ratio` that still yields `WORTH_IT` (scoring `verdict_worth_it_max_ratio`). */
            verdictWorthItMaxRatio: number;
            /** @description Largest compare `ratio` that still yields `MARGINAL`; above it the verdict is `NOT_WORTH_IT` (scoring `verdict_marginal_max_ratio`). */
            verdictMarginalMaxRatio: number;
        };
        Ranking: {
            mode: components["schemas"]["RankingMode"];
            /** @description Weight of content against price for `CUSTOM` only (default 0.5); sent with another mode it is rejected with 422. */
            lambda?: number;
        };
        RequiredNutrient: {
            code: components["schemas"]["NutrientCode"];
            /** @description Minimum amount per day; when absent the nutrient only has to be present. */
            minAmountPerDay?: number;
            unit?: components["schemas"]["InputUnit"];
        };
        RecommendationQueryRequest: {
            country: components["schemas"]["CountryCode"];
            /** @default IN_STOCK_ONLY */
            stockFilter: components["schemas"]["StockFilter"];
            /** @description Defaults to `{ "mode": "VALUE" }`. */
            ranking?: components["schemas"]["Ranking"];
            weights?: components["schemas"]["Weights"];
            requiredNutrients?: components["schemas"]["RequiredNutrient"][];
            /** @default false */
            excludeExceedingUl: boolean;
            displayCurrency?: components["schemas"]["CurrencyCode"];
            /**
             * Format: int32
             * @default 1
             */
            page: number;
            /**
             * Format: int32
             * @default 20
             */
            pageSize: number;
        };
        /**
         * @description Conversion applied to every monetary field: displayed value = unrounded
         *     native value x `rate`, rounded once to 4 decimals; a value is never
         *     converted from an already rounded figure. `base` is the country's
         *     native currency and `quote` the resolved display currency. The rate is
         *     the stored row of the exact pair `(base, quote)` with the largest
         *     `as_of` (never inverted, never derived through a third currency).
         *     For the identity pair `rate` is 1, `asOf`/`source` are `null`,
         *     `stale` is `false` and `status` is `OK`. With `status = UNAVAILABLE`
         *     (no stored row for the pair) `rate`, `asOf` and `source` are `null`,
         *     `stale` is `false`, money stays in the native currency and every
         *     money-bearing item carries the flag `FX_UNAVAILABLE`. A stored rate so
         *     small that a non-zero native amount of the response would be displayed
         *     as `0.0000` is not applied either (decided for the whole response, so
         *     one response never mixes currencies): `status` is `UNAVAILABLE`, money
         *     stays native with `FX_UNAVAILABLE` as above, `stale` is `false`, and
         *     `rate`, `asOf` and `source` identify the stored row that was not used.
         *     With `stale = true` every money-bearing item carries `FX_STALE`.
         *     `valueScore` is never converted.
         */
        FxMeta: {
            base: components["schemas"]["CurrencyCode"];
            quote: components["schemas"]["CurrencyCode"];
            /** @description Stored rate (`numeric(18,8)`), written with its 8 decimals and never rounded; `1` for the identity pair. */
            rate: number | null;
            /**
             * Format: date
             * @description ECB publication date of the rate (the provider's `date`), not the fetch time.
             */
            asOf: string | null;
            /** @enum {string|null} */
            source: "FRANKFURTER" | null;
            /** @description `true` when `asOf` is more than 5 days before the current UTC date (`fx_stale_after_days = 5`). */
            stale: boolean;
            status: components["schemas"]["FxStatus"];
        };
        MoneyMeta: {
            nativeCurrency: components["schemas"]["CurrencyCode"];
            /**
             * @description Currency of every monetary field (display currency). Equals
             *     `nativeCurrency` when `fx.status` is `UNAVAILABLE`; `fx.quote` then
             *     names the requested currency.
             */
            currency: components["schemas"]["CurrencyCode"];
            fx: components["schemas"]["FxMeta"];
        };
        /** @description In-market products that are not ranked, counted in filter order. `noStock` and `noOffer` are decided by the API before scoring; `requiredNutrients` and `exceedsUl` come from the scoring service. */
        ExcludedCounts: {
            /** @description `IN_STOCK_ONLY` only: products with offers but none in stock. */
            noStock: number;
            /** @description Products without any offer in the country. */
            noOffer: number;
            requiredNutrients: number;
            exceedsUl: number;
        };
        /** @description ANONYMOUS: `shown = min(3, total)`, `locked = total - shown`, `reason = SIGN_UP_REQUIRED` when `locked > 0`, else `null`. USER and ADMIN: `shown` is the number of items on this page, `locked = 0`, `reason = null`. */
        Visibility: {
            shown: number;
            total: number;
            locked: number;
            /** @enum {string|null} */
            reason: "SIGN_UP_REQUIRED" | null;
        };
        AnonymousPrincipal: {
            /** @constant */
            type: "ANONYMOUS";
            queriesUsed: number;
            queriesLimit: number;
        };
        UserPrincipal: {
            /** @enum {string} */
            type: "USER" | "ADMIN";
            plan: components["schemas"]["PlanCode"];
        };
        PrincipalSummary: components["schemas"]["AnonymousPrincipal"] | components["schemas"]["UserPrincipal"];
        QueryMeta: {
            country: components["schemas"]["CountryCode"];
            nativeCurrency: components["schemas"]["CurrencyCode"];
            currency: components["schemas"]["CurrencyCode"];
            fx: components["schemas"]["FxMeta"];
            /** Format: int64 */
            catalogVersion: number;
            /** @description Number of scored candidates (in market and with an effective price under `stockFilter`) before the required-nutrient and upper-limit filters. */
            marketSize: number;
            /**
             * @description Market marginal rate (median of cost per day per content point over
             *     the `marketSize` candidates with content above 0) in `currency`.
             *     `null` when fewer than three of them have content above 0; unlike
             *     compare, the query has no single-product fallback.
             */
            mRef: number | null;
            excluded: components["schemas"]["ExcludedCounts"];
            visibility: components["schemas"]["Visibility"];
            /** @description Caller of this query; for `ANONYMOUS`, `queriesUsed` includes this query. */
            principal: components["schemas"]["PrincipalSummary"];
            /** @description Requested page, echoed also when it is beyond the last page. */
            page: number;
            pageSize: number;
            /** @description Number of ranked products after all filters (`marketSize - excluded.requiredNutrients - excluded.exceedsUl`); equals `visibility.total`. */
            total: number;
        };
        Cost: {
            /** @description Effective price in the display currency. */
            effectivePrice: components["schemas"]["Money"];
            /** @description Effective price in the country's native currency. */
            effectivePriceNative: components["schemas"]["Money"];
            currency: components["schemas"]["CurrencyCode"];
            servingsPerContainer: number;
            daysSupply: number;
            /** @description Cost per day in the display currency. */
            costPerDay: components["schemas"]["Money"];
            /** @description Cost per day in the country's native currency. */
            costPerDayNative: components["schemas"]["Money"];
            priceStale: boolean;
            outOfStock: boolean;
        };
        /** @description Scoring flags first, in the scoring service's order, then the API flags `FX_STALE`, `FX_UNAVAILABLE`; each code at most once. On saved products that are not `AVAILABLE` the only flag is `UNAVAILABLE_IN_COUNTRY`. */
        ProductFlag: {
            code: components["schemas"]["ProductFlagCode"];
            /** @description Affected nutrients, sorted ascending, for `EXCEEDS_UL`, `UL_FORM_DEPENDENT`, `CONVERSION_ASSUMED`, `MARKET_MEDIAN_REFERENCE_USED`, `PRESENCE_ONLY_NUTRIENTS` and `BELOW_PRESENCE_FLOOR`; empty for `PRICE_STALE`, `OUT_OF_STOCK`, `FX_STALE`, `FX_UNAVAILABLE` and `UNAVAILABLE_IN_COUNTRY`. */
            nutrientCodes: components["schemas"]["NutrientCode"][];
        };
        /** @description One scored nutrient of a product. Breakdown rows are ordered by `code` ascending. */
        BreakdownRow: {
            code: components["schemas"]["NutrientCode"];
            category: components["schemas"]["NutrientCategory"];
            amountPerDay: number;
            ulAmountPerDay: number;
            unit: components["schemas"]["CanonicalUnit"];
            /** @description Reference amount per day; `null` under `PRESENCE_ONLY`. */
            reference: number | null;
            referenceSource: components["schemas"]["ReferenceSource"];
            /** @description Coverage ratio after the upper-limit cap; `null` under `PRESENCE_ONLY`. */
            coverageRatio: number | null;
            utility: number;
            weight: number;
            /** @description weight x utility. */
            contribution: number;
            cappedAtUl: boolean;
            cappedAtUMax: boolean;
            /** @description The applicable upper limit is lower than the resolved reference. */
            ulBelowReference: boolean;
            /** @description `PRESENCE_ONLY` row below 10 % of the presence floor (utility 0). */
            belowPresenceFloor: boolean;
        };
        SelectedOffer: {
            retailer: string;
            /** Format: uri */
            url: string;
            /** Format: date-time */
            checkedAt: string;
        };
        /**
         * @description Where the product's label data comes from. Synthetic fixture products
         *     have `synthetic = true`. Real label snapshots (`source = DSLD`) have
         *     `synthetic = false`, the label id in `sourceRef` and the label's entry
         *     date in `sourceEntryDate`, which the web app shows as the snapshot
         *     date. Offer prices in this release are demo values for every product.
         */
        ProductProvenance: {
            synthetic: boolean;
            /** @example DSLD */
            source: string | null;
            /** @example 239077 */
            sourceRef: string | null;
            /**
             * Format: date
             * @example 2020-11-21
             */
            sourceEntryDate: string | null;
        };
        /** @description Scored product with product metadata and the caller's saved state. */
        RankedProduct: {
            /** Format: uuid */
            productId: string;
            code: components["schemas"]["ProductCode"];
            /** @description Position in the full ranking (not in the page). */
            rank: number;
            brand: string;
            name: string;
            dosageForm: components["schemas"]["DosageForm"];
            provenance: components["schemas"]["ProductProvenance"];
            cost: components["schemas"]["Cost"];
            /** @description Raw content score C, used for ranking. */
            contentScore: number;
            contentScoreDisplay: number;
            /** @description Value score V = C / cost per day, per unit of `meta.nativeCurrency`; never converted. */
            valueScore: number;
            valueScoreDisplay: number;
            /** @description Score S for `CUSTOM` mode; `null` for other modes. */
            customScore: number | null;
            flags: components["schemas"]["ProductFlag"][];
            breakdown: components["schemas"]["BreakdownRow"][];
            /** @description First 5 breakdown rows by contribution descending. */
            topContributors: components["schemas"]["BreakdownRow"][];
            selectedOffer: components["schemas"]["SelectedOffer"];
            /** @description Whether the caller has a saved-product row for this product in `meta.country`; `false` for anonymous callers. Read per request after paging, never cached with the ranking. */
            saved: boolean;
            /** @description The caller's alert subscriptions (active and inactive) on this product in `meta.country`, ordered by `trigger` (`PRICE_DROP`, `BACK_IN_STOCK`, `PRICE_CHANGE`); empty for anonymous callers. Read per request after paging, never cached with the ranking. */
            alerts: components["schemas"]["AlertSubscription"][];
        };
        RecommendationQueryResponse: {
            meta: components["schemas"]["QueryMeta"];
            items: components["schemas"]["RankedProduct"][];
        };
        CompareRequest: {
            country: components["schemas"]["CountryCode"];
            /** Format: uuid */
            baselineProductId: string;
            /** Format: uuid */
            candidateProductId: string;
            /** @default IN_STOCK_ONLY */
            stockFilter: components["schemas"]["StockFilter"];
            weights?: components["schemas"]["Weights"];
            displayCurrency?: components["schemas"]["CurrencyCode"];
        };
        CompareMeta: {
            country: components["schemas"]["CountryCode"];
            nativeCurrency: components["schemas"]["CurrencyCode"];
            currency: components["schemas"]["CurrencyCode"];
            fx: components["schemas"]["FxMeta"];
            /** Format: int64 */
            catalogVersion: number;
            /** @description De-duplicated size of the market set used for `mRef`, including A and B. */
            marketSize: number;
        };
        /** @description One side of the comparison. The scoring service's per-product breakdown is not exposed here; per-nutrient detail is in `rows`. */
        ComparedProduct: {
            /** Format: uuid */
            productId: string;
            code: components["schemas"]["ProductCode"];
            brand: string;
            name: string;
            dosageForm: components["schemas"]["DosageForm"];
            provenance: components["schemas"]["ProductProvenance"];
            cost: components["schemas"]["Cost"];
            contentScore: number;
            /** @description Per unit of `meta.nativeCurrency`; never converted. */
            valueScore: number;
            flags: components["schemas"]["ProductFlag"][];
            selectedOffer: components["schemas"]["SelectedOffer"];
        };
        /** @description Per-nutrient delta over the union of nutrients in A and B, ordered by `code` ascending. Amounts are daily amounts; an amount of 0 means the nutrient is absent (utility 0, ratio 0, or `null` under `PRESENCE_ONLY`). Rows with `deltaAmount = 0` carry no flags. */
        DeltaRow: {
            code: components["schemas"]["NutrientCode"];
            category: components["schemas"]["NutrientCategory"];
            amountA: number;
            amountB: number;
            deltaAmount: number;
            unit: components["schemas"]["CanonicalUnit"];
            /** @description Coverage ratio of A; `null` under `PRESENCE_ONLY`. */
            rA: number | null;
            /** @description Coverage ratio of B; `null` under `PRESENCE_ONLY`. */
            rB: number | null;
            uA: number;
            uB: number;
            weight: number;
            /** @description weight x (uB - uA). */
            deltaContribution: number;
            flags: components["schemas"]["DeltaRowFlag"][];
        };
        /**
         * @description Parameters of the explanation key. `deltaCostPerDay`, `marginalRate`
         *     and `mRef` are money-typed: they are converted to the display currency
         *     like every other monetary field, and `currency` is rewritten to the
         *     display currency (the native currency while `meta.fx.status` is
         *     `UNAVAILABLE`). Parameters whose value is `null` are omitted; every
         *     number is rounded once to 4 decimals.
         */
        ExplanationParams: {
            currency: components["schemas"]["CurrencyCode"];
            deltaCostPerDay?: components["schemas"]["Money"];
            deltaContent?: number;
            marginalRate?: components["schemas"]["Money"];
            mRef?: components["schemas"]["Money"];
            ratio?: number;
        };
        /** @description i18n key with parameters; never a prose sentence. */
        Explanation: {
            /**
             * @example explain.verdict.not_worth_it
             * @enum {string}
             */
            key: "explain.verdict.worth_it" | "explain.verdict.marginal" | "explain.verdict.not_worth_it" | "explain.verdict.dominates" | "explain.verdict.equivalent" | "explain.verdict.insufficient_data";
            params: components["schemas"]["ExplanationParams"];
        };
        /**
         * @description A is the cheaper product by cost per day. `deltaCost >= 0`; on equal
         *     cost `deltaContent >= 0`. `marginalRate` and `ratio` are `null` unless
         *     `deltaCost > 0`, `deltaContent > 0` and `mRef` is not `null`.
         */
        CompareResponse: {
            meta: components["schemas"]["CompareMeta"];
            /** @description `true` when A is not the requested baseline. */
            swapped: boolean;
            verdict: components["schemas"]["Verdict"];
            productA: components["schemas"]["ComparedProduct"];
            productB: components["schemas"]["ComparedProduct"];
            /** @description Cost per day of B minus A in the display currency. */
            deltaCost: components["schemas"]["Money"];
            /** @description Cost per day of B minus A in the native currency. */
            deltaCostNative: components["schemas"]["Money"];
            /** @description Content score of B minus A. */
            deltaContent: number;
            /** @description deltaCost / deltaContent in the display currency. */
            marginalRate: number | null;
            /** @description Market marginal rate in the display currency (median of cost per day per content point over market products with content above 0; with fewer than three such products, A's cost per day per content point; `null` when A's content is 0). */
            mRef: number | null;
            /** @description marginalRate / mRef. */
            ratio: number | null;
            rows: components["schemas"]["DeltaRow"][];
            explanation: components["schemas"]["Explanation"];
        };
        PageMeta: {
            page: number;
            pageSize: number;
            total: number;
        };
        ProductListMeta: {
            country: components["schemas"]["CountryCode"];
            nativeCurrency: components["schemas"]["CurrencyCode"];
            currency: components["schemas"]["CurrencyCode"];
            fx: components["schemas"]["FxMeta"];
            page: number;
            pageSize: number;
            total: number;
        };
        ProductSummary: {
            /** Format: uuid */
            id: string;
            code: components["schemas"]["ProductCode"];
            brand: string;
            name: string;
            dosageForm: components["schemas"]["DosageForm"];
            unitsPerContainer: number;
            unitsPerServing: number;
            servingsPerDay: number;
            provenance: components["schemas"]["ProductProvenance"];
            /** @description Effective-price cost with out-of-stock offers included; `null` when the product has no offer. */
            cost: components["schemas"]["Cost"] | null;
            flags: components["schemas"]["ProductFlag"][];
        };
        ProductPage: {
            meta: components["schemas"]["ProductListMeta"];
            items: components["schemas"]["ProductSummary"][];
        };
        ProductNutrient: {
            code: components["schemas"]["NutrientCode"];
            category: components["schemas"]["NutrientCategory"];
            /** @description Amount per serving in the canonical unit. */
            amountPerServing: number;
            /** @description Amount per day in the canonical unit. */
            amountPerDay: number;
            unit: components["schemas"]["CanonicalUnit"];
            /** @description UL-relevant amount per serving when it differs from the scored amount; `null` means the full amount is UL-relevant. */
            ulAmountPerServing: number | null;
            /**
             * @description Registry form code, `UNKNOWN` when the label form is not in the registry or not stated.
             * @example UNKNOWN
             */
            formCode: string;
            /** @description Form as printed on the label. */
            formLabel: string | null;
            /** @description `true` when an IU conversion assumed a form. */
            conversionAssumed: boolean;
            /**
             * @description Regulatory reference of the requested country's reference set for
             *     this nutrient; `null` when the set defines no value for it. Catalog
             *     fact, independent of weights and of the market (no market-median or
             *     presence-only fallback here). Always written by the API; optional in
             *     the schema only for compatibility.
             */
            reference?: components["schemas"]["NutrientReference"] | null;
            /**
             * @description Upper limit of the requested country's UL set that applies to this
             *     nutrient and form under the UL matching rule; `null` when no row
             *     applies, the matched row is exempt, or the limit depends on a form
             *     the label does not state (`UL_FORM_DEPENDENT`). Always written by
             *     the API; optional in the schema only for compatibility.
             */
            upperLimit?: components["schemas"]["NutrientUpperLimit"] | null;
        };
        /**
         * @description `TOTAL`: the limit covers intake from all sources; `SUPPLEMENTAL`: supplemental intake only. Both are compared with the product's UL-relevant daily amount alone (dietary intake ignored).
         * @enum {string}
         */
        UlBasis: "TOTAL" | "SUPPLEMENTAL";
        /** @description Reference amount per day of the country's reference set, in the nutrient's canonical unit. */
        NutrientReference: {
            /** @description Stored reference value per day, rounded once to 4 decimals. */
            amount: number;
            unit: components["schemas"]["CanonicalUnit"];
            /**
             * @description Reference set code; equals the country's `referenceSetCode`.
             * @example EU_NRV
             */
            setCode: string;
            /**
             * @description Always `REGULATORY` (same vocabulary as `BreakdownRow.referenceSource`).
             * @constant
             */
            source: "REGULATORY";
            /** @description 100 x `amountPerDay` / `amount`, from the unrounded values, rounded once to 4 decimals (round-half-even). Not capped at the upper limit or the utility ceiling. */
            percentOfReference: number;
        };
        /** @description Tolerable upper intake level per day of the country's UL set, in the nutrient's canonical unit. */
        NutrientUpperLimit: {
            /** @description Stored upper limit per day, rounded once to 4 decimals. */
            amount: number;
            unit: components["schemas"]["CanonicalUnit"];
            basis: components["schemas"]["UlBasis"];
            /**
             * @description UL set code; equals the country's `ulSetCode`.
             * @example EFSA
             */
            setCode: string;
            /**
             * @description Form code of the matched row, a registry form code or `ANY`.
             * @example ANY
             */
            formCode: string;
            /** @description Citation note stored with the matched row, verbatim; `null` when the row has none. */
            note: string | null;
            /** @description UL-relevant amount per day compared with `amount` (`ulAmountPerServing` x `servingsPerDay` when `ulAmountPerServing` is set, else `amountPerDay`), rounded once to 4 decimals. */
            ulAmountPerDay: number;
            /** @description `true` when the unrounded `ulAmountPerDay` is strictly greater than the stored `amount`; the same comparison as the scoring service's `EXCEEDS_UL` flag, which product detail does not emit. */
            exceeded: boolean;
        };
        ProductAvailability: {
            status: components["schemas"]["AvailabilityStatus"];
            note: string | null;
        };
        Offer: {
            retailer: string;
            /** @description Price in the display currency. */
            price: components["schemas"]["Money"];
            /** @description Price in the country's native currency. */
            priceNative: components["schemas"]["Money"];
            currency: components["schemas"]["CurrencyCode"];
            inStock: boolean;
            /** Format: uri */
            url: string;
            /** Format: date-time */
            checkedAt: string;
            /** @description `true` when `checkedAt` is older than 30 days. */
            priceStale: boolean;
        };
        ProductDetail: {
            /** Format: uuid */
            id: string;
            code: components["schemas"]["ProductCode"];
            brand: string;
            name: string;
            dosageForm: components["schemas"]["DosageForm"];
            unitsPerContainer: number;
            unitsPerServing: number;
            servingsPerDay: number;
            gtin: string | null;
            /** @description `true` for synthetic fixture products; equals `provenance.synthetic`. */
            synthetic: boolean;
            provenance: components["schemas"]["ProductProvenance"];
            availability: components["schemas"]["ProductAvailability"];
            nutrients: components["schemas"]["ProductNutrient"][];
            offers: components["schemas"]["Offer"][];
            /** @description Effective-price cost with out-of-stock offers included; `null` when the product has no offer. */
            cost: components["schemas"]["Cost"] | null;
            selectedOffer: components["schemas"]["SelectedOffer"] | null;
            flags: components["schemas"]["ProductFlag"][];
            /** @description Whether the caller has a saved-product row for this product in `meta.country`; `false` for anonymous callers. */
            saved: boolean;
            /** @description The caller's alert subscriptions (active and inactive) on this product in `meta.country`, ordered by `trigger` (`PRICE_DROP`, `BACK_IN_STOCK`, `PRICE_CHANGE`); empty for anonymous callers. */
            alerts: components["schemas"]["AlertSubscription"][];
        };
        ProductDetailMeta: {
            country: components["schemas"]["CountryCode"];
            nativeCurrency: components["schemas"]["CurrencyCode"];
            currency: components["schemas"]["CurrencyCode"];
            fx: components["schemas"]["FxMeta"];
        };
        ProductDetailResponse: {
            meta: components["schemas"]["ProductDetailMeta"];
            product: components["schemas"]["ProductDetail"];
        };
        /** @description A `null` limit means unlimited. */
        PlanLimits: {
            code: components["schemas"]["PlanCode"];
            name: string;
            savedProductsLimit: number | null;
            alertSubscriptionsLimit: number | null;
            notificationsPerMonthLimit: number | null;
        };
        PlanPrice: {
            /** @description Price in the display currency. */
            amount: components["schemas"]["Money"];
            /** @description Price in the plan's stored currency. */
            amountNative: components["schemas"]["Money"];
            currency: components["schemas"]["CurrencyCode"];
            nativeCurrency: components["schemas"]["CurrencyCode"];
            /** @enum {string} */
            billingPeriod: "MONTHLY";
        };
        Plan: components["schemas"]["PlanLimits"] & {
            /** @description `null` for FREE. */
            price: components["schemas"]["PlanPrice"] | null;
            /**
             * @description Whether the plan can be bought through `POST /billing/checkout-session`:
             *     `true` only for PRO and only when a payment provider is
             *     configured. It does not depend on the caller; a caller whose
             *     plan is already PRO gets 409 `plan_already_active`.
             */
            checkoutAvailable: boolean;
        };
        PlanList: {
            meta: components["schemas"]["MoneyMeta"];
            items: components["schemas"]["Plan"][];
        };
        FxCurrency: {
            code: components["schemas"]["CurrencyCode"];
            /**
             * Format: date
             * @description Newest stored `as_of` of any rate row whose base or quote is this currency; `null` when no such row is stored.
             */
            asOf: string | null;
            /** @description `true` when `asOf` is more than 5 days before the current UTC date; `false` when `asOf` is `null`. */
            stale: boolean;
        };
        FxCurrencyList: {
            items: components["schemas"]["FxCurrency"][];
        };
        FxRate: {
            quote: components["schemas"]["CurrencyCode"];
            /** @description Stored rate (`numeric(18,8)`), written with its 8 decimals and never rounded. */
            rate: number;
            /**
             * Format: date
             * @description ECB publication date of the rate.
             */
            asOf: string;
            /** @enum {string} */
            source: "FRANKFURTER";
            /** @description `true` when `asOf` is more than 5 days before the current UTC date. */
            stale: boolean;
        };
        FxRateList: {
            base: components["schemas"]["CurrencyCode"];
            rates: components["schemas"]["FxRate"][];
        };
        FxRefreshResult: {
            /** @description `fx_rate` rows inserted or updated by this run (a repeated refresh of the same ECB date rewrites the rows and counts them again). */
            upsertedRows: number;
            /** @description Base currencies whose provider call failed in this run, ordered ascending; their stored rates stay in use. Empty when every call succeeded. Unsupported bases are not listed here. */
            failedBases: components["schemas"]["CurrencyCode"][];
            /** @description Base currencies the provider rejected in this run with a 422 whose message names exactly that base as an invalid currency (`invalid currency: <base>`), ordered ascending; they get no rate and do not fail the run on their own. Empty when there is none. */
            unsupportedBases: components["schemas"]["CurrencyCode"][];
        };
        UsageMeter: {
            used: number;
            /** @description `null` means unlimited. */
            limit: number | null;
        };
        MonthlyUsageMeter: components["schemas"]["UsageMeter"] & {
            /**
             * Format: date
             * @description First day of the current calendar month (UTC).
             */
            periodMonth: string;
        };
        Usage: {
            /** @description Counts saved-product rows over every country. */
            savedProducts: components["schemas"]["UsageMeter"];
            /** @description Counts active subscriptions over every country. */
            alertSubscriptions: components["schemas"]["UsageMeter"];
            /** @description Notifications counted this calendar month (UTC); the cap notice and plan notifications are not counted. */
            notificationsThisMonth: components["schemas"]["MonthlyUsageMeter"];
        };
        AnonymousQuota: {
            /** @constant */
            principal: "ANONYMOUS";
            queriesUsed: number;
            queriesLimit: number;
            visibleResults: number;
        };
        UserQuota: {
            /** @enum {string} */
            principal: "USER" | "ADMIN";
            plan: components["schemas"]["PlanCode"];
            usage: components["schemas"]["Usage"];
        };
        SessionQuota: components["schemas"]["AnonymousQuota"] | components["schemas"]["UserQuota"];
        UserProfile: {
            /** Format: uuid */
            id: string;
            email: components["schemas"]["Email"];
            emailVerified: boolean;
            role: components["schemas"]["Role"];
            status: components["schemas"]["UserStatus"];
            locale: components["schemas"]["Locale"];
            defaultCountryCode: components["schemas"]["CountryCode"] | null;
            /** @description Preferred display currency; `null` means the country's native currency. */
            displayCurrency: components["schemas"]["CurrencyCode"] | null;
            emailAlertsEnabled: boolean;
            /** Format: date-time */
            createdAt: string;
        };
        Subscription: {
            /** Format: uuid */
            id: string;
            planCode: components["schemas"]["PlanCode"];
            status: components["schemas"]["SubscriptionStatus"];
            source: components["schemas"]["SubscriptionSource"];
            /** Format: date-time */
            startedAt: string;
            /**
             * Format: date-time
             * @description `null` for the open-ended FREE subscription.
             */
            currentPeriodEnd: string | null;
            /** Format: date-time */
            canceledAt: string | null;
        };
        Me: components["schemas"]["UserProfile"] & {
            plan: components["schemas"]["PlanLimits"];
            subscription: components["schemas"]["Subscription"];
            usage: components["schemas"]["Usage"];
        };
        UpdateMeRequest: {
            displayCurrency?: components["schemas"]["CurrencyCode"] | null;
            locale?: components["schemas"]["Locale"];
            defaultCountryCode?: components["schemas"]["CountryCode"];
            emailAlertsEnabled?: boolean;
        };
        DeleteAccountRequest: {
            /** Format: password */
            password: string;
        };
        RegisterRequest: {
            email: components["schemas"]["Email"];
            /**
             * Format: password
             * @description At least 10 characters and not in the breached-password list.
             */
            password: string;
            locale?: components["schemas"]["Locale"];
            countryCode?: components["schemas"]["CountryCode"];
        };
        TokenRequest: {
            /** @description base64url-encoded 256-bit token from the URL fragment of the e-mail link. */
            token: string;
        };
        EmailRequest: {
            email: components["schemas"]["Email"];
        };
        LoginRequest: {
            email: components["schemas"]["Email"];
            /** Format: password */
            password: string;
        };
        ResetPasswordRequest: {
            token: string;
            /**
             * Format: password
             * @description Same rules as registration.
             */
            newPassword: string;
        };
        AuthTokenResponse: {
            accessToken: string;
            /** @enum {string} */
            tokenType: "Bearer";
            /**
             * @description Access-token lifetime in seconds.
             * @example 900
             */
            expiresIn: number;
            user: components["schemas"]["UserProfile"];
        };
        /** @description Generic acknowledgement; identical for known and unknown e-mail addresses. */
        AcceptedMessage: {
            /** @description i18n key of the message shown to the user. */
            messageKey: string;
        };
        SaveProductRequest: {
            /** Format: uuid */
            productId: string;
            countryCode: components["schemas"]["CountryCode"];
        };
        SavedProductRef: {
            /** Format: uuid */
            productId: string;
            countryCode: components["schemas"]["CountryCode"];
            /** Format: date-time */
            savedAt: string;
        };
        SavedProduct: {
            /** Format: uuid */
            productId: string;
            code: components["schemas"]["ProductCode"];
            brand: string;
            name: string;
            dosageForm: components["schemas"]["DosageForm"];
            provenance: components["schemas"]["ProductProvenance"];
            countryCode: components["schemas"]["CountryCode"];
            /**
             * Format: date-time
             * @description `saved_product.created_at`.
             */
            savedAt: string;
            /** @description Availability in `countryCode`; `NOT_SOLD` when no availability row exists. */
            availabilityStatus: components["schemas"]["AvailabilityStatus"];
            /** @description Current effective-price cost with out-of-stock offers included; `null` without offers or when `availabilityStatus` is not `AVAILABLE`. */
            cost: components["schemas"]["Cost"] | null;
            /** @description Offer behind `cost`; `null` exactly when `cost` is `null`. */
            selectedOffer: components["schemas"]["SelectedOffer"] | null;
            /** @description Not `AVAILABLE`: exactly `UNAVAILABLE_IN_COUNTRY`. Otherwise the `GET /products` flags in the order `CONVERSION_ASSUMED`, `PRICE_STALE`, `OUT_OF_STOCK`, `FX_STALE`, `FX_UNAVAILABLE` (FX flags only with a cost). */
            flags: components["schemas"]["ProductFlag"][];
            /** @description The caller's active alert subscriptions on this product in this country, ordered by `trigger` (`PRICE_DROP`, `BACK_IN_STOCK`, `PRICE_CHANGE`). */
            alerts: components["schemas"]["AlertSubscription"][];
        };
        SavedProductListMeta: {
            country: components["schemas"]["CountryCode"];
            nativeCurrency: components["schemas"]["CurrencyCode"];
            currency: components["schemas"]["CurrencyCode"];
            fx: components["schemas"]["FxMeta"];
        };
        SavedProductList: {
            meta: components["schemas"]["SavedProductListMeta"];
            items: components["schemas"]["SavedProduct"][];
        };
        /**
         * @description Minimum price drop in percent of the baseline price (`PRICE_DROP`
         *     only), stored as `numeric(5,2)`: from 0.01 to 99.99 with at most 2
         *     decimal places once trailing zeros are removed. A value outside the
         *     range or with more decimals is 422 `validation_failed` with
         *     `errors[].code = out_of_range`; it is never rounded. Returned as a
         *     JSON number (for example `5` or `7.5`). The alert fires when
         *     `after.effectivePrice <= baselinePrice x (1 - thresholdPercent / 100)`.
         * @example 5
         */
        ThresholdPercent: number;
        AlertSubscription: {
            /** Format: uuid */
            id: string;
            /** Format: uuid */
            productId: string;
            countryCode: components["schemas"]["CountryCode"];
            trigger: components["schemas"]["AlertTrigger"];
            /** @description Set for `PRICE_DROP`, `null` otherwise. */
            thresholdPercent: components["schemas"]["ThresholdPercent"] | null;
            /**
             * @description Baseline effective price (`numeric(12,2)`) in the country's native
             *     currency, never converted to the display currency (`PRICE_DROP`
             *     only). Set on creation, activation and threshold change to the
             *     current effective price, and after each notification to the
             *     triggering price; `null` until a first price exists, and always
             *     `null` for the other triggers.
             */
            baselinePrice: number | null;
            /** @description Native currency of the country (the currency of `baselinePrice`). */
            baselineCurrency: components["schemas"]["CurrencyCode"];
            active: boolean;
            /** Format: date-time */
            createdAt: string;
            /**
             * Format: date-time
             * @description When the subscription last produced a notification; `null` until then. A firing skipped by the monthly cap sets neither this nor the baseline.
             */
            lastTriggeredAt: string | null;
        };
        ProductRef: {
            /** Format: uuid */
            id: string;
            code: components["schemas"]["ProductCode"];
            brand: string;
            name: string;
            /** @description Availability in the subscription's country (`NOT_SOLD` when no availability row exists); anything but `AVAILABLE` is shown with the `UNAVAILABLE_IN_COUNTRY` badge. */
            availabilityStatus: components["schemas"]["AvailabilityStatus"];
            provenance: components["schemas"]["ProductProvenance"];
        };
        AlertListItem: components["schemas"]["AlertSubscription"] & {
            product: components["schemas"]["ProductRef"];
        };
        AlertList: {
            items: components["schemas"]["AlertListItem"][];
        };
        CreateAlertRequest: {
            /** Format: uuid */
            productId: string;
            countryCode: components["schemas"]["CountryCode"];
            trigger: components["schemas"]["AlertTrigger"];
            /** @description `PRICE_DROP` only; absent or `null` means 5.00. A non-null value with another trigger is 422 `threshold_not_allowed`. */
            thresholdPercent?: components["schemas"]["ThresholdPercent"] | null;
        };
        /** @description At least one of `active` and `thresholdPercent`; `null` values are rejected with 422. */
        UpdateAlertRequest: {
            active?: boolean;
            thresholdPercent?: components["schemas"]["ThresholdPercent"];
        };
        /**
         * @description Effective-price state of a product in a country (section 7.4,
         *     out-of-stock offers included) as carried by the `OFFERS_CHANGED`
         *     event; every field is `null` when no offer exists.
         */
        OfferState: {
            /** @description Native currency of the country (`currency` of the payload), 2 decimals as stored; never converted. */
            effectivePrice: number | null;
            inStock: boolean | null;
            retailer: string | null;
            /** Format: uri */
            url: string | null;
        };
        /**
         * @description Payload of `ALERT_PRICE_DROP`, `ALERT_BACK_IN_STOCK` and
         *     `ALERT_PRICE_CHANGE`: a snapshot written when the notification is
         *     created; later catalog changes or a deleted subscription do not alter
         *     it. Every amount (`before.effectivePrice`, `after.effectivePrice`,
         *     `baselinePrice`) is in the country's native currency `currency` and
         *     is never converted to a display currency: the inbox and the alert
         *     e-mail render it with `currency`.
         */
        AlertNotificationPayload: {
            /**
             * Format: uuid
             * @description Subscription that fired; it may have been deleted since.
             */
            subscriptionId: string;
            trigger: components["schemas"]["AlertTrigger"];
            /** Format: uuid */
            productId: string;
            productCode: components["schemas"]["ProductCode"];
            brand: string;
            productName: string;
            countryCode: components["schemas"]["CountryCode"];
            /** @description Native currency of `countryCode`. */
            currency: components["schemas"]["CurrencyCode"];
            /** @description Threshold in effect when the alert fired (`PRICE_DROP`); `null` for the other triggers. */
            thresholdPercent: components["schemas"]["ThresholdPercent"] | null;
            /** @description Baseline the drop was measured against (`PRICE_DROP`), native; `null` for the other triggers. */
            baselinePrice: number | null;
            before: components["schemas"]["OfferState"];
            after: components["schemas"]["OfferState"];
        };
        /** @description Payload of `NOTIFICATION_CAP_REACHED` (at most one per user and calendar month, not counted against the cap). */
        CapReachedPayload: {
            /**
             * Format: date
             * @description First day of the capped calendar month (UTC).
             */
            periodMonth: string;
            /** @description The plan's monthly notification limit that was reached. */
            limit: number;
        };
        /** @description Payload of `PLAN_ACTIVATED` (from `SubscriptionActivatedEvent`; not counted against the cap). */
        PlanActivatedPayload: {
            planCode: components["schemas"]["PlanCode"];
            source: components["schemas"]["SubscriptionSource"];
            /**
             * Format: date-time
             * @description End of the activated or extended period.
             */
            currentPeriodEnd: string | null;
            /** @description `true` when an existing PRO period was extended. */
            extended: boolean;
        };
        /** @description Payload of `PLAN_EXPIRED` (from `SubscriptionExpiredEvent`; not counted against the cap). */
        PlanExpiredPayload: {
            /** @description The plan that expired (`PRO`). */
            expiredPlanCode: components["schemas"]["PlanCode"];
            /** @description The plan now active (`FREE`). */
            planCode: components["schemas"]["PlanCode"];
            /** @description Alert subscriptions set inactive because they exceeded the new plan's limit. */
            deactivatedAlertSubscriptions: number;
        };
        NotificationBase: {
            /** Format: uuid */
            id: string;
            type: components["schemas"]["NotificationType"];
            /** Format: date-time */
            createdAt: string;
            /**
             * Format: date-time
             * @description First time the notification was marked read; `null` while unread.
             */
            readAt: string | null;
        };
        AlertNotification: components["schemas"]["NotificationBase"] & {
            /** @enum {string} */
            type?: "ALERT_PRICE_DROP" | "ALERT_BACK_IN_STOCK" | "ALERT_PRICE_CHANGE";
            payload?: components["schemas"]["AlertNotificationPayload"];
        } & {
            /**
             * @description discriminator enum property added by openapi-typescript
             * @enum {string}
             */
            type: "ALERT_PRICE_DROP" | "ALERT_BACK_IN_STOCK" | "ALERT_PRICE_CHANGE";
        };
        CapReachedNotification: components["schemas"]["NotificationBase"] & {
            /** @constant */
            type?: "NOTIFICATION_CAP_REACHED";
            payload?: components["schemas"]["CapReachedPayload"];
        } & {
            /**
             * @description discriminator enum property added by openapi-typescript
             * @enum {string}
             */
            type: "NOTIFICATION_CAP_REACHED";
        };
        PlanActivatedNotification: components["schemas"]["NotificationBase"] & {
            /** @constant */
            type?: "PLAN_ACTIVATED";
            payload?: components["schemas"]["PlanActivatedPayload"];
        } & {
            /**
             * @description discriminator enum property added by openapi-typescript
             * @enum {string}
             */
            type: "PLAN_ACTIVATED";
        };
        PlanExpiredNotification: components["schemas"]["NotificationBase"] & {
            /** @constant */
            type?: "PLAN_EXPIRED";
            payload?: components["schemas"]["PlanExpiredPayload"];
        } & {
            /**
             * @description discriminator enum property added by openapi-typescript
             * @enum {string}
             */
            type: "PLAN_EXPIRED";
        };
        /** @description One inbox entry; `type` selects the payload schema. */
        Notification: components["schemas"]["AlertNotification"] | components["schemas"]["CapReachedNotification"] | components["schemas"]["PlanActivatedNotification"] | components["schemas"]["PlanExpiredNotification"];
        NotificationPage: {
            meta: components["schemas"]["PageMeta"];
            items: components["schemas"]["Notification"][];
        };
        UnreadCount: {
            unreadCount: number;
        };
        PromoRedeemRequest: {
            /**
             * @description The code to redeem. Any string is accepted; a value that does not match `PromoCodeValue` is rejected with the same 400 `promo_invalid` as an unknown code.
             * @example LAUNCH-7K3M9QXT2B4N6PRW
             */
            code: string;
        };
        SubscriptionActivation: {
            plan: components["schemas"]["PlanCode"];
            subscription: components["schemas"]["Subscription"];
        };
        BillingRedirect: {
            /**
             * Format: uri
             * @description Absolute https URL of the provider's hosted page; the browser is sent there.
             */
            url: string;
        };
        /**
         * @description A Stripe event (snapshot payload) as Stripe sends it. Only `id`, `type`,
         *     `livemode` and `data.object` are read, after the signature check; other
         *     members are ignored.
         */
        StripeEvent: {
            /** @example evt_1QwErTyUiOpAsDfG */
            id: string;
            /** @example invoice.paid */
            type: string;
            livemode: boolean;
            data: {
                object: Record<string, never>;
            };
        };
        WebhookAck: {
            /** @description Always `true`. */
            received: boolean;
        };
        ComponentHealth: {
            status: components["schemas"]["HealthStatus"];
        };
        FxHealth: {
            status: components["schemas"]["HealthStatus"];
            /** @description `FxRefreshResult.unsupportedBases` of the last FX refresh run that obtained the lock since the API started (the run reported in `jobs`), ordered ascending; empty before the first run. It does not change `status`. */
            unsupportedBases: components["schemas"]["CurrencyCode"][];
            /**
             * Format: date
             * @description Newest `as_of` over all stored rates; `null` when no rate is stored.
             */
            newestAsOf: string | null;
            /** @description Current UTC date minus `newestAsOf` in whole days; `null` when no rate is stored. */
            rateAgeDays: number | null;
            /** @description `rateAgeDays > 5`; `false` when no rate is stored (the status is then `DEGRADED`). */
            stale: boolean;
        };
        /** @description Outbox counts (as `OutboxStats`) with a status. */
        OutboxHealth: components["schemas"]["OutboxStats"] & {
            status: components["schemas"]["HealthStatus"];
        };
        JobRun: {
            /**
             * @description Scheduled job; the housekeeping job is reported per package (one advisory lock each, ADR-0011).
             * @enum {string}
             */
            name: "OUTBOX_PROCESSOR" | "FX_REFRESH" | "SUBSCRIPTION_EXPIRY" | "HOUSEKEEPING_IDENTITY" | "HOUSEKEEPING_ALERTS";
            /**
             * Format: date-time
             * @description End of the last run that obtained the lock since the API started; `null` before the first.
             */
            lastRunAt: string | null;
            /**
             * @description `FAILED` when the run ended with an exception; an FX run is `FAILED` when at least one base failed (`FxRefreshResult.failedBases` not empty) or when no base could be refreshed at all, while unsupported bases alone (`FxRefreshResult.unsupportedBases`) do not fail it; `null` before the first run.
             * @enum {string|null}
             */
            lastOutcome: "SUCCEEDED" | "FAILED" | null;
        };
        AdminHealth: {
            status: components["schemas"]["HealthStatus"];
            /** Format: date-time */
            checkedAt: string;
            components: {
                scoring: components["schemas"]["ComponentHealth"];
                database: components["schemas"]["ComponentHealth"];
                fx: components["schemas"]["FxHealth"];
                outbox: components["schemas"]["OutboxHealth"];
            };
            jobs: components["schemas"]["JobRun"][];
        };
        OutboxStats: {
            /** @description Events with `processed_at IS NULL` (claimed or not). */
            pending: number;
            /** @description Pending events with `attempts > 0` (waiting for a retry). */
            failed: number;
            /** @description Retained events with outcome `DEAD_LETTER` (kept 90 days). */
            deadLetter: number;
            /**
             * Format: date-time
             * @description `created_at` of the oldest pending event; `null` when nothing is pending.
             */
            oldestPendingAt: string | null;
        };
        /** @description Products document; validated against `contracts/schemas/import-products.schema.json` before any write. */
        ProductsImportDocument: components["schemas"]["import-products.schema"];
        OffersImportUpload: {
            /** @description Offers CSV with a `product_code` column, at most 20 MB. */
            file: string;
        };
        /**
         * @description Row counts of the import (camelCase keys). `OFFERS_CSV`: `rows` (data
         *     rows read), `inserted`, `updated`, `unchanged`, `deleted`,
         *     `changedPairs`. `PRODUCTS_JSON`: `products` (products in the
         *     document), `inserted`, `updated`, `unchanged`, `deleted` (summed over
         *     every written table) and `changedPairs`. A `FAILED` job reports the
         *     rows or products read and 0 for every write count.
         */
        ImportSummary: {
            [key: string]: number;
        };
        ImportJob: {
            /** Format: uuid */
            id: string;
            kind: components["schemas"]["ImportJobKind"];
            status: components["schemas"]["ImportJobStatus"];
            /** Format: date-time */
            createdAt: string;
            /** Format: date-time */
            finishedAt: string | null;
            summary: components["schemas"]["ImportSummary"] | null;
        };
        ImportJobError: {
            /**
             * @description `OFFERS_CSV`: 1-based record number in the file, header included
             *     (the first data row is 2); file-level failures use 1.
             *     `PRODUCTS_JSON`: 1-based position of the product in `products`;
             *     document-level failures (encoding, JSON syntax, top-level schema
             *     violations) use 1 and name their location in `message`.
             */
            rowNumber: number;
            /** @description Machine-readable error code, the prefix of `message` (for example `invalid_csv`, `wrong_column_count`, `currency_mismatch`, `invalid_offer_url`, `unsupported_iu_form`, `schema_violation`, `too_many_records`, `field_too_long`, `too_many_errors`). */
            code?: string;
            /** @description `<code>: <detail> (<location>)`, where the location is `row N` for CSV records or a JSON path such as `products[2].nutrients[0].unit`. */
            message: string;
        };
        ImportJobDetail: components["schemas"]["ImportJob"] & {
            errors: components["schemas"]["ImportJobError"][];
        };
        AdminUser: {
            /** Format: uuid */
            id: string;
            email: components["schemas"]["Email"];
            role: components["schemas"]["Role"];
            status: components["schemas"]["UserStatus"];
            plan: components["schemas"]["PlanCode"];
            emailVerified: boolean;
            /** Format: date-time */
            createdAt: string;
        };
        AdminUserPage: {
            meta: components["schemas"]["PageMeta"];
            items: components["schemas"]["AdminUser"][];
        };
        GrantSubscriptionRequest: {
            /** @enum {string} */
            planCode: "PRO";
            durationDays: number;
        };
        SetUserStatusRequest: {
            status: components["schemas"]["UserStatus"];
        };
        CreatePromoCodeRequest: {
            /** @description Optional human-readable prefix. */
            prefix?: string;
            /** @enum {string} */
            planCode: "PRO";
            durationDays: number;
            maxRedemptions: number;
            /**
             * Format: date-time
             * @description `null` for a code that never expires; otherwise it must lie in the future (422, code `not_in_future`).
             */
            expiresAt: string | null;
        };
        PromoCode: {
            code: components["schemas"]["PromoCodeValue"];
            planCode: components["schemas"]["PlanCode"];
            durationDays: number;
            maxRedemptions: number;
            redeemedCount: number;
            /** Format: date-time */
            expiresAt: string | null;
            /** Format: uuid */
            createdBy: string | null;
        };
        PromoCodeList: {
            items: components["schemas"]["PromoCode"][];
        };
        /** @description Business key of the product, used by fixtures, this document, the offers CSV and tests. */
        productCode: string;
        registryCode: string;
        nutrientAmount: number;
        productNutrient: {
            nutrient_code: components["schemas"]["registryCode"];
            /** @description Labelled amount per serving; must be greater than 0. */
            amount_per_serving: components["schemas"]["nutrientAmount"];
            /**
             * @description Accepted units are mg, mcg, g and IU; the spellings µg, μg and ug are normalised to mcg and iu to IU.
             * @enum {string}
             */
            unit: "mg" | "mcg" | "g" | "IU" | "µg" | "μg" | "ug" | "iu";
            /** @description UL-relevant amount per serving in the same unit, set only when it differs from the labelled amount (preformed vitamin A next to beta-carotene, folic acid next to a DFE total); null or absent means the full amount is UL-relevant. */
            ul_amount_per_serving?: number | null;
            /** @description Form of the UL-relevant part from the form registry, or UNKNOWN. ANY is not a product form. */
            form_code?: string | null;
            /** @description Form text as printed on the label. */
            form_label?: string | null;
        };
        /** @description ISO 3166-1 alpha-2 country code of the country registry. */
        countryCode: string;
        availability: {
            country_code: components["schemas"]["countryCode"];
            /** @enum {string} */
            status: "AVAILABLE" | "NOT_SOLD" | "DISCONTINUED";
            note?: string | null;
        };
        offer: {
            country_code: components["schemas"]["countryCode"];
            /** @description Retailer name; part of the offer natural key (compared case-sensitively). */
            retailer: string;
            price: number;
            /** @description ISO 4217 alphabetic code; must equal the country's currency. */
            currency: string;
            in_stock: boolean;
            /**
             * Format: uri
             * @description Absolute http or https URI of at most 2048 characters.
             */
            url: string;
            /**
             * Format: date-time
             * @description Time the price was checked (RFC 3339 with offset); must not be in the future.
             */
            checked_at: string;
        };
        product: {
            code: components["schemas"]["productCode"];
            brand: string;
            name: string;
            /** @enum {string} */
            dosage_form: "TABLET" | "CAPSULE" | "SOFTGEL" | "GUMMY" | "POWDER" | "LIQUID";
            /** @description Count of the smallest labelled unit (tablet, capsule, scoop, millilitre) per container. */
            units_per_container: number;
            units_per_serving: number;
            servings_per_day: number;
            /** @description GTIN-8, GTIN-12, GTIN-13 or GTIN-14 digits; null or absent when unknown. */
            gtin?: string | null;
            /** @description Provenance of the record (label photo, manufacturer page, fixture set). */
            source?: string | null;
            /** @description Identifier of the record in the external source named by `source` (for example the label id of a label database). */
            source_ref?: string;
            /**
             * Format: date
             * @description Date the record was entered in the external source.
             */
            source_entry_date?: string;
            /**
             * @description True for fictional fixture products.
             * @default false
             */
            synthetic: boolean;
            nutrients: components["schemas"]["productNutrient"][];
            availability: components["schemas"]["availability"][];
            /** @default [] */
            offers: components["schemas"]["offer"][];
        };
        /**
         * Vitadelta products import document
         * @description Document accepted by the admin products import (POST /api/v1/admin/imports/products, at most 20 MB). Products are upserted by `code`; the internal uuid id is stable once created. The whole document is validated against this schema and the semantic rules below before anything is written; any error fails the job and writes nothing. Per listed product: the nutrient set is replaced by `nutrients`; `availability` rows are upserted per country (countries not listed keep their rows); `offers` are upserted by the natural key (product, country_code, retailer) with replace-set per (product, country_code) pair present in the product's `offers` (offers of that pair whose retailer is not listed are deleted; pairs not listed are untouched). Semantic rules checked by the importer: product codes are unique in the document; nutrient codes exist in the registry and appear at most once per product; `form_code` exists in the form registry (an unknown or missing form is stored as `UNKNOWN` with the raw text in `form_label`); `ul_amount_per_serving <= amount_per_serving`; units are normalised (`µg`, `μg`, `ug` to `mcg`, `iu` to `IU`); `IU` is accepted only for VIT_A, VIT_D and VIT_E; VIT_A in IU requires RETINOL, RETINYL_ACETATE or RETINYL_PALMITATE (`unsupported_iu_form` otherwise); `units_per_container >= units_per_serving`; country codes exist; `currency` equals the country's currency (`currency_mismatch`); `checked_at` is not in the future; each country appears at most once in `availability` and each (country_code, retailer) at most once in `offers`; money has at most 2 decimals, nutrient amounts at most 4 decimals, `servings_per_day` at most 2 decimals.
         */
        "import-products.schema": {
            /**
             * @description Version of this document format. Only version 1 exists.
             * @constant
             */
            schema_version: 1;
            products: components["schemas"]["product"][];
            $defs: {
                /** @description Business key of the product, used by fixtures, this document, the offers CSV and tests. */
                productCode: string;
                registryCode: string;
                /** @description ISO 3166-1 alpha-2 country code of the country registry. */
                countryCode: string;
                nutrientAmount: number;
                product: {
                    code: components["schemas"]["productCode"];
                    brand: string;
                    name: string;
                    /** @enum {string} */
                    dosage_form: "TABLET" | "CAPSULE" | "SOFTGEL" | "GUMMY" | "POWDER" | "LIQUID";
                    /** @description Count of the smallest labelled unit (tablet, capsule, scoop, millilitre) per container. */
                    units_per_container: number;
                    units_per_serving: number;
                    servings_per_day: number;
                    /** @description GTIN-8, GTIN-12, GTIN-13 or GTIN-14 digits; null or absent when unknown. */
                    gtin?: string | null;
                    /** @description Provenance of the record (label photo, manufacturer page, fixture set). */
                    source?: string | null;
                    /** @description Identifier of the record in the external source named by `source` (for example the label id of a label database). */
                    source_ref?: string;
                    /**
                     * Format: date
                     * @description Date the record was entered in the external source.
                     */
                    source_entry_date?: string;
                    /**
                     * @description True for fictional fixture products.
                     * @default false
                     */
                    synthetic: boolean;
                    nutrients: components["schemas"]["productNutrient"][];
                    availability: components["schemas"]["availability"][];
                    /** @default [] */
                    offers: components["schemas"]["offer"][];
                };
                productNutrient: {
                    nutrient_code: components["schemas"]["registryCode"];
                    /** @description Labelled amount per serving; must be greater than 0. */
                    amount_per_serving: components["schemas"]["nutrientAmount"];
                    /**
                     * @description Accepted units are mg, mcg, g and IU; the spellings µg, μg and ug are normalised to mcg and iu to IU.
                     * @enum {string}
                     */
                    unit: "mg" | "mcg" | "g" | "IU" | "µg" | "μg" | "ug" | "iu";
                    /** @description UL-relevant amount per serving in the same unit, set only when it differs from the labelled amount (preformed vitamin A next to beta-carotene, folic acid next to a DFE total); null or absent means the full amount is UL-relevant. */
                    ul_amount_per_serving?: number | null;
                    /** @description Form of the UL-relevant part from the form registry, or UNKNOWN. ANY is not a product form. */
                    form_code?: string | null;
                    /** @description Form text as printed on the label. */
                    form_label?: string | null;
                };
                availability: {
                    country_code: components["schemas"]["countryCode"];
                    /** @enum {string} */
                    status: "AVAILABLE" | "NOT_SOLD" | "DISCONTINUED";
                    note?: string | null;
                };
                offer: {
                    country_code: components["schemas"]["countryCode"];
                    /** @description Retailer name; part of the offer natural key (compared case-sensitively). */
                    retailer: string;
                    price: number;
                    /** @description ISO 4217 alphabetic code; must equal the country's currency. */
                    currency: string;
                    in_stock: boolean;
                    /**
                     * Format: uri
                     * @description Absolute http or https URI of at most 2048 characters.
                     */
                    url: string;
                    /**
                     * Format: date-time
                     * @description Time the price was checked (RFC 3339 with offset); must not be in the future.
                     */
                    checked_at: string;
                };
            };
        };
    };
    responses: {
        /**
         * @description Missing, invalid or expired credentials. Codes: `unauthorized`,
         *     `invalid_credentials`, `refresh_reuse_detected`.
         */
        Unauthorized: {
            headers: {
                "X-Request-Id": components["headers"]["XRequestId"];
                [name: string]: unknown;
            };
            content: {
                /**
                 * @example {
                 *       "type": "https://vitadelta.dev/problems/unauthorized",
                 *       "title": "Unauthorized",
                 *       "status": 401,
                 *       "detail": "Authentication is required.",
                 *       "instance": "/api/v1/me",
                 *       "requestId": "8c1d2e3f-4a5b-4c6d-9e7f-0a1b2c3d4e5f"
                 *     }
                 */
                "application/problem+json": components["schemas"]["Problem"];
            };
        };
        /**
         * @description The principal may not perform the action. Codes: `forbidden`,
         *     `account_disabled`, `email_not_verified`.
         */
        Forbidden: {
            headers: {
                "X-Request-Id": components["headers"]["XRequestId"];
                [name: string]: unknown;
            };
            content: {
                /**
                 * @example {
                 *       "type": "https://vitadelta.dev/problems/forbidden",
                 *       "title": "Forbidden",
                 *       "status": 403,
                 *       "detail": "The ADMIN role is required.",
                 *       "instance": "/api/v1/admin/users",
                 *       "requestId": "8c1d2e3f-4a5b-4c6d-9e7f-0a1b2c3d4e5f"
                 *     }
                 */
                "application/problem+json": components["schemas"]["Problem"];
            };
        };
        /** @description The anonymous trial quota is used up. Code `quota_exceeded`. */
        QuotaExceeded: {
            headers: {
                "X-Request-Id": components["headers"]["XRequestId"];
                [name: string]: unknown;
            };
            content: {
                /**
                 * @example {
                 *       "type": "https://vitadelta.dev/problems/quota_exceeded",
                 *       "title": "Query quota exceeded",
                 *       "status": 403,
                 *       "detail": "The free trial queries are used up. Create an account to continue.",
                 *       "instance": "/api/v1/recommendations/query",
                 *       "requestId": "8c1d2e3f-4a5b-4c6d-9e7f-0a1b2c3d4e5f",
                 *       "limit": 3,
                 *       "used": 3,
                 *       "action": "SIGN_UP",
                 *       "upgradeHint": {
                 *         "messageKey": "quota.exhausted",
                 *         "ctaPath": "/auth/register"
                 *       }
                 *     }
                 */
                "application/problem+json": components["schemas"]["QuotaExceededProblem"];
            };
        };
        /**
         * @description Plan limit reached (`entitlement_exceeded`, body
         *     `EntitlementExceededProblem`) or e-mail not verified
         *     (`email_not_verified`, body `Problem`).
         */
        EntitlementForbidden: {
            headers: {
                "X-Request-Id": components["headers"]["XRequestId"];
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["EntitlementExceededProblem"] | components["schemas"]["Problem"];
            };
        };
        /**
         * @description Resource not found. Codes: `product_not_found`,
         *     `product_not_available_in_country`; other resources (alert,
         *     notification, user, import job, promo code) use `about:blank`. For
         *     caller-scoped resources (`/me/alerts/{id}`,
         *     `/me/notifications/{id}/read`) an id that does not exist, belongs to
         *     another user or is not a UUID gets the same 404 body (only
         *     `instance` and `requestId` differ).
         */
        NotFound: {
            headers: {
                "X-Request-Id": components["headers"]["XRequestId"];
                [name: string]: unknown;
            };
            content: {
                /**
                 * @example {
                 *       "type": "https://vitadelta.dev/problems/product_not_available_in_country",
                 *       "title": "Product not available in country",
                 *       "status": 404,
                 *       "detail": "The product is not sold in GB.",
                 *       "instance": "/api/v1/products/3b2f0c4e-6a1d-4f0e-9c3a-1a2b3c4d5e01",
                 *       "requestId": "8c1d2e3f-4a5b-4c6d-9e7f-0a1b2c3d4e5f"
                 *     }
                 */
                "application/problem+json": components["schemas"]["Problem"];
            };
        };
        /** @description A concurrent change or run won (a subscription change, or a running job holding its lock); retry the request. Code `conflict_retry`. */
        Conflict: {
            headers: {
                "X-Request-Id": components["headers"]["XRequestId"];
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["Problem"];
            };
        };
        /** @description Request body exceeds the size limit (64 kB for every request body including the query, Stripe webhook 256 kB, import JSON 20 MB, CSV 20 MB). Code `validation_failed`. */
        PayloadTooLarge: {
            headers: {
                "X-Request-Id": components["headers"]["XRequestId"];
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["Problem"];
            };
        };
        /** @description The request body is not `application/json`. Generic problem with type `about:blank`. */
        UnsupportedMediaType: {
            headers: {
                "X-Request-Id": components["headers"]["XRequestId"];
                [name: string]: unknown;
            };
            content: {
                /**
                 * @example {
                 *       "type": "about:blank",
                 *       "title": "Unsupported Media Type",
                 *       "status": 415,
                 *       "detail": "Unsupported Media Type.",
                 *       "instance": "/api/v1/me",
                 *       "requestId": "8c1d2e3f-4a5b-4c6d-9e7f-0a1b2c3d4e5f"
                 *     }
                 */
                "application/problem+json": components["schemas"]["Problem"];
            };
        };
        /**
         * @description The request is syntactically or semantically invalid. Codes:
         *     `validation_failed` (with `errors[]`), `country_not_found`,
         *     `fx_unsupported_currency`. A malformed value (for example a country
         *     or currency code that does not match its pattern) is
         *     `validation_failed`; a well-formed but unknown country is
         *     `country_not_found`; a well-formed currency outside the supported
         *     set is `fx_unsupported_currency`.
         */
        UnprocessableEntity: {
            headers: {
                "X-Request-Id": components["headers"]["XRequestId"];
                [name: string]: unknown;
            };
            content: {
                /**
                 * @example {
                 *       "type": "https://vitadelta.dev/problems/validation_failed",
                 *       "title": "Validation failed",
                 *       "status": 422,
                 *       "detail": "The request contains invalid fields.",
                 *       "instance": "/api/v1/recommendations/query",
                 *       "requestId": "8c1d2e3f-4a5b-4c6d-9e7f-0a1b2c3d4e5f",
                 *       "errors": [
                 *         {
                 *           "field": "weights",
                 *           "code": "weights_all_zero",
                 *           "message": "The weights give every nutrient a weight of 0."
                 *         },
                 *         {
                 *           "field": "requiredNutrients[0].unit",
                 *           "code": "unit_requires_form",
                 *           "message": "IU is accepted only for VIT_D."
                 *         }
                 *       ]
                 *     }
                 */
                "application/problem+json": components["schemas"]["Problem"];
            };
        };
        /** @description The e-mail token is unknown, used or expired. Codes `token_invalid`, `token_expired`. */
        TokenRejected: {
            headers: {
                "X-Request-Id": components["headers"]["XRequestId"];
                [name: string]: unknown;
            };
            content: {
                /**
                 * @example {
                 *       "type": "https://vitadelta.dev/problems/token_expired",
                 *       "title": "Token expired",
                 *       "status": 400,
                 *       "detail": "The link has expired. Request a new one.",
                 *       "instance": "/api/v1/auth/verify-email",
                 *       "requestId": "8c1d2e3f-4a5b-4c6d-9e7f-0a1b2c3d4e5f"
                 *     }
                 */
                "application/problem+json": components["schemas"]["Problem"];
            };
        };
        /** @description The promo code cannot be redeemed (one response for every reason). Code `promo_invalid`. */
        PromoInvalid: {
            headers: {
                "X-Request-Id": components["headers"]["XRequestId"];
                [name: string]: unknown;
            };
            content: {
                /**
                 * @example {
                 *       "type": "https://vitadelta.dev/problems/promo_invalid",
                 *       "title": "Promo code invalid",
                 *       "status": 400,
                 *       "detail": "The promo code cannot be redeemed.",
                 *       "instance": "/api/v1/me/subscription/promo",
                 *       "requestId": "8c1d2e3f-4a5b-4c6d-9e7f-0a1b2c3d4e5f"
                 *     }
                 */
                "application/problem+json": components["schemas"]["Problem"];
            };
        };
        /** @description Rate limit exceeded. Code `rate_limited`. */
        TooManyRequests: {
            headers: {
                "X-Request-Id": components["headers"]["XRequestId"];
                "Retry-After": components["headers"]["RetryAfter"];
                [name: string]: unknown;
            };
            content: {
                /**
                 * @example {
                 *       "type": "https://vitadelta.dev/problems/rate_limited",
                 *       "title": "Too many requests",
                 *       "status": 429,
                 *       "detail": "Try again later.",
                 *       "instance": "/api/v1/auth/login",
                 *       "requestId": "8c1d2e3f-4a5b-4c6d-9e7f-0a1b2c3d4e5f"
                 *     }
                 */
                "application/problem+json": components["schemas"]["Problem"];
            };
        };
        /** @description The scoring service is unavailable, the circuit is open, the API instance has reached its limit of concurrent scoring calls, or the country's catalog exceeds the supported size (an operator error, logged). Code `scoring_unavailable`. Anonymous quota is not consumed. */
        ScoringUnavailable: {
            headers: {
                "X-Request-Id": components["headers"]["XRequestId"];
                [name: string]: unknown;
            };
            content: {
                /**
                 * @example {
                 *       "type": "https://vitadelta.dev/problems/scoring_unavailable",
                 *       "title": "Scoring unavailable",
                 *       "status": 503,
                 *       "detail": "The ranking service is temporarily unavailable.",
                 *       "instance": "/api/v1/recommendations/query",
                 *       "requestId": "8c1d2e3f-4a5b-4c6d-9e7f-0a1b2c3d4e5f"
                 *     }
                 */
                "application/problem+json": components["schemas"]["Problem"];
            };
        };
        /** @description Unexpected server error. Code `internal_error`. */
        InternalError: {
            headers: {
                "X-Request-Id": components["headers"]["XRequestId"];
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["Problem"];
            };
        };
        /**
         * @description The billing action does not apply to the caller's state. Codes
         *     `plan_already_active` (checkout while the ACTIVE plan is PRO or a
         *     provider subscription has not ended) and `no_billing_account` (portal
         *     before any completed provider checkout).
         */
        BillingConflict: {
            headers: {
                "X-Request-Id": components["headers"]["XRequestId"];
                [name: string]: unknown;
            };
            content: {
                /**
                 * @example {
                 *       "type": "https://vitadelta.dev/problems/plan_already_active",
                 *       "title": "Plan already active",
                 *       "status": 409,
                 *       "detail": "The PRO plan is already active.",
                 *       "instance": "/api/v1/billing/checkout-session",
                 *       "requestId": "8c1d2e3f-4a5b-4c6d-9e7f-0a1b2c3d4e5f"
                 *     }
                 */
                "application/problem+json": components["schemas"]["Problem"];
            };
        };
        /** @description No payment provider is configured, or the provider cannot be reached. Code `billing_unavailable`. */
        BillingUnavailable: {
            headers: {
                "X-Request-Id": components["headers"]["XRequestId"];
                [name: string]: unknown;
            };
            content: {
                /**
                 * @example {
                 *       "type": "https://vitadelta.dev/problems/billing_unavailable",
                 *       "title": "Billing unavailable",
                 *       "status": 503,
                 *       "detail": "Online payment is not available right now.",
                 *       "instance": "/api/v1/billing/checkout-session",
                 *       "requestId": "8c1d2e3f-4a5b-4c6d-9e7f-0a1b2c3d4e5f"
                 *     }
                 */
                "application/problem+json": components["schemas"]["Problem"];
            };
        };
        /**
         * @description The webhook request is not authentic or not readable. One generic body
         *     for every reason (signature, timestamp window, header format, body
         *     format); the reason is only logged and counted.
         */
        WebhookRejected: {
            headers: {
                "X-Request-Id": components["headers"]["XRequestId"];
                [name: string]: unknown;
            };
            content: {
                /**
                 * @example {
                 *       "type": "about:blank",
                 *       "title": "Bad Request",
                 *       "status": 400,
                 *       "detail": "Bad Request.",
                 *       "instance": "/api/v1/billing/webhooks/stripe",
                 *       "requestId": "8c1d2e3f-4a5b-4c6d-9e7f-0a1b2c3d4e5f"
                 *     }
                 */
                "application/problem+json": components["schemas"]["Problem"];
            };
        };
    };
    parameters: {
        /**
         * @description Client-supplied request id. Accepted only when it matches the pattern;
         *     otherwise, or when absent, the API generates a new id. Never rejected.
         */
        XRequestId: components["schemas"]["RequestId"];
        /** @description CSRF guard for cookie-scoped auth endpoints; any other value is rejected with 403 `forbidden`. */
        XRequestedWith: "vitadelta";
        /** @description When present, it must be one of the configured web origins; otherwise 403 `forbidden`. */
        Origin: string;
        /**
         * @description Country code; must exist in the country registry (422 `country_not_found` otherwise).
         * @example TR
         */
        CountryQueryRequired: components["schemas"]["CountryCode"];
        /**
         * @description Display currency. Resolution: this parameter, then the signed-in user's
         *     preference, then the native currency. Must be a supported currency
         *     (422 `fx_unsupported_currency` otherwise).
         */
        DisplayCurrencyQuery: components["schemas"]["CurrencyCode"];
        /** @description 1-based page number. */
        Page: number;
        /** @description Page size. */
        PageSize: number;
        /** @description Product id. */
        ProductIdPath: string;
        /** @description Alert subscription id. */
        AlertIdPath: string;
        /** @description Notification id. */
        NotificationIdPath: string;
        /** @description User id. */
        UserIdPath: string;
    };
    requestBodies: never;
    headers: {
        /** @description Request id in effect for this request (echoed or generated). */
        XRequestId: components["schemas"]["RequestId"];
        /** @description Seconds to wait before retrying. */
        RetryAfter: number;
        /**
         * @description Sets `vd_anon` when the anonymous identity cookie was missing
         *     (`HttpOnly; SameSite=Lax; Max-Age=15552000; Path=/`, `Secure` outside
         *     development). Absent for signed-in callers and when the cookie exists.
         */
        SetCookieAnon: string;
        /** @description Clears `vd_anon` (`Max-Age=0`). Sent identically on every 202 response. */
        SetCookieClearAnon: string;
        /** @description Sets the rotated `vd_refresh` cookie (`HttpOnly; SameSite=Strict; Path=/api/v1/auth; Max-Age=2592000`, `Secure` outside development). */
        SetCookieRefresh: string;
        /** @description Clears `vd_refresh` (`Path=/api/v1/auth; Max-Age=0`). */
        SetCookieClearRefresh: string;
        /** @description URL of the import job resource (`/api/v1/admin/imports/{jobId}`). */
        ImportJobLocation: string;
    };
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    getHealth: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The service is up or degraded. */
            200: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content: {
                    /**
                     * @example {
                     *       "status": "UP"
                     *     }
                     */
                    "application/json": components["schemas"]["Health"];
                };
            };
            /** @description The service is down. */
            503: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content: {
                    /**
                     * @example {
                     *       "status": "DOWN"
                     *     }
                     */
                    "application/json": components["schemas"]["Health"];
                };
            };
        };
    };
    getLegal: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Legal information. */
            200: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["LegalInfo"];
                };
            };
            500: components["responses"]["InternalError"];
        };
    };
    listCountries: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Supported countries. */
            200: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content: {
                    /**
                     * @example {
                     *       "items": [
                     *         {
                     *           "code": "TR",
                     *           "nameEn": "Türkiye",
                     *           "currency": "TRY",
                     *           "referenceSetCode": "EU_NRV",
                     *           "ulSetCode": "EFSA",
                     *           "defaultLocale": "tr"
                     *         },
                     *         {
                     *           "code": "DE",
                     *           "nameEn": "Germany",
                     *           "currency": "EUR",
                     *           "referenceSetCode": "EU_NRV",
                     *           "ulSetCode": "EFSA",
                     *           "defaultLocale": "en"
                     *         },
                     *         {
                     *           "code": "GB",
                     *           "nameEn": "United Kingdom",
                     *           "currency": "GBP",
                     *           "referenceSetCode": "EU_NRV",
                     *           "ulSetCode": "EFSA",
                     *           "defaultLocale": "en"
                     *         },
                     *         {
                     *           "code": "US",
                     *           "nameEn": "United States",
                     *           "currency": "USD",
                     *           "referenceSetCode": "US_DV",
                     *           "ulSetCode": "IOM",
                     *           "defaultLocale": "en"
                     *         }
                     *       ]
                     *     }
                     */
                    "application/json": components["schemas"]["CountryList"];
                };
            };
            500: components["responses"]["InternalError"];
        };
    };
    listNutrients: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Nutrient registry ordered by `sortOrder`. */
            200: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content: {
                    /**
                     * @example {
                     *       "items": [
                     *         {
                     *           "code": "VIT_C",
                     *           "nameEn": "Vitamin C",
                     *           "category": "VITAMIN",
                     *           "canonicalUnit": "mg",
                     *           "presenceFloorPerDay": null,
                     *           "sortOrder": 20,
                     *           "referenceSetCodes": [
                     *             "EU_NRV",
                     *             "US_DV"
                     *           ],
                     *           "forms": []
                     *         },
                     *         {
                     *           "code": "VIT_D",
                     *           "nameEn": "Vitamin D",
                     *           "category": "VITAMIN",
                     *           "canonicalUnit": "mcg",
                     *           "presenceFloorPerDay": null,
                     *           "sortOrder": 30,
                     *           "referenceSetCodes": [
                     *             "EU_NRV",
                     *             "US_DV"
                     *           ],
                     *           "forms": [
                     *             {
                     *               "code": "D2_ERGOCALCIFEROL",
                     *               "nameEn": "Vitamin D2 (ergocalciferol)"
                     *             },
                     *             {
                     *               "code": "D3_CHOLECALCIFEROL",
                     *               "nameEn": "Vitamin D3 (cholecalciferol)"
                     *             }
                     *           ]
                     *         },
                     *         {
                     *           "code": "COQ10",
                     *           "nameEn": "Coenzyme Q10",
                     *           "category": "COENZYME",
                     *           "canonicalUnit": "mg",
                     *           "presenceFloorPerDay": null,
                     *           "sortOrder": 290,
                     *           "referenceSetCodes": [],
                     *           "forms": []
                     *         }
                     *       ]
                     *     }
                     */
                    "application/json": components["schemas"]["NutrientList"];
                };
            };
            500: components["responses"]["InternalError"];
        };
    };
    getDefaultWeights: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Default category weights, override default, the allowed ranges and the engine parameters. */
            200: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["DefaultWeights"];
                };
            };
            500: components["responses"]["InternalError"];
        };
    };
    getSessionQuota: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Quota or usage summary. */
            200: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    "Set-Cookie": components["headers"]["SetCookieAnon"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SessionQuota"];
                };
            };
            401: components["responses"]["Unauthorized"];
            429: components["responses"]["TooManyRequests"];
            500: components["responses"]["InternalError"];
        };
    };
    queryRecommendations: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                /**
                 * @example {
                 *       "country": "TR",
                 *       "stockFilter": "IN_STOCK_ONLY",
                 *       "ranking": {
                 *         "mode": "VALUE"
                 *       },
                 *       "weights": {
                 *         "categories": {
                 *           "VITAMIN": 1,
                 *           "MINERAL": 1,
                 *           "COENZYME": 0.8,
                 *           "OTHER": 0.5
                 *         },
                 *         "overrides": {
                 *           "VIT_C": 1.5
                 *         }
                 *       },
                 *       "requiredNutrients": [
                 *         {
                 *           "code": "VIT_C",
                 *           "minAmountPerDay": 300,
                 *           "unit": "mg"
                 *         }
                 *       ],
                 *       "excludeExceedingUl": false,
                 *       "displayCurrency": "USD",
                 *       "page": 1,
                 *       "pageSize": 20
                 *     }
                 */
                "application/json": components["schemas"]["RecommendationQueryRequest"];
            };
        };
        responses: {
            /** @description Ranked page of recommendations. */
            200: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    "Set-Cookie": components["headers"]["SetCookieAnon"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RecommendationQueryResponse"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["QuotaExceeded"];
            413: components["responses"]["PayloadTooLarge"];
            415: components["responses"]["UnsupportedMediaType"];
            422: components["responses"]["UnprocessableEntity"];
            429: components["responses"]["TooManyRequests"];
            500: components["responses"]["InternalError"];
            503: components["responses"]["ScoringUnavailable"];
        };
    };
    compareProducts: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                /**
                 * @example {
                 *       "country": "TR",
                 *       "baselineProductId": "3b2f0c4e-6a1d-4f0e-9c3a-1a2b3c4d5e01",
                 *       "candidateProductId": "3b2f0c4e-6a1d-4f0e-9c3a-1a2b3c4d5e02",
                 *       "stockFilter": "IN_STOCK_ONLY",
                 *       "weights": {
                 *         "categories": {
                 *           "VITAMIN": 1,
                 *           "MINERAL": 1,
                 *           "COENZYME": 0.8,
                 *           "OTHER": 0.5
                 *         }
                 *       },
                 *       "displayCurrency": "TRY"
                 *     }
                 */
                "application/json": components["schemas"]["CompareRequest"];
            };
        };
        responses: {
            /** @description Verdict, deltas and per-nutrient delta rows. */
            200: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content: {
                    /**
                     * @example {
                     *       "meta": {
                     *         "country": "TR",
                     *         "nativeCurrency": "TRY",
                     *         "currency": "TRY",
                     *         "fx": {
                     *           "base": "TRY",
                     *           "quote": "TRY",
                     *           "rate": 1,
                     *           "asOf": null,
                     *           "source": null,
                     *           "stale": false,
                     *           "status": "OK"
                     *         },
                     *         "catalogVersion": 42,
                     *         "marketSize": 2
                     *       },
                     *       "swapped": false,
                     *       "verdict": "NOT_WORTH_IT",
                     *       "productA": {
                     *         "productId": "3b2f0c4e-6a1d-4f0e-9c3a-1a2b3c4d5e01",
                     *         "code": "GOLD-A",
                     *         "brand": "Gold",
                     *         "name": "Gold A Multivitamin",
                     *         "dosageForm": "TABLET",
                     *         "provenance": {
                     *           "synthetic": true,
                     *           "source": "Synthetic fixture set (fictional brand and formulation)",
                     *           "sourceRef": null,
                     *           "sourceEntryDate": null
                     *         },
                     *         "cost": {
                     *           "effectivePrice": 800,
                     *           "effectivePriceNative": 800,
                     *           "currency": "TRY",
                     *           "servingsPerContainer": 60,
                     *           "daysSupply": 60,
                     *           "costPerDay": 13.3333,
                     *           "costPerDayNative": 13.3333,
                     *           "priceStale": false,
                     *           "outOfStock": false
                     *         },
                     *         "contentScore": 4.9719,
                     *         "valueScore": 0.3729,
                     *         "flags": [],
                     *         "selectedOffer": {
                     *           "retailer": "Retailer One",
                     *           "url": "https://shop.example.com/products/gold-a",
                     *           "checkedAt": "2026-11-20T09:00:00Z"
                     *         }
                     *       },
                     *       "productB": {
                     *         "productId": "3b2f0c4e-6a1d-4f0e-9c3a-1a2b3c4d5e02",
                     *         "code": "GOLD-B",
                     *         "brand": "Gold",
                     *         "name": "Gold B Multivitamin",
                     *         "dosageForm": "TABLET",
                     *         "provenance": {
                     *           "synthetic": true,
                     *           "source": "Synthetic fixture set (fictional brand and formulation)",
                     *           "sourceRef": null,
                     *           "sourceEntryDate": null
                     *         },
                     *         "cost": {
                     *           "effectivePrice": 900,
                     *           "effectivePriceNative": 900,
                     *           "currency": "TRY",
                     *           "servingsPerContainer": 60,
                     *           "daysSupply": 60,
                     *           "costPerDay": 15,
                     *           "costPerDayNative": 15,
                     *           "priceStale": false,
                     *           "outOfStock": false
                     *         },
                     *         "contentScore": 5.0093,
                     *         "valueScore": 0.334,
                     *         "flags": [],
                     *         "selectedOffer": {
                     *           "retailer": "Retailer One",
                     *           "url": "https://shop.example.com/products/gold-b",
                     *           "checkedAt": "2026-11-20T09:00:00Z"
                     *         }
                     *       },
                     *       "deltaCost": 1.6667,
                     *       "deltaCostNative": 1.6667,
                     *       "deltaContent": 0.0374,
                     *       "marginalRate": 44.5806,
                     *       "mRef": 2.6817,
                     *       "ratio": 16.6237,
                     *       "rows": [
                     *         {
                     *           "code": "MAGNESIUM",
                     *           "category": "MINERAL",
                     *           "amountA": 100,
                     *           "amountB": 100,
                     *           "deltaAmount": 0,
                     *           "unit": "mg",
                     *           "rA": 0.2667,
                     *           "rB": 0.2667,
                     *           "uA": 0.2667,
                     *           "uB": 0.2667,
                     *           "weight": 1,
                     *           "deltaContribution": 0,
                     *           "flags": []
                     *         },
                     *         {
                     *           "code": "VIT_B12",
                     *           "category": "VITAMIN",
                     *           "amountA": 2.5,
                     *           "amountB": 2.5,
                     *           "deltaAmount": 0,
                     *           "unit": "mcg",
                     *           "rA": 1,
                     *           "rB": 1,
                     *           "uA": 1,
                     *           "uB": 1,
                     *           "weight": 1,
                     *           "deltaContribution": 0,
                     *           "flags": []
                     *         },
                     *         {
                     *           "code": "VIT_C",
                     *           "category": "VITAMIN",
                     *           "amountA": 300,
                     *           "amountB": 350,
                     *           "deltaAmount": 50,
                     *           "unit": "mg",
                     *           "rA": 3.75,
                     *           "rB": 4.375,
                     *           "uA": 1.4626,
                     *           "uB": 1.5,
                     *           "weight": 1,
                     *           "deltaContribution": 0.0374,
                     *           "flags": [
                     *             "DIMINISHING",
                     *             "ABOVE_CEILING_NO_CREDIT"
                     *           ]
                     *         },
                     *         {
                     *           "code": "VIT_D",
                     *           "category": "VITAMIN",
                     *           "amountA": 10,
                     *           "amountB": 10,
                     *           "deltaAmount": 0,
                     *           "unit": "mcg",
                     *           "rA": 2,
                     *           "rB": 2,
                     *           "uA": 1.2426,
                     *           "uB": 1.2426,
                     *           "weight": 1,
                     *           "deltaContribution": 0,
                     *           "flags": []
                     *         },
                     *         {
                     *           "code": "ZINC",
                     *           "category": "MINERAL",
                     *           "amountA": 10,
                     *           "amountB": 10,
                     *           "deltaAmount": 0,
                     *           "unit": "mg",
                     *           "rA": 1,
                     *           "rB": 1,
                     *           "uA": 1,
                     *           "uB": 1,
                     *           "weight": 1,
                     *           "deltaContribution": 0,
                     *           "flags": []
                     *         }
                     *       ],
                     *       "explanation": {
                     *         "key": "explain.verdict.not_worth_it",
                     *         "params": {
                     *           "currency": "TRY",
                     *           "deltaCostPerDay": 1.6667,
                     *           "deltaContent": 0.0374,
                     *           "marginalRate": 44.5806,
                     *           "mRef": 2.6817,
                     *           "ratio": 16.6237
                     *         }
                     *       }
                     *     }
                     */
                    "application/json": components["schemas"]["CompareResponse"];
                };
            };
            401: components["responses"]["Unauthorized"];
            404: components["responses"]["NotFound"];
            413: components["responses"]["PayloadTooLarge"];
            415: components["responses"]["UnsupportedMediaType"];
            422: components["responses"]["UnprocessableEntity"];
            429: components["responses"]["TooManyRequests"];
            500: components["responses"]["InternalError"];
            503: components["responses"]["ScoringUnavailable"];
        };
    };
    searchProducts: {
        parameters: {
            query: {
                /**
                 * @description Country code; must exist in the country registry (422 `country_not_found` otherwise).
                 * @example TR
                 */
                country: components["parameters"]["CountryQueryRequired"];
                /** @description Free-text search over brand, name and product code (case-insensitive substring). Control characters are rejected with 422. */
                q?: string;
                /** @description 1-based page number. */
                page?: components["parameters"]["Page"];
                /** @description Page size. */
                pageSize?: components["parameters"]["PageSize"];
                /**
                 * @description Display currency. Resolution: this parameter, then the signed-in user's
                 *     preference, then the native currency. Must be a supported currency
                 *     (422 `fx_unsupported_currency` otherwise).
                 */
                displayCurrency?: components["parameters"]["DisplayCurrencyQuery"];
            };
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Page of products. */
            200: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ProductPage"];
                };
            };
            401: components["responses"]["Unauthorized"];
            422: components["responses"]["UnprocessableEntity"];
            429: components["responses"]["TooManyRequests"];
            500: components["responses"]["InternalError"];
        };
    };
    getProduct: {
        parameters: {
            query: {
                /**
                 * @description Country code; must exist in the country registry (422 `country_not_found` otherwise).
                 * @example TR
                 */
                country: components["parameters"]["CountryQueryRequired"];
                /**
                 * @description Display currency. Resolution: this parameter, then the signed-in user's
                 *     preference, then the native currency. Must be a supported currency
                 *     (422 `fx_unsupported_currency` otherwise).
                 */
                displayCurrency?: components["parameters"]["DisplayCurrencyQuery"];
            };
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path: {
                /** @description Product id. */
                id: components["parameters"]["ProductIdPath"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Product detail. */
            200: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content: {
                    /**
                     * @example {
                     *       "meta": {
                     *         "country": "TR",
                     *         "nativeCurrency": "TRY",
                     *         "currency": "TRY",
                     *         "fx": {
                     *           "base": "TRY",
                     *           "quote": "TRY",
                     *           "rate": 1,
                     *           "asOf": null,
                     *           "source": null,
                     *           "stale": false,
                     *           "status": "OK"
                     *         }
                     *       },
                     *       "product": {
                     *         "id": "6a1f0c2e-3b4d-4e5f-8a9b-0c1d2e3f4a5b",
                     *         "code": "ZN40",
                     *         "brand": "Fixture Labs",
                     *         "name": "Zinc 40 High Strength",
                     *         "dosageForm": "TABLET",
                     *         "unitsPerContainer": 60,
                     *         "unitsPerServing": 1,
                     *         "servingsPerDay": 1,
                     *         "gtin": null,
                     *         "synthetic": true,
                     *         "provenance": {
                     *           "synthetic": true,
                     *           "source": null,
                     *           "sourceRef": null,
                     *           "sourceEntryDate": null
                     *         },
                     *         "availability": {
                     *           "status": "AVAILABLE",
                     *           "note": null
                     *         },
                     *         "nutrients": [
                     *           {
                     *             "code": "ZINC",
                     *             "category": "MINERAL",
                     *             "amountPerServing": 40,
                     *             "amountPerDay": 40,
                     *             "unit": "mg",
                     *             "ulAmountPerServing": null,
                     *             "formCode": "ZINC_PICOLINATE",
                     *             "formLabel": "zinc picolinate",
                     *             "conversionAssumed": false,
                     *             "reference": {
                     *               "amount": 10,
                     *               "unit": "mg",
                     *               "setCode": "EU_NRV",
                     *               "source": "REGULATORY",
                     *               "percentOfReference": 400
                     *             },
                     *             "upperLimit": {
                     *               "amount": 25,
                     *               "unit": "mg",
                     *               "basis": "TOTAL",
                     *               "setCode": "EFSA",
                     *               "formCode": "ANY",
                     *               "note": "EFSA UL Overview v11 (2025); EFSA 2006 SCF/NDA UL compendium (SCF 2002 zinc)",
                     *               "ulAmountPerDay": 40,
                     *               "exceeded": true
                     *             }
                     *           }
                     *         ],
                     *         "offers": [
                     *           {
                     *             "retailer": "Fixture Pharmacy",
                     *             "price": 240,
                     *             "priceNative": 240,
                     *             "currency": "TRY",
                     *             "inStock": true,
                     *             "url": "https://fixture-pharmacy.example/tr/p/zn40",
                     *             "checkedAt": "2026-11-21T09:00:00Z",
                     *             "priceStale": false
                     *           },
                     *           {
                     *             "retailer": "Anatolia Wellness Depot",
                     *             "price": 259.9,
                     *             "priceNative": 259.9,
                     *             "currency": "TRY",
                     *             "inStock": true,
                     *             "url": "https://anatolia-wellness.example/tr/p/zn40",
                     *             "checkedAt": "2026-11-23T08:15:00Z",
                     *             "priceStale": false
                     *           }
                     *         ],
                     *         "cost": {
                     *           "effectivePrice": 240,
                     *           "effectivePriceNative": 240,
                     *           "currency": "TRY",
                     *           "servingsPerContainer": 60,
                     *           "daysSupply": 60,
                     *           "costPerDay": 4,
                     *           "costPerDayNative": 4,
                     *           "priceStale": false,
                     *           "outOfStock": false
                     *         },
                     *         "selectedOffer": {
                     *           "retailer": "Fixture Pharmacy",
                     *           "url": "https://fixture-pharmacy.example/tr/p/zn40",
                     *           "checkedAt": "2026-11-21T09:00:00Z"
                     *         },
                     *         "flags": [],
                     *         "saved": false,
                     *         "alerts": []
                     *       }
                     *     }
                     */
                    "application/json": components["schemas"]["ProductDetailResponse"];
                };
            };
            401: components["responses"]["Unauthorized"];
            404: components["responses"]["NotFound"];
            422: components["responses"]["UnprocessableEntity"];
            429: components["responses"]["TooManyRequests"];
            500: components["responses"]["InternalError"];
        };
    };
    listPlans: {
        parameters: {
            query?: {
                /**
                 * @description Display currency. Resolution: this parameter, then the signed-in user's
                 *     preference, then the native currency. Must be a supported currency
                 *     (422 `fx_unsupported_currency` otherwise).
                 */
                displayCurrency?: components["parameters"]["DisplayCurrencyQuery"];
            };
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Plans. */
            200: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PlanList"];
                };
            };
            401: components["responses"]["Unauthorized"];
            422: components["responses"]["UnprocessableEntity"];
            500: components["responses"]["InternalError"];
        };
    };
    listFxCurrencies: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Supported currencies. */
            200: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["FxCurrencyList"];
                };
            };
            500: components["responses"]["InternalError"];
        };
    };
    getFxRates: {
        parameters: {
            query: {
                /**
                 * @description Base currency (ISO 4217).
                 * @example TRY
                 */
                base: components["schemas"]["CurrencyCode"];
            };
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Rates. */
            200: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["FxRateList"];
                };
            };
            422: components["responses"]["UnprocessableEntity"];
            500: components["responses"]["InternalError"];
        };
    };
    register: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
                /** @description When present, it must be one of the configured web origins; otherwise 403 `forbidden`. */
                Origin?: components["parameters"]["Origin"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                /**
                 * @example {
                 *       "email": "user@example.com",
                 *       "password": "correct-horse-battery",
                 *       "locale": "en",
                 *       "countryCode": "TR"
                 *     }
                 */
                "application/json": components["schemas"]["RegisterRequest"];
            };
        };
        responses: {
            /** @description Accepted; a message has been queued for the address. */
            202: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    "Set-Cookie": components["headers"]["SetCookieClearAnon"];
                    [name: string]: unknown;
                };
                content: {
                    /**
                     * @example {
                     *       "messageKey": "auth.register.check_email"
                     *     }
                     */
                    "application/json": components["schemas"]["AcceptedMessage"];
                };
            };
            403: components["responses"]["Forbidden"];
            413: components["responses"]["PayloadTooLarge"];
            422: components["responses"]["UnprocessableEntity"];
            429: components["responses"]["TooManyRequests"];
            500: components["responses"]["InternalError"];
        };
    };
    verifyEmail: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["TokenRequest"];
            };
        };
        responses: {
            /** @description E-mail verified. */
            204: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content?: never;
            };
            400: components["responses"]["TokenRejected"];
            413: components["responses"]["PayloadTooLarge"];
            415: components["responses"]["UnsupportedMediaType"];
            422: components["responses"]["UnprocessableEntity"];
            429: components["responses"]["TooManyRequests"];
            500: components["responses"]["InternalError"];
        };
    };
    resendVerification: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["EmailRequest"];
            };
        };
        responses: {
            /** @description Accepted. */
            202: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content: {
                    /**
                     * @example {
                     *       "messageKey": "auth.verify.resent"
                     *     }
                     */
                    "application/json": components["schemas"]["AcceptedMessage"];
                };
            };
            413: components["responses"]["PayloadTooLarge"];
            415: components["responses"]["UnsupportedMediaType"];
            422: components["responses"]["UnprocessableEntity"];
            429: components["responses"]["TooManyRequests"];
            500: components["responses"]["InternalError"];
        };
    };
    login: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
                /** @description When present, it must be one of the configured web origins; otherwise 403 `forbidden`. */
                Origin?: components["parameters"]["Origin"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["LoginRequest"];
            };
        };
        responses: {
            /** @description Logged in. */
            200: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    "Set-Cookie": components["headers"]["SetCookieRefresh"];
                    [name: string]: unknown;
                };
                content: {
                    /**
                     * @example {
                     *       "accessToken": "eyJraWQiOiI0ZjFhMmIzYzRkNWU2ZjcwIiwiYWxnIjoiSFMyNTYifQ.eyJzdWIiOiI5ZjFjMmQzZSJ9.c2lnbmF0dXJl",
                     *       "tokenType": "Bearer",
                     *       "expiresIn": 900,
                     *       "user": {
                     *         "id": "9f1c2d3e-4b5a-4c6d-8e7f-0a1b2c3d4e5f",
                     *         "email": "user@example.com",
                     *         "emailVerified": true,
                     *         "role": "USER",
                     *         "status": "ACTIVE",
                     *         "locale": "en",
                     *         "defaultCountryCode": "TR",
                     *         "displayCurrency": null,
                     *         "emailAlertsEnabled": true,
                     *         "createdAt": "2026-11-01T10:00:00Z"
                     *       }
                     *     }
                     */
                    "application/json": components["schemas"]["AuthTokenResponse"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            413: components["responses"]["PayloadTooLarge"];
            422: components["responses"]["UnprocessableEntity"];
            429: components["responses"]["TooManyRequests"];
            500: components["responses"]["InternalError"];
        };
    };
    refreshSession: {
        parameters: {
            query?: never;
            header: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
                /** @description CSRF guard for cookie-scoped auth endpoints; any other value is rejected with 403 `forbidden`. */
                "X-Requested-With": components["parameters"]["XRequestedWith"];
                /** @description When present, it must be one of the configured web origins; otherwise 403 `forbidden`. */
                Origin?: components["parameters"]["Origin"];
            };
            path?: never;
            cookie?: never;
        };
        /** @description No fields are read; an empty JSON object may be sent to satisfy the content-type rule. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["EmptyObject"];
            };
        };
        responses: {
            /** @description Session refreshed. */
            200: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    "Set-Cookie": components["headers"]["SetCookieRefresh"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["AuthTokenResponse"];
                };
            };
            /** @description No session; the request carried no `vd_refresh` cookie (or only an empty one). No body, no `Set-Cookie`. */
            204: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content?: never;
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            413: components["responses"]["PayloadTooLarge"];
            429: components["responses"]["TooManyRequests"];
            500: components["responses"]["InternalError"];
        };
    };
    logout: {
        parameters: {
            query?: never;
            header: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
                /** @description CSRF guard for cookie-scoped auth endpoints; any other value is rejected with 403 `forbidden`. */
                "X-Requested-With": components["parameters"]["XRequestedWith"];
                /** @description When present, it must be one of the configured web origins; otherwise 403 `forbidden`. */
                Origin?: components["parameters"]["Origin"];
            };
            path?: never;
            cookie?: never;
        };
        /** @description No fields are read; an empty JSON object may be sent to satisfy the content-type rule. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["EmptyObject"];
            };
        };
        responses: {
            /** @description Logged out. */
            204: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    "Set-Cookie": components["headers"]["SetCookieClearRefresh"];
                    [name: string]: unknown;
                };
                content?: never;
            };
            403: components["responses"]["Forbidden"];
            413: components["responses"]["PayloadTooLarge"];
            500: components["responses"]["InternalError"];
        };
    };
    forgotPassword: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["EmailRequest"];
            };
        };
        responses: {
            /** @description Accepted. */
            202: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content: {
                    /**
                     * @example {
                     *       "messageKey": "auth.forgot.check_email"
                     *     }
                     */
                    "application/json": components["schemas"]["AcceptedMessage"];
                };
            };
            413: components["responses"]["PayloadTooLarge"];
            415: components["responses"]["UnsupportedMediaType"];
            422: components["responses"]["UnprocessableEntity"];
            429: components["responses"]["TooManyRequests"];
            500: components["responses"]["InternalError"];
        };
    };
    resetPassword: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ResetPasswordRequest"];
            };
        };
        responses: {
            /** @description Password changed. */
            204: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content?: never;
            };
            400: components["responses"]["TokenRejected"];
            413: components["responses"]["PayloadTooLarge"];
            415: components["responses"]["UnsupportedMediaType"];
            422: components["responses"]["UnprocessableEntity"];
            429: components["responses"]["TooManyRequests"];
            500: components["responses"]["InternalError"];
        };
    };
    getMe: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Profile of the signed-in user. */
            200: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content: {
                    /**
                     * @example {
                     *       "id": "9f1c2d3e-4b5a-4c6d-8e7f-0a1b2c3d4e5f",
                     *       "email": "user@example.com",
                     *       "emailVerified": true,
                     *       "role": "USER",
                     *       "status": "ACTIVE",
                     *       "locale": "tr",
                     *       "defaultCountryCode": "TR",
                     *       "displayCurrency": "TRY",
                     *       "emailAlertsEnabled": true,
                     *       "createdAt": "2026-11-01T10:00:00Z",
                     *       "plan": {
                     *         "code": "FREE",
                     *         "name": "Free",
                     *         "savedProductsLimit": 5,
                     *         "alertSubscriptionsLimit": 3,
                     *         "notificationsPerMonthLimit": 30
                     *       },
                     *       "subscription": {
                     *         "id": "1d2e3f4a-5b6c-4d7e-8f9a-0b1c2d3e4f5a",
                     *         "planCode": "FREE",
                     *         "status": "ACTIVE",
                     *         "source": "MANUAL",
                     *         "startedAt": "2026-11-01T10:00:00Z",
                     *         "currentPeriodEnd": null,
                     *         "canceledAt": null
                     *       },
                     *       "usage": {
                     *         "savedProducts": {
                     *           "used": 2,
                     *           "limit": 5
                     *         },
                     *         "alertSubscriptions": {
                     *           "used": 1,
                     *           "limit": 3
                     *         },
                     *         "notificationsThisMonth": {
                     *           "used": 4,
                     *           "limit": 30,
                     *           "periodMonth": "2026-11-01"
                     *         }
                     *       }
                     *     }
                     */
                    "application/json": components["schemas"]["Me"];
                };
            };
            401: components["responses"]["Unauthorized"];
            500: components["responses"]["InternalError"];
        };
    };
    deleteMe: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["DeleteAccountRequest"];
            };
        };
        responses: {
            /** @description Account deleted. */
            204: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    "Set-Cookie": components["headers"]["SetCookieClearRefresh"];
                    [name: string]: unknown;
                };
                content?: never;
            };
            401: components["responses"]["Unauthorized"];
            413: components["responses"]["PayloadTooLarge"];
            415: components["responses"]["UnsupportedMediaType"];
            422: components["responses"]["UnprocessableEntity"];
            500: components["responses"]["InternalError"];
        };
    };
    updateMe: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                /**
                 * @example {
                 *       "displayCurrency": "EUR",
                 *       "emailAlertsEnabled": false
                 *     }
                 */
                "application/json": components["schemas"]["UpdateMeRequest"];
            };
        };
        responses: {
            /** @description Updated profile. */
            200: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Me"];
                };
            };
            401: components["responses"]["Unauthorized"];
            413: components["responses"]["PayloadTooLarge"];
            415: components["responses"]["UnsupportedMediaType"];
            422: components["responses"]["UnprocessableEntity"];
            500: components["responses"]["InternalError"];
        };
    };
    listSavedProducts: {
        parameters: {
            query: {
                /**
                 * @description Country code; must exist in the country registry (422 `country_not_found` otherwise).
                 * @example TR
                 */
                country: components["parameters"]["CountryQueryRequired"];
                /**
                 * @description Display currency. Resolution: this parameter, then the signed-in user's
                 *     preference, then the native currency. Must be a supported currency
                 *     (422 `fx_unsupported_currency` otherwise).
                 */
                displayCurrency?: components["parameters"]["DisplayCurrencyQuery"];
            };
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Saved products. */
            200: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content: {
                    /**
                     * @example {
                     *       "meta": {
                     *         "country": "TR",
                     *         "nativeCurrency": "TRY",
                     *         "currency": "USD",
                     *         "fx": {
                     *           "base": "TRY",
                     *           "quote": "USD",
                     *           "rate": 0.024137,
                     *           "asOf": "2026-11-23",
                     *           "source": "FRANKFURTER",
                     *           "stale": false,
                     *           "status": "OK"
                     *         }
                     *       },
                     *       "items": [
                     *         {
                     *           "productId": "3b2f0c4e-6a1d-4f0e-9c3a-1a2b3c4d5e01",
                     *           "code": "GOLD-A",
                     *           "brand": "Gold",
                     *           "name": "Gold A Multivitamin",
                     *           "dosageForm": "TABLET",
                     *           "provenance": {
                     *             "synthetic": true,
                     *             "source": "Synthetic fixture set (fictional brand and formulation)",
                     *             "sourceRef": null,
                     *             "sourceEntryDate": null
                     *           },
                     *           "countryCode": "TR",
                     *           "savedAt": "2026-11-24T08:30:00Z",
                     *           "availabilityStatus": "AVAILABLE",
                     *           "cost": {
                     *             "effectivePrice": 19.3096,
                     *             "effectivePriceNative": 800,
                     *             "currency": "USD",
                     *             "servingsPerContainer": 60,
                     *             "daysSupply": 60,
                     *             "costPerDay": 0.3218,
                     *             "costPerDayNative": 13.3333,
                     *             "priceStale": false,
                     *             "outOfStock": false
                     *           },
                     *           "selectedOffer": {
                     *             "retailer": "Retailer One",
                     *             "url": "https://shop.example.com/products/gold-a",
                     *             "checkedAt": "2026-11-20T09:00:00Z"
                     *           },
                     *           "flags": [],
                     *           "alerts": [
                     *             {
                     *               "id": "5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d",
                     *               "productId": "3b2f0c4e-6a1d-4f0e-9c3a-1a2b3c4d5e01",
                     *               "countryCode": "TR",
                     *               "trigger": "PRICE_DROP",
                     *               "thresholdPercent": 10,
                     *               "baselinePrice": 800,
                     *               "baselineCurrency": "TRY",
                     *               "active": true,
                     *               "createdAt": "2026-11-24T08:30:00Z",
                     *               "lastTriggeredAt": null
                     *             }
                     *           ]
                     *         },
                     *         {
                     *           "productId": "3b2f0c4e-6a1d-4f0e-9c3a-1a2b3c4d5e07",
                     *           "code": "GOLD-G",
                     *           "brand": "Gold",
                     *           "name": "Gold G Multivitamin",
                     *           "dosageForm": "CAPSULE",
                     *           "provenance": {
                     *             "synthetic": true,
                     *             "source": "Synthetic fixture set (fictional brand and formulation)",
                     *             "sourceRef": null,
                     *             "sourceEntryDate": null
                     *           },
                     *           "countryCode": "TR",
                     *           "savedAt": "2026-11-20T10:00:00Z",
                     *           "availabilityStatus": "DISCONTINUED",
                     *           "cost": null,
                     *           "selectedOffer": null,
                     *           "flags": [
                     *             {
                     *               "code": "UNAVAILABLE_IN_COUNTRY",
                     *               "nutrientCodes": []
                     *             }
                     *           ],
                     *           "alerts": []
                     *         }
                     *       ]
                     *     }
                     */
                    "application/json": components["schemas"]["SavedProductList"];
                };
            };
            401: components["responses"]["Unauthorized"];
            422: components["responses"]["UnprocessableEntity"];
            500: components["responses"]["InternalError"];
        };
    };
    saveProduct: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                /**
                 * @example {
                 *       "productId": "3b2f0c4e-6a1d-4f0e-9c3a-1a2b3c4d5e01",
                 *       "countryCode": "TR"
                 *     }
                 */
                "application/json": components["schemas"]["SaveProductRequest"];
            };
        };
        responses: {
            /** @description Already saved; the existing row is returned unchanged. */
            200: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content: {
                    /**
                     * @example {
                     *       "productId": "3b2f0c4e-6a1d-4f0e-9c3a-1a2b3c4d5e01",
                     *       "countryCode": "TR",
                     *       "savedAt": "2026-11-20T10:00:00Z"
                     *     }
                     */
                    "application/json": components["schemas"]["SavedProductRef"];
                };
            };
            /** @description Saved. */
            201: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content: {
                    /**
                     * @example {
                     *       "productId": "3b2f0c4e-6a1d-4f0e-9c3a-1a2b3c4d5e01",
                     *       "countryCode": "TR",
                     *       "savedAt": "2026-11-24T08:30:00Z"
                     *     }
                     */
                    "application/json": components["schemas"]["SavedProductRef"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["EntitlementForbidden"];
            404: components["responses"]["NotFound"];
            413: components["responses"]["PayloadTooLarge"];
            415: components["responses"]["UnsupportedMediaType"];
            422: components["responses"]["UnprocessableEntity"];
            500: components["responses"]["InternalError"];
        };
    };
    removeSavedProduct: {
        parameters: {
            query: {
                /**
                 * @description Country code; must exist in the country registry (422 `country_not_found` otherwise).
                 * @example TR
                 */
                country: components["parameters"]["CountryQueryRequired"];
            };
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path: {
                /** @description Product id. */
                productId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Removed. */
            204: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content?: never;
            };
            401: components["responses"]["Unauthorized"];
            422: components["responses"]["UnprocessableEntity"];
            500: components["responses"]["InternalError"];
        };
    };
    listAlerts: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Alert subscriptions. */
            200: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content: {
                    /**
                     * @example {
                     *       "items": [
                     *         {
                     *           "id": "5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d",
                     *           "productId": "3b2f0c4e-6a1d-4f0e-9c3a-1a2b3c4d5e01",
                     *           "countryCode": "TR",
                     *           "trigger": "PRICE_DROP",
                     *           "thresholdPercent": 10,
                     *           "baselinePrice": 800,
                     *           "baselineCurrency": "TRY",
                     *           "active": true,
                     *           "createdAt": "2026-11-24T08:30:00Z",
                     *           "lastTriggeredAt": null,
                     *           "product": {
                     *             "id": "3b2f0c4e-6a1d-4f0e-9c3a-1a2b3c4d5e01",
                     *             "code": "GOLD-A",
                     *             "brand": "Gold",
                     *             "name": "Gold A Multivitamin",
                     *             "availabilityStatus": "AVAILABLE",
                     *             "provenance": {
                     *               "synthetic": true,
                     *               "source": "Synthetic fixture set (fictional brand and formulation)",
                     *               "sourceRef": null,
                     *               "sourceEntryDate": null
                     *             }
                     *           }
                     *         },
                     *         {
                     *           "id": "6b7c8d9e-0f1a-4b2c-9d3e-4f5a6b7c8d9e",
                     *           "productId": "3b2f0c4e-6a1d-4f0e-9c3a-1a2b3c4d5e02",
                     *           "countryCode": "TR",
                     *           "trigger": "BACK_IN_STOCK",
                     *           "thresholdPercent": null,
                     *           "baselinePrice": null,
                     *           "baselineCurrency": "TRY",
                     *           "active": false,
                     *           "createdAt": "2026-11-21T12:00:00Z",
                     *           "lastTriggeredAt": "2026-11-22T07:00:00Z",
                     *           "product": {
                     *             "id": "3b2f0c4e-6a1d-4f0e-9c3a-1a2b3c4d5e02",
                     *             "code": "GOLD-B",
                     *             "brand": "Gold",
                     *             "name": "Gold B Multivitamin",
                     *             "availabilityStatus": "AVAILABLE",
                     *             "provenance": {
                     *               "synthetic": true,
                     *               "source": "Synthetic fixture set (fictional brand and formulation)",
                     *               "sourceRef": null,
                     *               "sourceEntryDate": null
                     *             }
                     *           }
                     *         }
                     *       ]
                     *     }
                     */
                    "application/json": components["schemas"]["AlertList"];
                };
            };
            401: components["responses"]["Unauthorized"];
            500: components["responses"]["InternalError"];
        };
    };
    createAlert: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                /**
                 * @example {
                 *       "productId": "3b2f0c4e-6a1d-4f0e-9c3a-1a2b3c4d5e01",
                 *       "countryCode": "TR",
                 *       "trigger": "PRICE_DROP",
                 *       "thresholdPercent": 10
                 *     }
                 */
                "application/json": components["schemas"]["CreateAlertRequest"];
            };
        };
        responses: {
            /** @description The subscription already existed; returned unchanged. */
            200: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content: {
                    /**
                     * @example {
                     *       "id": "5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d",
                     *       "productId": "3b2f0c4e-6a1d-4f0e-9c3a-1a2b3c4d5e01",
                     *       "countryCode": "TR",
                     *       "trigger": "PRICE_DROP",
                     *       "thresholdPercent": 10,
                     *       "baselinePrice": 800,
                     *       "baselineCurrency": "TRY",
                     *       "active": false,
                     *       "createdAt": "2026-11-20T10:00:00Z",
                     *       "lastTriggeredAt": null
                     *     }
                     */
                    "application/json": components["schemas"]["AlertSubscription"];
                };
            };
            /** @description Subscription created. */
            201: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content: {
                    /**
                     * @example {
                     *       "id": "5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d",
                     *       "productId": "3b2f0c4e-6a1d-4f0e-9c3a-1a2b3c4d5e01",
                     *       "countryCode": "TR",
                     *       "trigger": "PRICE_DROP",
                     *       "thresholdPercent": 10,
                     *       "baselinePrice": 800,
                     *       "baselineCurrency": "TRY",
                     *       "active": true,
                     *       "createdAt": "2026-11-24T08:30:00Z",
                     *       "lastTriggeredAt": null
                     *     }
                     */
                    "application/json": components["schemas"]["AlertSubscription"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["EntitlementForbidden"];
            404: components["responses"]["NotFound"];
            413: components["responses"]["PayloadTooLarge"];
            415: components["responses"]["UnsupportedMediaType"];
            422: components["responses"]["UnprocessableEntity"];
            500: components["responses"]["InternalError"];
        };
    };
    deleteAlert: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path: {
                /** @description Alert subscription id. */
                id: components["parameters"]["AlertIdPath"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Deleted. */
            204: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content?: never;
            };
            401: components["responses"]["Unauthorized"];
            404: components["responses"]["NotFound"];
            500: components["responses"]["InternalError"];
        };
    };
    updateAlert: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path: {
                /** @description Alert subscription id. */
                id: components["parameters"]["AlertIdPath"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdateAlertRequest"];
            };
        };
        responses: {
            /** @description The subscription after the update (also when nothing changed). */
            200: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content: {
                    /**
                     * @example {
                     *       "id": "5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d",
                     *       "productId": "3b2f0c4e-6a1d-4f0e-9c3a-1a2b3c4d5e01",
                     *       "countryCode": "TR",
                     *       "trigger": "PRICE_DROP",
                     *       "thresholdPercent": 7.5,
                     *       "baselinePrice": 760,
                     *       "baselineCurrency": "TRY",
                     *       "active": true,
                     *       "createdAt": "2026-11-24T08:30:00Z",
                     *       "lastTriggeredAt": null
                     *     }
                     */
                    "application/json": components["schemas"]["AlertSubscription"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["EntitlementForbidden"];
            /**
             * @description Unknown, foreign or non-UUID subscription id (`about:blank`), or on
             *     activation `product_not_available_in_country`.
             */
            404: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content: {
                    /**
                     * @example {
                     *       "type": "about:blank",
                     *       "title": "Not Found",
                     *       "status": 404,
                     *       "detail": "Not Found.",
                     *       "instance": "/api/v1/me/alerts/5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d",
                     *       "requestId": "8c1d2e3f-4a5b-4c6d-9e7f-0a1b2c3d4e5f"
                     *     }
                     */
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            413: components["responses"]["PayloadTooLarge"];
            415: components["responses"]["UnsupportedMediaType"];
            422: components["responses"]["UnprocessableEntity"];
            500: components["responses"]["InternalError"];
        };
    };
    listNotifications: {
        parameters: {
            query?: {
                /** @description Return only unread notifications. */
                unreadOnly?: boolean;
                /** @description 1-based page number. */
                page?: components["parameters"]["Page"];
                /** @description Page size. */
                pageSize?: components["parameters"]["PageSize"];
            };
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Page of notifications. */
            200: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content: {
                    /**
                     * @example {
                     *       "meta": {
                     *         "page": 1,
                     *         "pageSize": 20,
                     *         "total": 5
                     *       },
                     *       "items": [
                     *         {
                     *           "id": "7d8e9f0a-1b2c-4d3e-9f4a-5b6c7d8e9f0a",
                     *           "type": "ALERT_PRICE_DROP",
                     *           "payload": {
                     *             "subscriptionId": "5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d",
                     *             "trigger": "PRICE_DROP",
                     *             "productId": "3b2f0c4e-6a1d-4f0e-9c3a-1a2b3c4d5e01",
                     *             "productCode": "GOLD-A",
                     *             "brand": "Gold",
                     *             "productName": "Gold A Multivitamin",
                     *             "countryCode": "TR",
                     *             "currency": "TRY",
                     *             "thresholdPercent": 5,
                     *             "baselinePrice": 800,
                     *             "before": {
                     *               "effectivePrice": 800,
                     *               "inStock": true,
                     *               "retailer": "Retailer One",
                     *               "url": "https://shop.example.com/products/gold-a"
                     *             },
                     *             "after": {
                     *               "effectivePrice": 752,
                     *               "inStock": true,
                     *               "retailer": "Retailer One",
                     *               "url": "https://shop.example.com/products/gold-a"
                     *             }
                     *           },
                     *           "createdAt": "2026-11-24T07:00:00Z",
                     *           "readAt": null
                     *         },
                     *         {
                     *           "id": "8e9f0a1b-2c3d-4e4f-8a5b-6c7d8e9f0a1b",
                     *           "type": "ALERT_BACK_IN_STOCK",
                     *           "payload": {
                     *             "subscriptionId": "6b7c8d9e-0f1a-4b2c-9d3e-4f5a6b7c8d9e",
                     *             "trigger": "BACK_IN_STOCK",
                     *             "productId": "3b2f0c4e-6a1d-4f0e-9c3a-1a2b3c4d5e02",
                     *             "productCode": "GOLD-B",
                     *             "brand": "Gold",
                     *             "productName": "Gold B Multivitamin",
                     *             "countryCode": "TR",
                     *             "currency": "TRY",
                     *             "thresholdPercent": null,
                     *             "baselinePrice": null,
                     *             "before": {
                     *               "effectivePrice": 900,
                     *               "inStock": false,
                     *               "retailer": "Retailer One",
                     *               "url": "https://shop.example.com/products/gold-b"
                     *             },
                     *             "after": {
                     *               "effectivePrice": 900,
                     *               "inStock": true,
                     *               "retailer": "Retailer One",
                     *               "url": "https://shop.example.com/products/gold-b"
                     *             }
                     *           },
                     *           "createdAt": "2026-11-22T07:00:00Z",
                     *           "readAt": "2026-11-22T09:00:00Z"
                     *         },
                     *         {
                     *           "id": "9f0a1b2c-3d4e-4f5a-9b6c-7d8e9f0a1b2c",
                     *           "type": "NOTIFICATION_CAP_REACHED",
                     *           "payload": {
                     *             "periodMonth": "2026-10-01",
                     *             "limit": 30
                     *           },
                     *           "createdAt": "2026-10-28T07:00:00Z",
                     *           "readAt": "2026-10-28T08:00:00Z"
                     *         },
                     *         {
                     *           "id": "0a1b2c3d-4e5f-4a6b-8c7d-8e9f0a1b2c3d",
                     *           "type": "PLAN_EXPIRED",
                     *           "payload": {
                     *             "expiredPlanCode": "PRO",
                     *             "planCode": "FREE",
                     *             "deactivatedAlertSubscriptions": 2
                     *           },
                     *           "createdAt": "2026-10-01T00:05:00Z",
                     *           "readAt": "2026-10-01T10:00:00Z"
                     *         },
                     *         {
                     *           "id": "1b2c3d4e-5f6a-4b7c-9d8e-9f0a1b2c3d4e",
                     *           "type": "PLAN_ACTIVATED",
                     *           "payload": {
                     *             "planCode": "PRO",
                     *             "source": "PROMO",
                     *             "currentPeriodEnd": "2026-10-01T00:00:00Z",
                     *             "extended": false
                     *           },
                     *           "createdAt": "2026-09-01T00:00:00Z",
                     *           "readAt": "2026-09-01T00:10:00Z"
                     *         }
                     *       ]
                     *     }
                     */
                    "application/json": components["schemas"]["NotificationPage"];
                };
            };
            401: components["responses"]["Unauthorized"];
            422: components["responses"]["UnprocessableEntity"];
            500: components["responses"]["InternalError"];
        };
    };
    markNotificationRead: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path: {
                /** @description Notification id. */
                id: components["parameters"]["NotificationIdPath"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Marked as read. */
            204: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content?: never;
            };
            401: components["responses"]["Unauthorized"];
            404: components["responses"]["NotFound"];
            500: components["responses"]["InternalError"];
        };
    };
    markAllNotificationsRead: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description All notifications marked as read. */
            204: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content?: never;
            };
            401: components["responses"]["Unauthorized"];
            500: components["responses"]["InternalError"];
        };
    };
    getUnreadNotificationCount: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Unread count. */
            200: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content: {
                    /**
                     * @example {
                     *       "unreadCount": 3
                     *     }
                     */
                    "application/json": components["schemas"]["UnreadCount"];
                };
            };
            401: components["responses"]["Unauthorized"];
            500: components["responses"]["InternalError"];
        };
    };
    redeemPromoCode: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                /**
                 * @example {
                 *       "code": "LAUNCH-7K3M9QXT2B4N6PRW"
                 *     }
                 */
                "application/json": components["schemas"]["PromoRedeemRequest"];
            };
        };
        responses: {
            /** @description PRO activated or extended. */
            200: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content: {
                    /**
                     * @example {
                     *       "plan": "PRO",
                     *       "subscription": {
                     *         "id": "4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b",
                     *         "planCode": "PRO",
                     *         "status": "ACTIVE",
                     *         "source": "PROMO",
                     *         "startedAt": "2026-11-24T08:30:00Z",
                     *         "currentPeriodEnd": "2026-12-24T08:30:00Z",
                     *         "canceledAt": null
                     *       }
                     *     }
                     */
                    "application/json": components["schemas"]["SubscriptionActivation"];
                };
            };
            400: components["responses"]["PromoInvalid"];
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            409: components["responses"]["Conflict"];
            413: components["responses"]["PayloadTooLarge"];
            415: components["responses"]["UnsupportedMediaType"];
            422: components["responses"]["UnprocessableEntity"];
            429: components["responses"]["TooManyRequests"];
            500: components["responses"]["InternalError"];
        };
    };
    createCheckoutSession: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Checkout session created. */
            200: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content: {
                    /**
                     * @example {
                     *       "url": "https://checkout.stripe.com/c/pay/cs_test_a1B2c3D4e5F6g7H8"
                     *     }
                     */
                    "application/json": components["schemas"]["BillingRedirect"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            409: components["responses"]["BillingConflict"];
            429: components["responses"]["TooManyRequests"];
            500: components["responses"]["InternalError"];
            503: components["responses"]["BillingUnavailable"];
        };
    };
    createBillingPortalSession: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Portal session created. */
            200: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content: {
                    /**
                     * @example {
                     *       "url": "https://billing.stripe.com/p/session/test_YWNjdF8xUXdFcnR5"
                     *     }
                     */
                    "application/json": components["schemas"]["BillingRedirect"];
                };
            };
            401: components["responses"]["Unauthorized"];
            409: components["responses"]["BillingConflict"];
            429: components["responses"]["TooManyRequests"];
            500: components["responses"]["InternalError"];
            503: components["responses"]["BillingUnavailable"];
        };
    };
    receiveStripeWebhook: {
        parameters: {
            query?: never;
            header: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
                /** @description `t=<unix seconds>,v1=<hex HMAC-SHA256>[,v1=...]`; other entries are ignored. */
                "Stripe-Signature": string;
            };
            path?: never;
            cookie?: never;
        };
        /** @description The Stripe event exactly as sent; verified as raw bytes before parsing. */
        requestBody: {
            content: {
                "application/json": components["schemas"]["StripeEvent"];
            };
        };
        responses: {
            /** @description Accepted (applied, already applied or ignored). */
            200: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content: {
                    /**
                     * @example {
                     *       "received": true
                     *     }
                     */
                    "application/json": components["schemas"]["WebhookAck"];
                };
            };
            400: components["responses"]["WebhookRejected"];
            413: components["responses"]["PayloadTooLarge"];
            429: components["responses"]["TooManyRequests"];
            500: components["responses"]["InternalError"];
            503: components["responses"]["BillingUnavailable"];
        };
    };
    getAdminHealth: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Detailed status. */
            200: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content: {
                    /**
                     * @example {
                     *       "status": "UP",
                     *       "checkedAt": "2026-11-24T08:30:00Z",
                     *       "components": {
                     *         "scoring": {
                     *           "status": "UP"
                     *         },
                     *         "database": {
                     *           "status": "UP"
                     *         },
                     *         "fx": {
                     *           "status": "UP",
                     *           "newestAsOf": "2026-11-23",
                     *           "rateAgeDays": 1,
                     *           "stale": false,
                     *           "unsupportedBases": []
                     *         },
                     *         "outbox": {
                     *           "status": "UP",
                     *           "pending": 0,
                     *           "failed": 0,
                     *           "deadLetter": 0,
                     *           "oldestPendingAt": null
                     *         }
                     *       },
                     *       "jobs": [
                     *         {
                     *           "name": "OUTBOX_PROCESSOR",
                     *           "lastRunAt": "2026-11-24T08:29:55Z",
                     *           "lastOutcome": "SUCCEEDED"
                     *         },
                     *         {
                     *           "name": "FX_REFRESH",
                     *           "lastRunAt": "2026-11-24T06:00:02Z",
                     *           "lastOutcome": "SUCCEEDED"
                     *         },
                     *         {
                     *           "name": "SUBSCRIPTION_EXPIRY",
                     *           "lastRunAt": "2026-11-24T00:05:01Z",
                     *           "lastOutcome": "SUCCEEDED"
                     *         },
                     *         {
                     *           "name": "HOUSEKEEPING_IDENTITY",
                     *           "lastRunAt": "2026-11-24T03:15:04Z",
                     *           "lastOutcome": "SUCCEEDED"
                     *         },
                     *         {
                     *           "name": "HOUSEKEEPING_ALERTS",
                     *           "lastRunAt": "2026-11-24T03:15:03Z",
                     *           "lastOutcome": "SUCCEEDED"
                     *         }
                     *       ]
                     *     }
                     */
                    "application/json": components["schemas"]["AdminHealth"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            500: components["responses"]["InternalError"];
        };
    };
    importProducts: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path?: never;
            cookie?: never;
        };
        /** @description Products document; its structure is defined by `contracts/schemas/import-products.schema.json`. */
        requestBody: {
            content: {
                "application/json": components["schemas"]["import-products.schema"];
            };
        };
        responses: {
            /** @description Import job created. */
            202: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    Location: components["headers"]["ImportJobLocation"];
                    [name: string]: unknown;
                };
                content: {
                    /**
                     * @example {
                     *       "id": "2c3d4e5f-6a7b-4c8d-9e0f-1a2b3c4d5e6f",
                     *       "kind": "PRODUCTS_JSON",
                     *       "status": "RUNNING",
                     *       "createdAt": "2026-11-24T08:30:00Z",
                     *       "finishedAt": null,
                     *       "summary": null
                     *     }
                     */
                    "application/json": components["schemas"]["ImportJob"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            413: components["responses"]["PayloadTooLarge"];
            415: components["responses"]["UnsupportedMediaType"];
            422: components["responses"]["UnprocessableEntity"];
            500: components["responses"]["InternalError"];
        };
    };
    importOffers: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "multipart/form-data": components["schemas"]["OffersImportUpload"];
            };
        };
        responses: {
            /** @description Import job created. */
            202: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    Location: components["headers"]["ImportJobLocation"];
                    [name: string]: unknown;
                };
                content: {
                    /**
                     * @example {
                     *       "id": "2c3d4e5f-6a7b-4c8d-9e0f-1a2b3c4d5e6f",
                     *       "kind": "OFFERS_CSV",
                     *       "status": "RUNNING",
                     *       "createdAt": "2026-11-24T08:30:00Z",
                     *       "finishedAt": null,
                     *       "summary": null
                     *     }
                     */
                    "application/json": components["schemas"]["ImportJob"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            413: components["responses"]["PayloadTooLarge"];
            415: components["responses"]["UnsupportedMediaType"];
            422: components["responses"]["UnprocessableEntity"];
            500: components["responses"]["InternalError"];
        };
    };
    getImportJob: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path: {
                /** @description Import job id. */
                jobId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Import job. */
            200: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content: {
                    /**
                     * @example {
                     *       "id": "2c3d4e5f-6a7b-4c8d-9e0f-1a2b3c4d5e6f",
                     *       "kind": "OFFERS_CSV",
                     *       "status": "FAILED",
                     *       "createdAt": "2026-11-24T08:30:00Z",
                     *       "finishedAt": "2026-11-24T08:30:02Z",
                     *       "summary": {
                     *         "rows": 120,
                     *         "inserted": 0,
                     *         "updated": 0,
                     *         "unchanged": 0,
                     *         "deleted": 0,
                     *         "changedPairs": 0
                     *       },
                     *       "errors": [
                     *         {
                     *           "rowNumber": 17,
                     *           "code": "currency_mismatch",
                     *           "message": "currency_mismatch: currency EUR differs from the country currency TRY (row 17)"
                     *         },
                     *         {
                     *           "rowNumber": 42,
                     *           "code": "invalid_offer_url",
                     *           "message": "invalid_offer_url: url must be an absolute http or https URI (row 42)"
                     *         }
                     *       ]
                     *     }
                     */
                    "application/json": components["schemas"]["ImportJobDetail"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            500: components["responses"]["InternalError"];
        };
    };
    listUsers: {
        parameters: {
            query?: {
                /** @description Case-insensitive substring search over the e-mail address. Control characters are rejected (422). */
                q?: string;
                /** @description 1-based page number. */
                page?: components["parameters"]["Page"];
                /** @description Page size. */
                pageSize?: components["parameters"]["PageSize"];
            };
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Page of users. */
            200: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content: {
                    /**
                     * @example {
                     *       "meta": {
                     *         "page": 1,
                     *         "pageSize": 20,
                     *         "total": 1
                     *       },
                     *       "items": [
                     *         {
                     *           "id": "9f1c2d3e-4b5a-4c6d-8e7f-0a1b2c3d4e5f",
                     *           "email": "user@example.com",
                     *           "role": "USER",
                     *           "status": "ACTIVE",
                     *           "plan": "FREE",
                     *           "emailVerified": true,
                     *           "createdAt": "2026-11-01T10:00:00Z"
                     *         }
                     *       ]
                     *     }
                     */
                    "application/json": components["schemas"]["AdminUserPage"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            422: components["responses"]["UnprocessableEntity"];
            500: components["responses"]["InternalError"];
        };
    };
    grantSubscription: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path: {
                /** @description User id. */
                id: components["parameters"]["UserIdPath"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                /**
                 * @example {
                 *       "planCode": "PRO",
                 *       "durationDays": 30
                 *     }
                 */
                "application/json": components["schemas"]["GrantSubscriptionRequest"];
            };
        };
        responses: {
            /** @description Subscription now in effect for the user. */
            200: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Subscription"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            409: components["responses"]["Conflict"];
            413: components["responses"]["PayloadTooLarge"];
            415: components["responses"]["UnsupportedMediaType"];
            422: components["responses"]["UnprocessableEntity"];
            500: components["responses"]["InternalError"];
        };
    };
    setUserStatus: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path: {
                /** @description User id. */
                id: components["parameters"]["UserIdPath"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                /**
                 * @example {
                 *       "status": "DISABLED"
                 *     }
                 */
                "application/json": components["schemas"]["SetUserStatusRequest"];
            };
        };
        responses: {
            /** @description Updated user. */
            200: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["AdminUser"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            413: components["responses"]["PayloadTooLarge"];
            415: components["responses"]["UnsupportedMediaType"];
            422: components["responses"]["UnprocessableEntity"];
            500: components["responses"]["InternalError"];
        };
    };
    listPromoCodes: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Promo codes. */
            200: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PromoCodeList"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            500: components["responses"]["InternalError"];
        };
    };
    createPromoCode: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                /**
                 * @example {
                 *       "prefix": "LAUNCH",
                 *       "planCode": "PRO",
                 *       "durationDays": 30,
                 *       "maxRedemptions": 100,
                 *       "expiresAt": "2027-01-31T23:59:59Z"
                 *     }
                 */
                "application/json": components["schemas"]["CreatePromoCodeRequest"];
            };
        };
        responses: {
            /** @description Promo code created. */
            201: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content: {
                    /**
                     * @example {
                     *       "code": "LAUNCH-7K3M9QXT2B4N6PRW",
                     *       "planCode": "PRO",
                     *       "durationDays": 30,
                     *       "maxRedemptions": 100,
                     *       "redeemedCount": 0,
                     *       "expiresAt": "2027-01-31T23:59:59Z",
                     *       "createdBy": "9f1c2d3e-4b5a-4c6d-8e7f-0a1b2c3d4e5f"
                     *     }
                     */
                    "application/json": components["schemas"]["PromoCode"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            413: components["responses"]["PayloadTooLarge"];
            415: components["responses"]["UnsupportedMediaType"];
            422: components["responses"]["UnprocessableEntity"];
            500: components["responses"]["InternalError"];
        };
    };
    deletePromoCode: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path: {
                /** @description Promo code. */
                code: components["schemas"]["PromoCodeValue"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Deleted. */
            204: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content?: never;
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            404: components["responses"]["NotFound"];
            500: components["responses"]["InternalError"];
        };
    };
    refreshFxRates: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Refresh finished. */
            200: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["FxRefreshResult"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            409: components["responses"]["Conflict"];
            500: components["responses"]["InternalError"];
        };
    };
    getOutboxStats: {
        parameters: {
            query?: never;
            header?: {
                /**
                 * @description Client-supplied request id. Accepted only when it matches the pattern;
                 *     otherwise, or when absent, the API generates a new id. Never rejected.
                 */
                "X-Request-Id"?: components["parameters"]["XRequestId"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Counts. */
            200: {
                headers: {
                    "X-Request-Id": components["headers"]["XRequestId"];
                    [name: string]: unknown;
                };
                content: {
                    /**
                     * @example {
                     *       "pending": 3,
                     *       "failed": 1,
                     *       "deadLetter": 0,
                     *       "oldestPendingAt": "2026-11-24T08:20:00Z"
                     *     }
                     */
                    "application/json": components["schemas"]["OutboxStats"];
                };
            };
            401: components["responses"]["Unauthorized"];
            403: components["responses"]["Forbidden"];
            500: components["responses"]["InternalError"];
        };
    };
}
type WithRequired<T, K extends keyof T> = T & {
    [P in K]-?: T[P];
};
