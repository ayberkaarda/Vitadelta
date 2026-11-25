# Phase 6 report: web application

Date: 2026-11-25. Branch: `feat/phase-6-web`.

## 1. Completed

- Design system in code: tokens for light and dark themes, base styles, shared components (score and delta bars as hand-written SVG, flag badges, verdict badge, money display with native amount and rate tooltip, provenance marker, locked rows, quota chip, notices, form fields, drawer, confirm dialog, pagination, skeleton and empty states, disclaimer).
- Typed API layer: types generated from the public contract by an isolated tool package, a hand-written client with one typed method per operation, problem responses mapped to a typed error.
- Session core: access token in memory only, silent refresh once per 401 with single flight inside a tab and across tabs, session generation that invalidates late responses and user-scoped state, return-URL allow-list, guards.
- Pages: query builder, ranked results with breakdown drawer and locked rows for visitors, comparison with verdict card and delta table, product detail, register, verify, login, forgot and reset password, account settings with usage meters, saved products, alerts, notification inbox, upgrade, admin imports, promo codes and users, privacy notice, not-found.
- Shell: country, display currency with rate date and stale marker, language toggle, quota chip, notification bell, user menu, footer with privacy link and disclaimer.
- Turkish and English translations loaded per feature; shareable URLs for results and comparisons.
- Headless end-to-end suite against the running stack and an adversarial end-to-end suite.

## 2. Files by area

| Area | Files |
|---|---|
| Tooling | `apps/web/package.json`, `angular.json`, `tsconfig*.json`, `eslint.config.js`, `tools/openapi/**`, `scripts/**`, `playwright.config.ts` |
| Styles | `apps/web/src/styles/**` |
| Core | `apps/web/src/app/core/{api,auth,security,state,format,i18n}/**`, `app.start.ts`, `app.config.ts`, `app.routes.ts` |
| Shared and shell | `apps/web/src/app/shared/**`, `apps/web/src/app/shell/**` |
| Features | `apps/web/src/app/features/{query,results,compare,product,auth,account,upgrade,admin,privacy}/**`, `apps/web/public/i18n/**` |
| Tests | unit and component specs next to the code, `apps/web/src/app/testing/**`, `apps/web/e2e/**`, `apps/web/e2e/adversarial/**` |
| Other | `infra/nginx/**` (asset 404s, immutable caching, routing check script), `scripts/test-all.sh`, `.dockerignore`, `.github/dependabot.yml`, `README.md`, `README.tr.md`, `apps/web/README.md`, ADR-0005 |

## 3. Tests and checks

| Command | Result |
|---|---|
| `npm run api:check` | generated types match the contract |
| `npm run lint` | passes (eslint, prettier, type checks of both end-to-end suites, 70 templates without literal text) |
| `npm test` | 62 files, 668 tests, line coverage 95.3 % (gate 70 %) |
| `npm run build` | passes; initial bundle 117 kB gzipped (budget 380 kB, enforced by the build), every feature lazy, no source maps |
| `bash infra/nginx/check-routing.sh` | 25 checks pass |
| `npm run e2e` on a fresh stack | 26 passed, repeated on fresh stacks |
| `npm run e2e:adversarial` on a fresh stack | 51 passed, repeated on fresh stacks |
| `RUN_E2E=1 scripts/test-all.sh` | RESULT: OK |

End-to-end scenarios covered: visitor query with three visible results, locked rows without product data and the quota chip counting down to the exhausted state; register, verification link from the real e-mail, login, full paged results; breakdown drawer; comparison of the golden pair with the indicative note and disclaimer; display currency switch; save and price-drop alert; admin import through the UI leading to an unread notification; limit notices; promo code created by the admin and redeemed by the user; language switch; session surviving reload; logout; password reset through the mail link; account deletion; privacy page; keyboard-only run; accessibility scans with no serious or critical violation; no console errors or policy violations; 375 px smoke.

