# Phase 8 report: payment provider (Stripe, test mode)

Date: 2026-11-25. Branch: `feat/phase-8-billing-provider`.

The owner approved the optional Phase 8 of the project specification (§13.10) and chose Stripe in test mode. This report covers the contracts and the API (sections 1 to 10) and the web part (checkout button on `/upgrade`, return handling, billing-portal entry on `/account`; section 11).

## 1. Completed

- Contract first: three new operations (`POST /billing/checkout-session`, `POST /billing/portal-session`, `POST /billing/webhooks/stripe`), `Plan.checkoutAvailable`, three problem codes; the typed web client was regenerated (section 8).
- `BillingProvider` adapter `StripeBillingProvider` behind the existing billing port, with a new request kind `ActivationRequest.ProviderPeriod`; subscriptions with `source = PROVIDER` and the Stripe subscription id in `external_ref`, changed only under the existing per-user row lock (`SubscriptionService.applyProviderPeriod`, `SubscriptionService.endProviderSubscription`).
- Hosted Stripe Checkout (subscription mode, one recurring monthly price) and Stripe Billing Portal (payment methods, invoices, cancellation); the API creates sessions and returns their URLs, built only from `VITADELTA_WEB_BASE_URL`.
- Webhook receiver: raw-body signature verification before parsing (HMAC-SHA256 over `t.payload`, constant-time comparison of every `v1`, 5-minute window in both directions, missing, repeated and malformed headers rejected), one generic `400` for every rejection, idempotency through a processed-events table, current subscription state read from Stripe for every handled event, forward-only application (periods never shrink, an ended subscription never comes back), user mapping only through the user id attached at checkout (never e-mail), events of the other mode and unknown types acknowledged and ignored. Own body limit (256 kB) and rate cap (300 per minute per client address); no credentials and no CSRF token.
- `plan-activated` (first activation) and `plan-expired` (provider subscription ended) mails through the existing asynchronous mail dispatcher; the alert module's existing listeners create the in-app notifications from the published events.
- Account deletion cancels the user's live Stripe subscriptions after the deletion commits.
- Configuration through `VITADELTA_BILLING_PROVIDER` (none|stripe, default none), `VITADELTA_STRIPE_SECRET_KEY`, `VITADELTA_STRIPE_WEBHOOK_SECRET`, `VITADELTA_STRIPE_PRICE_ID`, `VITADELTA_STRIPE_ALLOW_LIVE`; fail fast before the database is contacted; live keys refused unless explicitly allowed; secrets never printed. Documented in `.env.example` (placeholders only) and passed through `infra/docker-compose.yml`.
- Flyway migration V7 (`billing_customer`, `billing_subscription`, `billing_webhook_event`).
- ADR-0014, runbook section 7.1 (test-mode product and price, portal configuration, `stripe listen`, webhook secret and API key rotation, troubleshooting), README (English and Turkish), architecture, ADR-0005 (pins), ADR-0010 and ADR-0013 cross-references.

## 2. Files by area

