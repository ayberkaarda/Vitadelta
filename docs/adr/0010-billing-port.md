# ADR-0010: Billing port with promo-code and manual adapters; payment provider deferred

- Status: Accepted; the deferred payment provider is decided in ADR-0014 (Stripe, Phase 8)
- Date: 2026-11-24

## Context

PRO must be obtainable in v1, but integrating a payment provider requires a provider choice by the owner (iyzico for Türkiye, Stripe elsewhere), contracts and compliance work. The subscription model should not have to change when payments are added later.

## Decision

- Port `BillingProvider { ActivationResult activate(ActivationRequest) }` in package `billing`, with two adapters in v1:
  - `PromoCodeBillingProvider` behind `POST /me/subscription/promo { code }` (subscription `source = PROMO`);
  - `ManualGrantBillingProvider` behind the admin endpoint `POST /admin/users/{id}/subscription { planCode: "PRO", durationDays }` (subscription `source = MANUAL`).
- **Promo codes** are generated server-side by `POST /admin/promo-codes` (optional human prefix only): `<PREFIX>-` plus 16 characters of Crockford base32 from a CSPRNG (80 bits of entropy); lookups are case-insensitive. The generated code is returned once.
- **Redemption** requires a verified e-mail and is rate limited per IP (10 per hour) and per user (5 per hour). It runs in one transaction: lock the user row, insert `promo_redemption` (unique per code and user), then `UPDATE promo_code SET redeemed_count = redeemed_count + 1 WHERE code = ? AND redeemed_count < max_redemptions AND (expires_at IS NULL OR expires_at > now()) RETURNING ...`; zero rows roll the transaction back. PRO is then activated for `duration_days`.
- **No oracle.** Every failure (unknown, expired, exhausted, already redeemed) returns the same `400 promo_invalid` with the same body; the reason is only logged and counted in a metric.
- **Subscription changes are serialized** per user through the user-row lock: activation cancels the ACTIVE FREE subscription and inserts an ACTIVE PRO row with `current_period_end = now + duration`; an existing PRO period is extended. A daily expiry job downgrades expired PRO subscriptions to FREE (ADR-0011).
- After commit the `plan-activated` e-mail is handed to the asynchronous mail dispatcher and a `SubscriptionActivatedEvent` is published.
- **Payment provider deferred.** No checkout exists in v1; the upgrade page shows benefits, the price from `GET /plans` and the promo-code form. A payment adapter is optional Phase 8 work and needs the owner's provider choice.

## Consequences

- The PRO plan, its limits and its expiry are fully exercised in v1 without handling payments or card data.
- Adding a payment provider means adding an adapter and a checkout flow; the subscription model, entitlements and expiry logic stay unchanged.
- Concurrency tests (for example 50 parallel redemptions of a code with 10 redemptions yield exactly 10 successes) guard the atomic redemption.
- The PRO price is informational until checkout exists.

## Alternatives considered

- **Integrate a payment provider in v1.** Rejected: blocked on an owner decision and out of proportion to v1 scope.
- **Admin grants only.** Rejected: the owner needs a way to distribute PRO access at scale without manual work per user.
- **Distinct error codes for exhausted or already redeemed codes.** Rejected: they would let callers probe which codes exist.