Adversarial suite (51 tests, all green after the fixes):

| Area | Result |
|---|---|
| Stored script payloads in product, brand, retailer and notification text on every page; offer links | PASS after fix |
| Catalog text inside translated strings | PASS after fix |
| Token handling: storage, cookies, address bar, history, referrer, request URLs, back navigation after logout, two users in two tabs | PASS after fix |
| Open redirect through the return URL (19 variants) | PASS |
| Cross-origin requests, response reading and framing | PASS |
| Locked data never present in DOM or responses; admin routes as a normal user; hostile URL state | PASS |
| Security headers, script policy, caching, API paths never answered by the app shell, no source maps, no secrets in the bundle | PASS after fix |
| API outage, rate limit and quota notices | PASS |

Defects found by the adversarial suite and by an independent review; each has a regression test:

| Finding | Fix |
|---|---|
| A request that got a late 401 could be retried with another account's token after an account switch in another tab | requests are bound to a session generation and never retried across a change |
| Late refresh or profile responses could restore a cleared session | stale responses are discarded |
| Results, saved products, inbox and admin data stayed visible after a session change in another tab | user-scoped state is cleared and mounted pages are re-authorised |
| The admin guard admitted before a successful profile request | it requires one for the current session |
| Cross-tab refresh could run twice | explicit completion handshake |
| Offer URLs were not restricted to http and https | other schemes render as text |
| URL-restored query state bypassed ranges and registries | validated and normalised before use |
| Re-enabling an existing inactive alert reported success without activating it | the subscription is activated first |
| Import job polling and history outlived the admin session | bound to the session |
| Out-of-order responses could overwrite a newer list | latest request wins |
| Missing assets were answered with the app shell | 404 for assets and translation files |
| Import errors showed server text | translated text per error code |
| Catalog text containing interpolation markers was re-interpreted and could freeze the page | single-pass literal parameter substitution |
| E-mail tokens stayed in the address bar during start-up requests | removed before the first request |
| Fingerprinted assets had no long-lived caching | immutable caching |
| A login page stayed open after another tab signed in | it follows the checked return URL |

## 4. Deviations and assumptions

- The Phase 0 health panel was removed; the design has no status indicator.
- The style policy needs `'unsafe-inline'` for styles because the framework inserts component styles as style elements; the script policy stays `'self'`.
- Product detail shows no reference amount or upper limit per nutrient because the contract has no fields for them.
- Money is formatted by the browser's locale rules, so the currency code can precede the amount.
- "Recent jobs" on the admin imports page lists the jobs started in the current session; the API has no job list.
- The delete-account confirmation is a dialog with a focus trap.
- An anonymous page load makes one refresh request that is answered 401 by design; the suites accept that console entry.
- The end-to-end suites are opt-in in `scripts/test-all.sh` and are not part of CI yet.
- The privacy notice text is a complete draft in both languages.

## 5. Unverified or skipped

- Screen-reader output, touch gestures, clipboard prompts and drag-and-drop were not checked interactively; accessibility is covered by automated scans, keyboard tests and unit tests.
- Cross-tab refresh timing is covered by unit tests with fake locks and channels, and by one two-tab end-to-end case whose sign-out branch depends on timing.

## 6. Decisions for the owner

- Legal review of the privacy notice text and its "last updated" date.
- Whether product detail should expose reference amounts and upper limits per nutrient.
- Whether an anonymous refresh request without a cookie should answer 204 instead of 401.
- Whether the end-to-end suites should run in CI.

## 7. Proposed scope changes

None.

## 8. Branch, commits and contract changes

- Branch `feat/phase-6-web`; small Conventional Commits, one logical change each.
- No contract change in this phase.

## 9. Next phase

Phase 7: hardening, observability, backup and restore, release documentation.

## 10. Awaiting approval

No force-push or rewrite of pushed history has happened. Merging requires a green CI run.