| Area | Files |
|---|---|
| Contracts (ORCH) | `contracts/public-api.openapi.yaml` |
| Web, generated only | `apps/web/src/app/core/api/schema.d.ts` (`npm run api:types`) |
| Billing (AGENT_IDENTITY) | new: `BillingProperties`, `StripeSignatureVerifier`, `StripeApi`, `StripeApiException`, `StripeSubscription`, `StripeBillingProvider`, `BillingAccountRepository`, `StripeWebhookService`, `ProviderCheckoutService`, `ProviderBillingController`, `ProviderCheckoutAvailability`, `BillingAccountDeletionListener`; changed: `ActivationRequest`, `BillingProvider`, `BillingApi` |
| Entitlement (AGENT_IDENTITY) | new: `CheckoutAvailability`; changed: `Subscription` (`externalRef`), `SubscriptionRepository`, `SubscriptionService`, `SubscriptionSource`, `PlanController` |
| Migration (AGENT_IDENTITY) | `V7__billing_provider.sql` |
| Shared files (AGENT_API boundary, applied by this worker) | `ProblemCode` (3 codes), `RateLimitPolicy` (3 policies), `HttpLimitsProperties` (webhook body limit), `SecurityConfig` (`/billing/webhooks/**` public, `/billing/**` authenticated), `StartupValidationConfig` (`BillingProperties` validated first), `application.yml` (`vitadelta.billing.*`, `vitadelta.http.webhook-*`) |
| Tests | new: `StripeSignatureVerifierTest`, `BillingPropertiesTest`, `StripeApiTest`, `StripeIntegrationTest` (base), `StripeWebhookIntegrationTest`, `StripeCheckoutIntegrationTest`, `BillingProviderDisabledIntegrationTest`; changed: `OpenApiContractTest`, `MigrationGapTest`, `ProblemsTest`, `PropertiesValidationTest`, `RequestFiltersTest`, `StartupFailFastTest` |
| Infra | `.env.example`, `infra/docker-compose.yml` |
| Docs | `docs/adr/0014-stripe-billing-provider.md`, `docs/adr/0005-version-pins.md`, `docs/adr/0010-billing-port.md`, `docs/adr/0013-migration-numbering.md`, `docs/runbook.md`, `docs/architecture.md`, `README.md`, `README.tr.md`, this report |

## 3. Tests and checks

| Check | Command | Result |
|---|---|---|
| API, full gate | `cd services/api && ./gradlew check --rerun-tasks` (JDK 25) | PASS: 1503 tests, 0 failures, 0 skipped; spotless and the JaCoCo gate green (line coverage 96.35 %, branch 86.65 %; package `billing` 96.07 % lines) |
| Contract lint | `npx @redocly/cli@2.83.0 lint contracts/public-api.openapi.yaml` | valid; the same 7 pre-existing warnings as on `main` |
| Typed web client | `cd apps/web && npm run api:types`, then `npm run api:check` | regenerated, current |
| Security scan | `scripts/security-scan.sh` | OK (gitleaks history and tree, env files, SQL concatenation, npm and pip audit, Dependabot, pinned actions PASS; dependency-check SKIPPED without `NVD_API_KEY`) |
| Web type check, suites and e2e | see section 11.3 | PASS after the web part |
| `scripts/test-all.sh` | | not run as a whole in this phase yet (phase gate) |

New tests (all against a WireMock stub of the Stripe API, keys and secrets generated per run):

- `StripeSignatureVerifierTest`: valid signature; any matching `v1` among several (rotation); `v0` and debug entries ignored; tampered and reformatted body; another secret; changed timestamp; replay window in both directions with the exact boundary; missing and repeated header; twelve malformed header forms; repeated `t`; oversized header; too many signatures; non-hex and upper-case signatures. Expected signatures are computed independently in the test.
- `BillingPropertiesTest`: provider off by default (empty, `none`); unknown provider; test and restricted test keys; live keys refused unless allowed; each required value; malformed values without echoing secrets; API base URL (https, or http to loopback only); limits; `toString` hides secrets.
- `StripeApiTest`: Checkout form fields, `Authorization` and `Stripe-Version` headers; customer reuse; non-https or malformed session URLs; portal session; Stripe error type and code without the message; subscription parsing (latest item period end, expanded invoice, metadata, expanded customer, end time); grant rule per status and invoice; malformed and duplicate-key bodies; ids checked before they reach a URL; DELETE cancellation; 404, connection reset, timeout, oversized body, refused connection; unconfigured adapter.
- `StripeWebhookIntegrationTest`: checkout activates PRO (`PROVIDER`, `external_ref`, period end, customer mapping, `plan-activated` mail, event, pinned headers to Stripe, nothing provider-specific in `GET /me`); idempotent replay with fresh signatures (one mail, Stripe read once); six parallel deliveries of one event apply once; paid renewal extends without a mail; open and failed renewals grant nothing until paid; deletion downgrades at once with `plan-expired`; a late active read never revives an ended subscription; an older period never shortens a newer one; checkout on top of a promo period keeps the later end; user mapping only through metadata (mismatch with `client_reference_id`, missing metadata, other price, missing client reference); customer of another user refused; other mode, unknown type and payment-mode sessions ignored; Stripe outage answers 503 and the retry applies; unknown subscription ignored; nine unauthentic variants all get the identical generic 400 and change nothing; no credentials needed, 100 kB body accepted, 300 kB refused with 413, per-address cap of 300 per minute with `Retry-After`; account deletion cancels the Stripe subscription after commit and a later deleted event is recorded as `user_missing`; a failed cancellation does not block the deletion.
- `StripeCheckoutIntegrationTest`: checkout bound to the user (client reference and both metadata fields, return URLs from `VITADELTA_WEB_BASE_URL`, locale, no e-mail sent to Stripe, no subscription change); customer reuse; 401, 403 `email_not_verified`, 409 `plan_already_active`; Stripe failure is 503 without details; per-user limits of checkout and portal; portal 409 `no_billing_account`, return URL, non-https portal URL refused; `checkoutAvailable` true for PRO only.
- `BillingProviderDisabledIntegrationTest`: `checkoutAvailable` false; checkout, portal and webhook answer 503 `billing_unavailable`; authentication still required.
- `StartupFailFastTest`: provider `stripe` without a key, with a live key, and an unknown provider stop the startup before the database is contacted.
- Updated: `OpenApiContractTest` (three new operations implemented; one documented gap), `MigrationGapTest` (V7 in both delivery orders), `ProblemsTest`, `PropertiesValidationTest`, `RequestFiltersTest`.

