# Phase 5 report: saved products, alerts, notifications and exchange rates

Date: 2026-11-25. Branch: `feat/phase-5-alerts-fx`.

## 1. Completed

- Exchange rates: provider port with the Frankfurter v2 adapter (ECB reference rates), stored daily rates, refresh at start-up and every six hours under a job lock, cached lookup with staleness, display-currency conversion on every money-bearing response, endpoints for currencies, rates and an admin refresh.
- Saved products and alert subscriptions with plan limits checked atomically, three triggers (price drop against a per-subscription baseline, back in stock, price change).
- Offer-change events written inside the import and seed transactions, one per product and country whose effective price state changed.
- Transactional outbox processor with claim and lease, per product-and-country ordering, exponential back-off and dead letters; alert evaluator with delivery de-duplication and the monthly notification cap; alert e-mails sent outside transactions with a preferences link and a `List-Unsubscribe` header.
- Notification inbox endpoints, plan-activated and plan-expired notifications, account-deletion cleanup, outbox housekeeping.
- Admin health with component status and job last-run times, outbox statistics, public overall health status.
- Engine parameters (verdict thresholds, utility ceiling, beta) on the default-weights endpoint for the web app.
- Adversarial suite extended to saved products, alerts, notifications, the outbox, exchange rates and health (173 tests in total).

## 2. Files by area

| Area | Files |
|---|---|
| Exchange rates | `fx/**`, `db/migration/V4__fx_rates.sql` |
| Alerts and notifications | `alerts/**`, `db/migration/V3__notifications_and_outbox.sql`, `db/migration/V6__alert_ordering.sql`, templates `alert-*` and `notification-cap-reached` under `resources/mail/{tr,en}` |
| API core and shared files | `health/**`, `common/{jobs,health,watch}/**`, `config/**`, `application.yml`, `build.gradle.kts`, `catalog/{seed,web}/**`, `imports/**`, `recommendation/**`, `entitlement/**` (money presentation), `mail/**` (message headers) |
| Adversarial tests | `src/test/java/com/vitadelta/api/adversarial/**` (8 new classes, 2 updated) |
| Other | `contracts/public-api.openapi.yaml`, `scripts/dev-up.sh`, `scripts/seed.sh`, `.env.example`, `README.md`, `README.tr.md`, ADR-0005, ADR-0008, ADR-0009, ADR-0011, ADR-0013 |

Shared-file changes requested by the exchange-rate and alerts work and applied by the API core: the `vitadelta.fx` and `vitadelta.alerts` settings blocks, test contexts kept off the network, typed exchange-rate metadata, a job-run registry fed by the job lock, extension points for outbox health and for saved and alert state on product responses, optional allow-listed headers on e-mail messages, the committed catalog version passed to offer-change listeners, and the list of implemented operations in the OpenAPI comparison test.

## 3. Tests and checks

| Command | Result |
|---|---|
| `./gradlew check --rerun-tasks` (services/api) | BUILD SUCCESSFUL; 1301 tests, 0 failures; line coverage 96.3 % (gate 85 %) |
| Package coverage (lines) | alerts 97.9 %, fx 95.1 % |
| Live provider check | the adapter fetched and parsed real rates for base TRY (EUR, GBP, USD) |
| Local stack through nginx | admin refresh stored 12 rates; a TR query with display currency USD returned converted money with `fx.status = OK`, the rate with 8 decimals and the same order as in TRY; register, verify, login, save a product, create a price-drop alert, import the price-drop fixture: one unread notification (900.00 to 846.00) and the alert e-mail in Mailpit with the preferences link and `List-Unsubscribe`; outbox statistics all zero afterwards |
| Seed twice | the second run changes no row, leaves the catalog version unchanged and writes no event |

Adversarial suite (173 tests, all green after the fixes):

| Area | Result |
|---|---|
| Ownership: every id route with another user's, random, malformed and deleted ids gives the same 404; lists and counters never include other users' rows | PASS |
| Limits under parallel requests, implicit saved product, re-activation, downgrade, threshold bounds, unverified users | PASS |
| Notification integrity: cap farming, single cap notice per month, replayed imports, ineligible recipients, earlier changes, out-of-order retries | PASS after fix |
| Alert e-mail: script payloads escaped, no header injection, links only from the configured base URL | PASS |
| Account deletion completeness and races with mail dispatch | PASS |
| Outbox: poison events dead-lettered without blocking others, SMTP outage without duplicate mail | PASS |
| Exchange-rate provider boundary (29 hostile answers), hostile currency values, order invariance with extreme rates, refresh authorisation | PASS after fix |
| Health, operations endpoints and API headers | PASS after fix |

