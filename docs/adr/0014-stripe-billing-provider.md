# ADR-0014: Stripe as the optional payment provider, with hosted pages and verified webhooks

- Status: Accepted
- Date: 2026-11-25

## Context

ADR-0010 deferred payments: PRO was obtainable only through promo codes and manual grants. For Phase 8 the owner approved a payment provider and chose Stripe, in test mode. The subscription model (one ACTIVE row per user, changes serialized by the user-row lock, a daily expiry job) must stay as it is; entitlement stays server-side. Card data must never touch the API or the web app, and the integration must stay off unless it is configured.

## Decision

### Shape of the integration

- **Hosted pages only.** PRO is bought on Stripe Checkout (subscription mode, one unit of one recurring monthly price). Payment methods, invoices and cancellation are handled on the Stripe Billing Portal, so cancellation logic does not live in our code. The API only creates sessions and returns their URLs:
  - `POST /api/v1/billing/checkout-session` (bearer token; verified e-mail; `409 plan_already_active` when the ACTIVE plan is PRO or when a Stripe subscription of the user has not ended (a past-due one is settled in the portal, not by a second subscription); 10 per hour per user). Nothing is stored or locked per checkout session. Two sessions paid before the first webhook can still create two subscriptions; that is resolved afterwards by the webhook (surplus cancellation below).
  - `POST /api/v1/billing/portal-session` (bearer token; `409 no_billing_account` before the first completed checkout; 10 per hour per user).
  - Return URLs come only from `VITADELTA_WEB_BASE_URL`: `/upgrade?checkout=success`, `/upgrade?checkout=canceled`, `/account`. A return URL never changes a plan.
- **Webhooks are the only writer.** `POST /api/v1/billing/webhooks/stripe` applies `checkout.session.completed`, `invoice.paid`, `invoice.payment_failed`, `customer.subscription.updated` and `customer.subscription.deleted`. Other types are acknowledged and ignored.
- **Billing port.** A new request kind `ActivationRequest.ProviderPeriod(userId, externalRef, periodEnd)` is handled by the adapter `StripeBillingProvider`, which calls `SubscriptionService.applyProviderPeriod` under the user-row lock (source `PROVIDER`, `external_ref` = Stripe subscription id). An ended Stripe subscription calls `SubscriptionService.endProviderSubscription`, which downgrades exactly like the expiry job (row `CANCELED`, FREE row inserted, alert subscriptions beyond the FREE limit deactivated, `plan-expired` mail and `SubscriptionExpiredEvent`). First activation sends `plan-activated` through the existing asynchronous mail dispatcher; renewals send no mail.
- **Configuration.** `VITADELTA_BILLING_PROVIDER` = `none` (default, also when empty) or `stripe`. With `stripe`, `VITADELTA_STRIPE_SECRET_KEY`, `VITADELTA_STRIPE_WEBHOOK_SECRET` and `VITADELTA_STRIPE_PRICE_ID` are required and checked before the database is contacted. Only test keys (`sk_test_`, `rk_test_`) are accepted unless `VITADELTA_STRIPE_ALLOW_LIVE=true`. There are no default secrets; `toString()` of the settings hides them. Without a provider every billing route answers `503 billing_unavailable` and `GET /plans` reports `checkoutAvailable: false`.

### Webhook security