## 4. Deviations and assumptions

- **Plain REST instead of the Stripe SDK.** `stripe-java` 34.2.0 was evaluated (works on JDK 25, pinned to the same API version) and not used: four calls and the signature check do not justify the SDK and Gson on the runtime classpath (ADR-0014).
- **Current state over payload.** Every handled event triggers one `GET /v1/subscriptions/{id}`; the payload is only used to locate the subscription. This makes ordering irrelevant and keeps one parsed shape.
- **Grant rule.** A period is granted only when the subscription is `active`/`trialing` and its latest invoice is `paid`. Stripe moves `current_period_end` when the renewal invoice is created; granting on that alone would give an unpaid month.
- **Future timestamps rejected.** Stripe's libraries reject only signing times older than the tolerance; this adapter also rejects times more than 5 minutes ahead.
- **Renewals send no mail.** `plan-activated` is sent on activation only, `plan-expired` when a provider subscription ends; a monthly renewal mail was not specified.
- **Webhook ignored cases answer 200.** Unknown types, other-mode events and subscriptions not created by this API's checkout are acknowledged so Stripe stops retrying; a Stripe outage while reading answers `503` so Stripe retries.
- **Account deletion** cancels the user's live Stripe subscriptions after commit (not listed in §13.10; without it a deleted user would keep being charged). A failed cancellation does not block the deletion; it is logged and counted for the operator.
- **`checkoutAvailable` does not depend on the caller.** A PRO caller gets `409 plan_already_active` from checkout; the web app reads the caller's plan from `GET /me`.
- **`billing_webhook_event` is kept indefinitely.** No personal data, a few rows per paying user per month; no purge job was added.
- **Overlapping subscriptions** (review finding, fixed): the entitlement is tracked per Stripe subscription (`paid_through`, migration V8) and follows the live subscription paid furthest ahead; an event of one subscription only extends or ends that subscription's own row, and ending one keeps PRO while another still pays. Checkout is refused while a provider subscription has not ended, concurrent checkouts are serialized, and the previous open session is expired before a new one is created. The portal follows the customer of the entitled subscription; ended subscriptions never set it.
- **Known limits** (runbook 7.1): a promo period redeemed on top of a provider period is lost when the provider subscription ends early; a second subscription can still arise when Stripe silently keeps an old session payable or a subscription is created outside the API (counted and logged; entitlement stays correct; the operator cancels and refunds one).

## 5. Unverified or skipped