Defects found by the adversarial suite and by an independent review; each has a regression test:

| Finding | Fix |
|---|---|
| An alert created after a price change, but before the outbox ran, fired for that earlier change | each subscription stores a catalog-version boundary; older events are ignored |
| A retried older event could be processed after a newer one for the same product and announce a stale price | events of one product and country are claimed strictly in order; a dead letter releases the pair |
| E-mail completion and account deletion took locks in opposite orders | one lock order in both |
| Very small rates were written in scientific notation | decimals are written in plain notation everywhere |
| A converted amount could be shown as 0.0000 | the whole response stays in the native currency with `fx.status = UNAVAILABLE` |
| Out-of-range engine parameters from the scoring service were republished | parameters are validated; otherwise `engine` is null |
| A configured currency the provider does not publish made every refresh fail | the refresh retries without it and warns |

Not changed, by design: the exchange-rate lookup cache is per instance (single API instance, ADR-0011).

## 4. Deviations and assumptions

- A seed run that changes no catalog row no longer bumps the catalog version, and a seed that changes offers writes offer-change events like an import. This settles a question left open in Phase 4.
- Migration V6 adds ordering columns to the outbox and the boundary version to subscriptions; migrations still run out of order with checksum validation (ADR-0013).
- A repeated alert creation returns the existing subscription; re-activation re-creates a missing saved-product row and counts toward the limit.
- Public `GET /health` returns the overall status of the admin health view (`UP`, `DEGRADED` or `DOWN`), cached for 10 seconds; `scripts/dev-up.sh` accepts `DEGRADED` as healthy.
- `POST /admin/fx/refresh` returns the upserted row count and the bases that failed, and answers 409 while a refresh is running.
- When a converted amount is not representable, `meta.fx` keeps the stored rate and date while the status is `UNAVAILABLE`.
- Notification payloads are snapshots with native money.
- Beta has no upper bound in the engine-parameter validation because the scoring configuration sets none.
- Exchange rates are indicative reference rates; the provider publishes no rate on weekends and the last stored rate is used.

Accepted data deviations from Phase 1 (owner decision): the EFSA fluoride upper limit is not seeded, the IOM chloride upper limit is 3600 mg, presence floors are empty.

## 5. Unverified or skipped

- The remaining differences between the served OpenAPI document and the contract are limited to identity and billing operations and one multipart marker; they are listed in the comparison test.
- OWASP Dependency-Check was not run (no NVD API key).

## 6. Decisions for the owner

- Whether product detail should expose the reference amount and upper limit per nutrient (the web design has columns for them; the contract has no fields).
- Whether a base currency the provider does not publish should be dropped from the refresh instead of marking the refresh as failed.
- Whether the OpenAPI document should stay public.

## 7. Proposed scope changes

None.

## 8. Branch, commits and contract changes

- Branch `feat/phase-5-alerts-fx`; small Conventional Commits, one logical change each.
- Contract changes in `contracts/public-api.openapi.yaml`:
  - `GET /weights/defaults`: nullable `engine` object (beta, utility ceiling, verdict thresholds). Additive.
  - notifications: payloads typed per notification type; alert payloads are snapshots with native currency.
  - saved products and alerts: `provenance` on items; `thresholdPercent` 0.01 to 99.99 with two decimals and `threshold_not_allowed` for other triggers; check order, idempotency and same-404 rules written out; `saved` and `alerts` on ranked items and product detail are real for signed-in callers.
  - exchange rates: lookup, staleness and `UNAVAILABLE` rules; source fixed to `FRANKFURTER`; refresh response with `failedBases` and 409; the not-representable case.
  - admin: health rules per component and overall, job outcomes, `oldestPendingAt` in outbox statistics; public health returns the overall status.
  - `DefaultCategoryWeights` remodelled without changing valid instances.
  All are additive or describe implemented behaviour; no released client exists yet.

## 9. Next phase

Phase 6: the web application.

## 10. Awaiting approval

No force-push or rewrite of pushed history has happened. Merging requires a green CI run.
