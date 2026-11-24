# Phase 3 report: identity, plans and entitlement engine

Date: 2026-11-24. Branch: `feat/phase-3-identity`.

## 1. Completed

- Migration `V2__identity_and_plans.sql`: users, refresh tokens, e-mail tokens, plans, subscriptions, promo codes and redemptions, anonymous identities, saved products, alert subscriptions, usage counters; plan seeds FREE (5 saved products, 3 active alerts, 30 notifications per month) and PRO (unlimited, 199.00 TRY per month).
- Shared foundation: validated configuration with fail-fast start-up (JWT secret, cookie secret, web base URL), one security filter chain with the route matrix, RFC 9457 problem responses, request id filter, trusted-proxy client address resolution, log redaction with e-mail masking, in-memory rate limiter, pagination and rounding helpers, advisory-lock job wrapper.
- Mail: `EmailSender` port with the SMTP adapter, asynchronous `AuthMailDispatcher`, five localized templates in Turkish and English (verify e-mail, already registered, reset password, plan activated, plan expired).
- Identity: register, verify e-mail, resend verification, login, refresh, logout, forgot and reset password, profile read and update, account deletion, session quota, admin user listing and status changes, admin bootstrap from the environment, daily housekeeping.
- Plans and entitlements: cached plan service, public plans endpoint, subscription lifecycle serialized per user, daily expiry job, the single entitlement decision point with atomic limit checks and the monthly notification cap.
- Billing: the billing port with the promo-code and manual-grant adapters, promo redemption, admin promo-code endpoints.
- Adversarial suite for everything delivered so far (80 tests).

## 2. Files by area

| Area | Files |
|---|---|
| Shared API files | `build.gradle.kts`, `application*.yml`, `config/**`, `common/{problem,web,logging,ratelimit,paging,numeric,jobs}/**` |
| Mail | `mail/**`, `resources/mail/{tr,en}/*` |
| Identity | `identity/**`, `db/migration/V2__identity_and_plans.sql` |
| Entitlement and billing | `entitlement/**`, `billing/**` |
| Adversarial tests | `src/test/java/com/vitadelta/api/adversarial/**` |
| Other | `infra/docker-compose.yml` (dev profile for the local stack), `.env.example`, `README.md`, `README.tr.md`, `contracts/public-api.openapi.yaml`, ADR-0005, ADR-0006, ADR-0010, ADR-0011 |

Shared-file changes requested by the mail, identity, entitlement and billing work and applied separately: the mail dispatch settings block, the data directory for the test JVM (weak-password list), route-matrix test updates for the new public routes, ordered problem extension members, problem bodies for rejected cross-origin requests, the documented identity retention settings, the scheduler switch comment, API security headers on early rejections, and the public test helper for contract schemas.

## 3. Tests and checks

| Command | Result |
|---|---|
| `./gradlew check --rerun-tasks` (services/api) | BUILD SUCCESSFUL; 844 tests, 0 failures; line coverage 95.65 % (gate 85 %) |
| Package coverage | identity 93 %, entitlement 99 %, billing 98 %, mail 100 % (lines) |
| Local stack smoke through nginx | register 202 and verification mail in Mailpit; login 200 with access token and refresh cookie; profile 200; admin route with a user token 403; anonymous session quota 200; plans 200 with FREE and PRO 199.00 TRY; promo redemption for an unverified user 403; foreign-origin login 403 |

Adversarial suite (80 tests, all green after the fixes):

| Case | Result |
|---|---|
| 1 JWT forgery (tampered signature, `alg: none`, expired, edited role, unknown key id) | PASS |
| 2 Admin routes as a normal user, unknown ids | PASS (the per-user id routes arrive with Phase 5) |
| 4 CSRF on cookie-scoped endpoints, login CSRF, foreign-origin preflight, alternative path spellings | PASS after fix |
| 5 Refresh reuse after the grace window, behaviour inside the window | PASS |
| 6 Rate limits per address, per account and per recipient; spoofed forwarding header | PASS |
| 7 Oversized bodies, security headers on the rejection | PASS after fix |
| 9 Injection payloads in free-text fields | PASS after fix |
| 10 Account deletion completeness | PASS |
| 12 Enumeration and timing | PASS |
| 13 Disabled account | PASS |
| 14 E-mail token abuse, tokens never logged | PASS |
| 15 Promo abuse, 50 parallel redemptions of a 10-use code give exactly 10 | PASS |
| 16 Limit races | PASS |
| 18 Request id and public health | PASS |
| 19 Bootstrap collision | PASS |