- **Signature first.** The raw body is bound as bytes and verified before it is parsed, as Stripe documents for manual verification: the header `Stripe-Signature` must occur exactly once; its entries are `key=value`; `t` occurs exactly once (Unix seconds); every `v1` entry (up to 8, one per active secret during a rotation) is compared in constant time (`MessageDigest.isEqual`) with HMAC-SHA256 keyed with the whole `whsec_...` secret over `<t>.<raw body>`; `v0` and the debug entries (`secret`, `destination`, `payload`) are never used. The timestamp must lie within 5 minutes of the server clock in both directions (Stripe's libraries check only the past). Header values over 2048 characters are refused.
- **Uniform rejection.** Every failure (missing, repeated or malformed header, no `v1`, mismatch, timestamp window, a body that is not an event) is the same `400` `about:blank` body. The reason is logged and counted (`vitadelta.billing.webhook.rejected{reason}`), never returned.
- **No user credentials, own limits.** The route is public in the security chain (no bearer token, no cookie, no CSRF header; the API has no CSRF tokens at all, ADR-0006), is exempt from the other rate-limit policies, is capped at 300 requests per minute per client address before the signature check, and has its own body limit of 256 kB (the general limit is 64 kB).

### Applying events

- **Read, then apply.** The event payload is only used to find the subscription (for invoices `parent.subscription_details.subscription`, falling back to the pre-2025-03-31 `subscription` field). The API then reads the subscription from Stripe (`GET /v1/subscriptions/{id}?expand[]=latest_invoice`) outside any transaction and applies that current state. A Stripe failure is `503`, so Stripe retries the delivery.
- **Paid periods only.** A period is granted when the status is `active` or `trialing` and the latest invoice is `paid` (or absent). Stripe advances `current_period_end` when the renewal invoice is created, before it is paid; an open or failed renewal therefore grants nothing, and the already paid period stays until it ends (then the expiry job downgrades). `past_due`, `unpaid`, `incomplete` and `paused` change nothing. `canceled` and `incomplete_expired` end the subscription.
- **Forward only.** `billing_subscription` keeps the merged state per Stripe subscription: `current_period_end` is the maximum ever seen, `paid_through` the latest period end reported as paid (never raised after the end), and `ended_at`, once set, stays set. A late `invoice.paid` read before a cancellation can therefore not revive an ended subscription, and an older period can not shorten a newer one.
- **Entitlement per subscription.** After each event the user's ACTIVE row is reconciled with all of the user's provider subscriptions: PRO follows the live subscription paid furthest ahead (`paid_through`), under that subscription's own `external_ref`. An event of one subscription only extends that subscription's own row; when the row's subscription ends while another live subscription is paid, that one takes over (`ProviderChange.REPLACED`, no mail); the user is downgraded only when no live subscription pays for PRO.
- **Surplus cancellation.** When a user has more than one live provider subscription after an event, the one that holds the entitlement is kept and the others are canceled at Stripe (`DELETE /v1/subscriptions/{id}`) after the webhook transaction, marked ended, and only then is the event recorded. A failed cancellation is counted (`vitadelta.billing.surplus.cancel_failed`) and answered `503` without recording the event, so Stripe's redelivery reapplies the event (idempotent) and retries the cancellation. Each cancellation is counted (`vitadelta.billing.surplus.canceled`) and logged with a line telling the operator to refund its payment; refunds are not automatic. Without a provider entitlement (every subscription past due, for example) nothing is canceled and the overlap is only logged. Overlaps are counted in `vitadelta.billing.duplicate_subscription`.
- **Exactly once.** `billing_webhook_event (provider, event_id)` records applied events. Repeated deliveries are answered from it without calling Stripe; the record is inserted in the same transaction that changes the subscription, after the user-row lock, so parallel deliveries of one event apply once.
- **User mapping.** At checkout the API sets the session's `client_reference_id`, its `metadata.user_id` and the subscription's `metadata.user_id` to the user id (subscription metadata cannot be edited by customers). A subscription without that metadata, with another price, or whose metadata disagrees with the session's `client_reference_id` is ignored. E-mail addresses are never sent to or read from Stripe for mapping. A Stripe customer belongs to one user (`billing_customer`, unique per customer); a customer or subscription already mapped to another user is refused and recorded as `customer_conflict`. The portal and the next checkout use the customer of the subscription that holds the entitlement; without a provider entitlement a live subscription sets the customer only when the user has none, and an ended subscription never sets or replaces it.
- **Mode.** Events and subscriptions whose `livemode` differs from the key's mode are ignored.

### Account deletion

`UserDeletingEvent` collects the user's Stripe subscriptions that have not ended; after the deletion commits they are canceled at Stripe (`DELETE /v1/subscriptions/{id}`). The deletion never waits for Stripe; a failed cancellation is logged with the subscription id and counted (`vitadelta.billing.cancel_on_delete.failed`) for the operator. The provider rows are removed by the deletion cascade.

A subscription the deletion cannot see, because it reaches the API only after the account is gone (a hosted checkout paid after the deletion, or a deletion that commits while a webhook is reading Stripe), is caught by the webhook: when the subscription's user no longer exists and the subscription has not ended, it is canceled at Stripe after the webhook transaction and only then is the event recorded as `user_missing`. A failed cancellation is counted in `vitadelta.billing.cancel_on_delete.failed` and answered `503`, so Stripe's delivery retry retries the cancellation.

### Plain REST instead of the SDK

The adapter uses the JDK HTTP client (Spring `RestClient`, no redirects, connect 5 s, read 20 s, responses up to 1 MiB, strict JSON with duplicate-key detection) and pins `Stripe-Version: 2026-11-18.endive` on every request. Verified on 2026-11-25:

- `stripe-java` 34.2.0 (Maven Central, published 2026-11-19) is pinned to `2026-11-18.endive`, targets Java 8 bytecode (it would run on JDK 25) and depends on Gson 2.10.1. It was not used: four calls and the signature check do not justify a large SDK and a second JSON library on the runtime classpath, and the plain client is tested against a stub server like the FX adapter.
- `2026-11-18.endive` is the latest GA API version in the Stripe changelog. The Endive breaking changes (first version `2026-09-30.endive`) touch Checkout only through the removed `payment_method_types` parameter, which is not sent; Billing Portal sessions, subscriptions (`items.data[].current_period_end` since Basil, `latest_invoice`, `metadata`, `ended_at`, statuses) and invoices (`parent.subscription_details.subscription`) are unchanged.
- The signature scheme above is the one documented under "Verify webhook signatures manually" and matches `com.stripe.net.Webhook.Signature` in `stripe-java` 34.2.0 (key = secret string as UTF-8, lower-case hex, any `v1` match), except that a timestamp in the future is also rejected here.

### Data

Migration V7 (ADR-0013 numbering: the next free number, delivered after V6) adds `billing_customer`, `billing_subscription`, `billing_webhook_event` and a partial index on `subscription.external_ref`; V8 adds `billing_subscription.paid_through`. V8 sets no value for existing rows: a stored `active` status and period end are no proof of a paid invoice. Until the next webhook read-back of such a subscription (which sets `paid_through` only from a paid latest invoice), its PRO row keeps its period end and is neither extended nor ended; `NULL` counts as unpaid. `billing_webhook_event` holds no personal data and is kept indefinitely (a few rows per paying user per month).

## Consequences

- No card data reaches the API or the web app; PCI scope stays with Stripe's hosted pages.
- The subscription model, entitlement checks and the expiry job are unchanged; a provider subscription is one more source of ACTIVE PRO rows.
- Each handled event costs one Stripe API call. Ordering problems are solved by reading current state and by the forward-only merge instead of by event timestamps (Stripe does not guarantee order and `created` has one-second resolution).
- Known limits, documented in the runbook: a promo period redeemed on top of a provider period is lost when that provider subscription ends early; a surplus subscription canceled by the webhook has been charged once; the operator refunds it from the log line; a user whose deletion could not cancel the Stripe subscription is listed in the logs.
- The web checkout and portal buttons are a separate deliverable that only renders `checkoutAvailable` and follows the returned URL.

## Alternatives considered

- **Stripe Java SDK.** Rejected for now (see above); the adapter can switch to it without changing the port.
- **Trusting the event payload.** Rejected: payload versions follow the endpoint's API version, events arrive out of order and can be stale; reading the subscription gives one shape and the current state.
- **Mapping customers by e-mail.** Rejected: e-mail on the Stripe side is editable by the customer and is not proof of account ownership.
- **Cancellation endpoint in our API.** Rejected: the Billing Portal already handles cancellation, proration and payment-method changes.
- **iyzico.** The owner chose Stripe for Phase 8.