- No call was made to the real Stripe API and no real key was used: every test runs against a WireMock stub answering the documented shapes. The end-to-end flow with a real test-mode account (`stripe listen`, test card) has not been run.
- The Stripe facts (latest API version `2026-11-18.endive`, Endive breaking changes, subscription and invoice fields, the manual signature scheme, `stripe-java` 34.2.0) were read from Stripe's documentation, changelog, the `stripe-java` source on GitHub and Maven Central on 2026-11-25.
- The §15.7 adversarial cases for the billing routes are not written: by the adversarial-test rule they belong to AGENT_REDTEAM, not to the implementing agent. Suggested cases: signature forgery and replay through nginx, header smuggling of `Stripe-Signature`, metadata spoofing, customer takeover, oversized and chunked bodies, rate-cap bypass with forwarded headers.
- Stripe recommends allow-listing its webhook source addresses; not configured in nginx (the signature is the control). Left to the deployment.

## 6. Decisions for the owner

- Whether to keep the `stripe` provider off in the default local stack (current) and how the Stripe test account, price and webhook secret are provisioned for shared environments.
- Whether a monthly renewal e-mail is wanted.
- Whether promo redemption should be blocked while a provider subscription is active (avoids the lost-promo-period limit above).
- Live mode remains out of scope until a separate decision (`VITADELTA_STRIPE_ALLOW_LIVE`).

## 7. Proposed scope changes

- An admin view of a user's provider state (customer, subscription status, last webhook outcome) and an admin "cancel at Stripe" action, to replace the SQL and CLI steps of runbook 7.1.

## 8. Branch, commits and contract changes

Branch `feat/phase-8-billing-provider`, created from `main` at `7c4e3b6`. Not pushed; no PR opened.

- `fe191be` feat(contracts): add the Stripe checkout, billing portal and webhook operations
- `e29c18e` chore(web): regenerate the typed API client for the billing operations
- `08f053a` fix(contracts): show the generic webhook rejection detail in its example
- `8ec51b4` chore(web): regenerate the typed API client after the webhook example fix
- `f9ebb92` feat(identity): add the billing provider tables in migration V7
- `20b215f` feat(identity): apply and end payment-provider subscription periods
- `3652600` feat(api): add billing problem codes, rate limits and the webhook body limit
- `61cba34` feat(billing): configure the optional Stripe provider and fail fast on bad settings
- `47126ee` feat(billing): verify Stripe webhook signatures over the raw body
- `cdb2554` feat(billing): call the Stripe REST API with a pinned API version
- `974bd8e` feat(identity): report checkoutAvailable on the plans
- `7c60799` feat(billing): add the Stripe adapter of the billing port and its repository
- `c547f3f` feat(billing): serve hosted checkout, billing portal and the Stripe webhook
- `1807664` feat(billing): cancel the Stripe subscription of a deleted account after commit
- `0c2a789` feat(infra): pass the optional Stripe settings to the API and document them
- `c8a90d1` docs: record the Stripe provider decision and the pinned API version
- `285b365` docs: add the Stripe test-mode setup, webhook forwarding and secret rotation to the runbook
- `3b5e162` docs: mention the optional Stripe provider in the READMEs and the architecture
- this report: docs: add the Phase 8 report for the contracts and the API

Contract changes (`contracts/public-api.openapi.yaml`):

| Change | Field / operation | Reason | Compatibility |
|---|---|---|---|
| New operation | `POST /billing/checkout-session` → `BillingRedirect { url }`; 401, 403 `email_not_verified`, 409 `plan_already_active`, 429, 503 `billing_unavailable` | hosted Stripe Checkout for PRO | additive |
| New operation | `POST /billing/portal-session` → `BillingRedirect`; 401, 409 `no_billing_account`, 429, 503 | cancellation and payment methods on the Stripe Billing Portal | additive |
| New operation | `POST /billing/webhooks/stripe` (`security: []`, header `Stripe-Signature`, body `StripeEvent`) → `WebhookAck { received }`; 400 generic, 413, 429, 503 | signature-authenticated provider webhook | additive |
| New required property | `Plan.checkoutAvailable` (boolean) | the web app shows the checkout button from the server's answer | additive for readers; typed clients that construct `Plan` objects must add it (web fixtures, section 11) |
| New problem types | `billing_unavailable` (503), `plan_already_active` (409), `no_billing_account` (409) in `Problem.type` | billing errors | additive |
| New responses / schemas | `BillingConflict`, `BillingUnavailable`, `WebhookRejected`; `BillingRedirect`, `StripeEvent`, `WebhookAck`; tag `Billing` | | additive |
| Description | `PayloadTooLarge` names the 256 kB webhook limit | | none |

