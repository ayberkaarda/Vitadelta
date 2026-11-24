# ADR-0007: Entitlements: data-driven plans, server-side enforcement, anonymous truncation

- Status: Accepted
- Date: 2026-11-24

## Context

Vitadelta offers an anonymous trial, a FREE plan and a PRO plan. Limits differ per principal (trial queries, visible results, saved products, active alerts, notifications per month). Limits will be tuned over time, must hold under concurrent requests, and must not be bypassable by a modified client.

## Decision

- **Plans are data.** Limits live in the `plan` table (`NULL` means unlimited) and are read through `PlanService` with a 60-second cache. Seeded values: FREE = 5 saved products, 3 active alerts, 30 notifications per calendar month (UTC); PRO = unlimited. The PRO list price defaults to 199.00 TRY per month and is informational in v1.
- **Single decision point.** `EntitlementService` decides every limited action; controllers never inline limit logic, and the web app never decides entitlements locally. Plans are always read from the database, never from token claims (ADR-0006).
- **Anonymous trial.** `VITADELTA_ANON_QUERY_LIMIT` = 3 queries, reserved by an atomic conditional `UPDATE ... WHERE queries_used < ?` in a short transaction and refunded on a scoring failure (ADR-0003). Zero rows updated means `403 quota_exceeded` with `{ limit, used, action: "SIGN_UP" }`.
- **Truncation instead of blurring.** Anonymous results show the top `VITADELTA_ANON_VISIBLE_RESULTS` = 3 items of the full ranking regardless of `page`. Locked items are **not sent in any form**; the response carries `meta.visibility = { shown, total, locked, reason: "SIGN_UP_REQUIRED" }`, and `page > 1` returns an empty `items` array with the same metadata. Users get full paged results with `locked: 0`.
- **Atomic limit checks.** Saving a product, creating an alert and activating an alert run in one transaction that first locks the user's row (`SELECT ... FOR UPDATE`), then counts and writes. Exceeding a limit returns `403 entitlement_exceeded` with `{ limit, used, plan, upgradeTo }`. Every `quota_exceeded` and `entitlement_exceeded` body includes `upgradeHint`.
- **Notification cap.** FREE users get at most 30 notifications per month through an atomic counter; reaching the cap creates one `NOTIFICATION_CAP_REACHED` notice per month that is not counted.
- **Downgrade keeps data.** When PRO expires, nothing is deleted; excess saved products remain, no new ones can be added, and alert subscriptions beyond the limit are deactivated starting from the newest.
- **Accepted exposure.** Compare and product endpoints are open to anonymous users without quota, limited only by per-IP rate limits; the trial quota protects the ranking, not the catalog.

## Consequences

- Limits change by updating data, without a release; tests assert the seeded values.
- A modified client cannot see locked results, because they never leave the server.
- Concurrency tests (for example 10 parallel saves for a FREE user with 4 saved products yield exactly 1 success) are required and part of the API test suite.
- The anonymous quota is best effort: clearing cookies resets it (ADR-0006).

## Alternatives considered

- **Blurring or obfuscating locked results in the client.** Rejected: the data would still be delivered and could be read from the network response.
- **Plan in the JWT.** Rejected: plan changes would take effect only after token expiry.
- **Hard-coded limits in code or configuration.** Rejected: limits are product data and must be visible and changeable per plan.
- **Counting with read-then-write without a lock.** Rejected: parallel requests could exceed limits.