Cases 3, 8, 11 and 17 need the recommendation endpoints, the import pipeline, nginx in an end-to-end run or the web app and follow in their phases.

Defects found by the adversarial suite and by an independent security review; each got a failing regression test first, then the fix:

| Finding | Fix |
|---|---|
| Logout or reuse detection could miss a successor created by a concurrent rotation | rotation and every revocation lock the user row first |
| Login could issue a session from credentials changed by a concurrent reset or disable | the issuance transaction re-reads the user under the lock |
| Account deletion accepted a password replaced by a concurrent reset | the current hash is verified under the lock |
| The forgery guard compared the raw path, so a percent-encoded path skipped it | the guard is bound to the handler methods |
| Anonymous identity rows could be created without limit | 60 creations per hour per client address |
| Encrypted grace successors lived until the daily purge | cleared on the next rotation or login of the user |
| Parallel login attempts passed the account lockout check together | attempts are reserved atomically before verification |
| Back-off escalation reset when the failure window emptied | escalation is kept until a successful login or 24 quiet hours |
| Recipient mail limits used a token bucket | rolling-window counts |
| Concurrent profile updates lost a field | read-merge-write under the user lock |
| A NUL byte in the admin search caused a server error | control characters are rejected with 422 |
| Access tokens were accepted up to 60 seconds after expiry | zero clock skew |
| The 413 answer for a declared oversized body lacked the API security headers | the body limit runs after the header writer |
| Notification allowance read the plan without the user lock | plan read under the lock |
| Malformed promo codes answered 422 and were not rate limited | uniform 400 after the limits |

## 4. Deviations and assumptions

- `app_user.last_login_at` was added to support the purge of unverified accounts that never logged in.
- Extending PRO sets the period end to the later of the current end and now, plus the duration. Grant and promo durations are capped at 3650 days.
- The cookie secret must be at least 32 bytes and `VITADELTA_MAIL_FROM` is required at start-up.
- Actuator health and metrics listen on a separate internal port that the local stack does not publish.
- An oversized unauthenticated request to a protected route is answered 401 before 413.
- `GET /plans` returns native prices; when another display currency is requested the response says conversion is unavailable until the FX module exists.
- Per-address limits use a token bucket (bursts up to the capacity); per-recipient limits are rolling windows.
- The API reads the common password list from the mounted data directory at start-up.
- Text parts and subjects of e-mails use unescaped text-mode output because they are plain text; HTML parts use escaped output only.
- No alert templates or alert endpoints were added in this phase; they belong to Phase 5.

Accepted data deviations from Phase 1 (owner decision): the EFSA fluoride upper limit is not seeded, the IOM chloride upper limit is 3600 mg, presence floors are empty.

## 5. Unverified or skipped

- The timing comparison between unknown and wrong-password logins is measured in-process with medians; it is not a network-level measurement.
- OWASP Dependency-Check was not run (no NVD API key).

## 6. Decisions for the owner

- Whether `DELETE /me` should be rate limited.
- Whether an administrator may disable their own account or the last administrator.
- Whether subscription events are published inside the transaction or after commit (ADR-0010 says after commit).

Applied as decided: PRO 199.00 TRY per month; FREE 5 saved products, 3 active alerts, 30 notifications per month.

## 7. Proposed scope changes

None.

## 8. Branch, commits and contract changes

- Branch `feat/phase-3-identity`; small Conventional Commits, one logical change each.
- Contract changes in `contracts/public-api.openapi.yaml`:
  - `POST /me/subscription/promo`: `code` is a plain string; malformed values return the uniform `400 promo_invalid` and count against the limits (was `422`; no impact for valid codes).
  - `GET /session/quota`: new `429` with `Retry-After` (anonymous identity creation limit). Additive.
  - `GET /plans`: optional bearer token (it supplies the display-currency preference) and `401` for an invalid one; example for the response before FX exists (`fx.status = UNAVAILABLE`, native money). Additive.
  - `Email` and the admin user search `q`: control characters are rejected (pattern added). Tightening that matches behaviour.
  - `GrantSubscriptionRequest` and `CreatePromoCodeRequest`: `durationDays` maximum 3650; `expiresAt` must be in the future. Tightening that matches behaviour.
  - `413` and a new shared `415` response are listed on the Phase 3 operations with a body; documentation of the body size limit. Additive.

## 9. Next phase

Phase 4, core API: catalog and recommendation endpoints, effective price resolution, entitlement gate on the ranking query with result truncation, market profile, scoring client with resilience, caching, the admin import pipeline, the form registry table and stored upper-limit notes.

## 10. Awaiting approval

No force-push or rewrite of pushed history has happened. Merging requires a green CI run.