`OpenApiContractTest` lists one new accepted gap: the webhook binds the raw body as bytes (`request: kind contract=object code=scalar`), because the signature is verified over the exact bytes before parsing.

## 9. Next phase

The web part of Phase 8 (section 11), then the phase gate: `scripts/test-all.sh` including e2e, the AGENT_REDTEAM billing cases, the phase PR.

## 10. Awaiting approval

Awaiting the phase gate (`scripts/test-all.sh`, AGENT_REDTEAM billing cases) and the owner's review of this report.

## 11. Web part

### 11.1 Delivered

- **Problem codes.** `billing_unavailable`, `plan_already_active` and `no_billing_account` are listed in `PROBLEM_CODES` (`core/api/api-types.ts`) with `problem.<code>.title/.body` in the root `en` and `tr` files; the web type check passes again. `checkoutAvailable` was added to the typed `Plan` fixtures (`upgrade-page.component.spec.ts`, `results/testing/golden.fixtures.ts`).
- **Typed client.** `ApiClient.createCheckoutSession()` and `createBillingPortalSession()` (`POST /billing/checkout-session`, `POST /billing/portal-session`, no body; the bearer token comes from the interceptor). The webhook operation has no web caller.
- **Leaving the app safely** (`core/billing/billing-navigator.ts`). `BillingNavigator.open('checkout' | 'portal')` asks the API for a session and follows the returned URL only after `providerPageUrl` accepts it: an absolute `https:` URL on a subdomain of `stripe.com`, no credentials and no explicit port (also not `:443`, checked on the raw string because `URL` drops the default port), no whitespace, control character or backslash, at most 4096 characters. Anything else (another or look-alike host, `http:`, `javascript:`, `data:`, relative, malformed) is refused with the generic problem notice and the browser stays. The URL is never rendered; the browser is moved with `location.assign` behind the `ExternalNavigation` seam. The calling button passes an `AbortSignal` that is aborted when its component is destroyed (the user left the page or the route changed): the pending request is cancelled and a late answer never moves the browser.
- **`/upgrade`.** The PRO card renders `vd-checkout-panel`, which follows the server only: `Plan.checkoutAvailable` from `GET /plans`, plan and `subscription.source` from `GET /me`. Without a provider nothing is rendered (no checkout button). With one: visitors get "Log in to subscribe" (returns to `/upgrade`), PRO from a promo code or a grant gets a short note, everyone else gets "Subscribe to PRO" with a note that payment happens on Stripe's page. While the session is created the button is `aria-busy`/`aria-disabled`, a polite status says the payment page is opening and repeated clicks are ignored; after a successful hand-over it stays busy (and is reset when the page comes back from the back-forward cache). Problems: `401` leads to `/auth/login?returnUrl=/upgrade` (unless the interceptor is already navigating there); `403 email_not_verified` is a warning with a link to `/account` (where the e-mail can be resent); `409 plan_already_active` reloads `/me` and shows an information notice with a link to the account; `429` keeps the existing `Retry-After` countdown, and the checkout button itself stays disabled until the countdown ends; `503 billing_unavailable` and every other problem are retryable notices. The promo-code form stays. The how-to text is `upgrade.how_to_get_pro` (unchanged, "online payment is not available yet") without a provider and `upgrade.how_to_get_pro_online` when the server offers checkout.
- **Return from Checkout** (`vd-checkout-return`). `/upgrade?checkout=canceled` says that nothing was charged. `/upgrade?checkout=success` never assumes a plan change: it shows "Confirming your payment…" and reloads `/me` after pauses of 1, 2, 4 and 8 s (bounded, about 15 s) until the server reports PRO ("PRO is active until <date>", link to the account), otherwise "Your payment is being processed" with a "Check again" button (a fresh `/me` reload, then the same bounded schedule). A signed-out visitor is asked to log in. Polling stops when the page is left. The parameter is removed from the address after the arriving navigation (`replaceUrl`), so a reload does not replay the banner; unknown values are ignored.
- **Billing portal.** `vd-manage-subscription` ("Manage subscription", with a hint naming payment method, invoices and cancellation) appears in the plan card on `/account` and in the PRO card on `/upgrade` only when the ACTIVE subscription has `source = PROVIDER`. `409 no_billing_account` is an information notice; `401`, `429` (button held for the `Retry-After` countdown) and `503` are handled as above. The portal returns to `/account`, whose page already reloads `/me` on arrival.
- **Copy.** All new texts are in the `upgrade` and `account` scope files in `en` and `tr` (`upgrade.checkout.*`, `upgrade.return.*`, `upgrade.how_to_get_pro_online`, `account.billing.*`); `scripts/check-i18n.mjs` and the translation parity test pass.
- **Layout and accessibility.** Existing tokens and classes only (`card`, `btn`, `btn-primary`, `vd-notice`, `vd-problem-notice`, `hint`, `live-region`). The payment buttons wrap instead of `nowrap`, so long Turkish labels and wide fallback fonts never widen a card; the payment part sits at the bottom of the PRO card. Keyboard use was checked end to end (focus and Enter start the checkout); axe (WCAG 2.1 AA) finds no serious or critical violation on the upgrade page before and after checkout and at every width below.
- **Disclaimer.** `disclaimer.not_medical_advice` stays where the other surfaces render it (results, compare, product detail, saved products); `/upgrade` and `/account` are not recommendation or comparison surfaces and did not show it before, and the footer disclaimer is on every page. Not added there.

### 11.2 Files

| Area | Files |
|---|---|
| Core | `core/api/api-types.ts`, `core/api/api-client.ts`, new `core/billing/billing-navigator.ts` |
| Upgrade | `upgrade-page.component.{ts,html,scss}`, new `components/checkout-panel.component.ts`, new `components/checkout-return.component.ts` |
| Account | `components/plan-card.component.ts`, new `components/manage-subscription.component.ts` |
| i18n | `public/i18n/{en,tr}.json`, `public/i18n/upgrade/{en,tr}.json`, `public/i18n/account/{en,tr}.json` |
| Tests | new `core/billing/billing-navigator.spec.ts`, `features/upgrade/components/checkout-return.component.spec.ts`, `features/account/components/manage-subscription.component.spec.ts`, `e2e/07-checkout.e2e.ts`, `e2e/support/billing-api.ts`; changed `api-client.spec.ts`, `upgrade-page.component.spec.ts`, `account-page.component.spec.ts`, `app.flows.spec.ts`, `testing/fake-backend.ts`, `results/testing/golden.fixtures.ts` |

All paths are under `apps/web/src/app` unless they start with `public/` or `e2e/` (under `apps/web`). No contract change; `schema.d.ts` is unchanged.

### 11.3 Tests and checks

| Check | Command | Result |
|---|---|---|
| Web gate | `cd apps/web && npm run lint && npm test && npm run build` | PASS: lint (ESLint, Prettier, e2e type checks, i18n literal check); 67 files, 779 tests; coverage 95.65 % statements, 89.33 % branches; initial bundle 117.52 kB gzipped (limit 380 kB) |
| Typed client | `npm run api:check` | `schema.d.ts` matches the contract |
| Checkout e2e | `npx playwright test --project=e2e e2e/07-checkout.e2e.ts` (headless) | PASS: 9 tests (also after the review fixes, against a rebuilt web image) |
| Full e2e | `npm run e2e` against the local stack (`scripts/dev-up.sh`, `scripts/seed.sh`, API restarted first) | PASS: 57 tests (01 to 07), no retries, before the review fixes; after them the checkout e2e was rerun (row above) |

New and changed tests:

- `billing-navigator.spec.ts`: accepted Checkout and Portal URLs; 27 refused URL shapes (http, other and look-alike hosts, bare and trailing-dot domain, credentials, empty user name, user-info and backslash tricks, explicit port including `:443` and an empty port, whitespace, tab and line break, extra or missing slashes, `javascript:`, `data:`, relative, protocol-relative, malformed, empty, overlong) and non-strings; the API problem is passed on; a non-provider answer never navigates; an aborted caller cancels the request and never navigates, also when the answer arrives late.
- `upgrade-page.component.spec.ts`: no button without a provider; button, note and how-to text with one; busy state and single request; redirect; back-forward cache; refused URL never rendered; visitor login link; promo PRO note; "Manage subscription" for `PROVIDER`; 401, 403, 409 (reloads `/me`), 429 countdown that also holds the checkout button and releases it when it ends, 503 retry; no redirect after the page was left; return handling through the real router (canceled banner and parameter removal, success waits for the server's PRO, unknown parameter ignored).
- `checkout-return.component.spec.ts`: outcome parsing; default schedule; PRO at once; PRO after the second reload; bounded attempts then "processing" and a fresh check on request; signed-out visitor; no reload after leaving the page.
- `manage-subscription.component.spec.ts`: portal hand-over, busy state, 409 `no_billing_account`, 503 retry, 429 countdown holding and then releasing the button, 401, no redirect after the page was left.
- `app.flows.spec.ts` against the stateful fake API (now serving `GET /plans` and both billing routes): checkout hand-over with the bearer token, return before the webhook (three bounded reloads, then "processing"), webhook lands, "Check again" shows PRO, portal from the account page; and no billing request or button without a provider.
- `07-checkout.e2e.ts` (mocked API; Stripe's hosts are served by local stand-in pages, so no request reaches Stripe): checkout by keyboard to the stand-in page, return with a delayed webhook until "PRO is active", portal from the account page, canceled return, no button without a provider; the payment parts at 375, 768 and 1280 px in English and Turkish with no sideways scroll and nothing past the viewport edge (tab rows and tables that scroll by design excepted, as in `06-long-text.e2e.ts`), axe on every width, no console errors or CSP violations.

### 11.4 Commits (web part)

- `12701e4` feat(web): know the billing problem codes and their messages
- `32ef7f3` test(web): add checkoutAvailable to the typed plan fixtures
- `c414d8f` feat(web): call the checkout and billing portal session operations
- `14909ff` feat(web): follow only checked payment-provider URLs to the hosted pages
- `f33af47` feat(web): open the billing portal for a provider subscription from the plan card
- `ec3f68a` feat(web): offer the hosted checkout on /upgrade and confirm the plan after the return
- `bdcb433` test(web): cover checkout, webhook delay and billing portal in the application flows
- `0b831d8` test(web): run the checkout and billing portal flow end to end against stand-in provider pages
- `e0d3329` docs: add the web part to the Phase 8 report
- `ec08c9e` fix(web): refuse provider URLs with an explicit port, backslashes or whitespace
- `1392ea9` fix(web): cancel a pending provider session request when its caller gives up
- `932a45d` fix(web): stop billing redirects after leaving the page and hold the buttons during Retry-After
- docs: record the review fixes of the web part

The three fixes follow an independent review of the web diff (accepted `:443`, a late session answer redirecting after the user left, the primary buttons bypassing the `Retry-After` countdown); each has a regression test that failed before the fix.

### 11.5 Deviations, unverified items and decisions

- **Provider host check.** The web app accepts only `https://*.stripe.com` URLs from the billing routes, stricter than the contract's `^https://` pattern. A future provider or a Stripe custom domain needs this list changed (`core/billing/billing-navigator.ts`).
- **How-to text.** With a provider the page shows `upgrade.how_to_get_pro_online` instead of `upgrade.how_to_get_pro`, because the latter says that online payment is not available yet; without a provider the original key is shown unchanged.
- **Already PRO.** A user whose PRO comes from a promo code or a grant sees no checkout button (the API would answer `409 plan_already_active`); subscribing ahead of the end of such a period is not possible from the web app.
- **Not verified:** a real Stripe test-mode round trip in a browser (the e2e uses stand-in pages and a mocked API); the screen-reader wording of the live regions with real assistive technology; the Linux CI fonts locally (checks ran on Windows Chromium; the layout checks measure overflow, so they cover wider fonts only when CI runs them).
- The `e2e/adversarial/**` billing cases are AGENT_REDTEAM's and are not part of this work.
