# Phase 7 report: hardening

Date: 2026-11-25. Branch: `feat/phase-7-hardening`.

## 1. Completed

Against the Phase 7 deliverables of the project specification (§13.9):

- Full-stack compose verified from a clean copy of the tracked files (fresh `.env`, seed, test script) and from a fresh stack (`dev-down.sh --volumes`, `dev-up.sh --bootstrap-admin`, `seed.sh`).
- End-to-end flows completed with mail helpers and accessibility scans on results, comparison, product detail and the admin imports page; a long-text layout suite; a screenshot run that writes the six README images.
- End-to-end and security jobs added to the CI workflow; actions pinned by commit, credentials masked and kept out of uploaded artifacts.
- Observability: request context in every log line (request id, principal type, country, catalog version, job name and run id), meters for rate limits, failed logins, rejected refreshes, entitlement rejections, job runs and skipped jobs; the metric and log catalogue is in the [runbook](../runbook.md) (sections 13 and 14).
- Security layer (§15): header and TLS posture asserted by the routing check, `scripts/security-scan.sh` with self-tests, leakage guarantees for error bodies and logs at every level, encrypted backup and isolated restore with one restore drill, the adversarial suite completed on the API and the web side.
- Owner decisions from Phase 6 implemented contract first: product detail shows the country reference and the applicable upper limit per nutrient; a refresh request without a session cookie answers 204.
- Exchange rates: a base currency the provider does not publish is reported as unsupported instead of failing the run.
- Documentation: [runbook](../runbook.md), [architecture overview](../architecture.md), both READMEs restructured with screenshots, version pins recorded in [ADR-0005](../adr/0005-version-pins.md).
- Infrastructure hardening after an independent review: nginx runs unprivileged, PostgreSQL and SMTP ports are no longer published, external images are pinned by digest, secrets are masked in the access log.

## 2. Files by area

142 files changed, 11622 insertions, 597 deletions against the default branch.

| Area | Files |
|---|---|
| Contracts | `contracts/public-api.openapi.yaml` |
| API | `services/api/**`: `catalog` (nutrient facts), `identity` (refresh 204), `fx` (unsupported bases), `common` (log context, redaction, body-safe JSON converter, problem handling), `billing`, `entitlement`, `recommendation`, `config`, `application.yml`, build file (dependency vulnerability plugin), `Dockerfile` |
| API tests | unit and integration tests next to the packages; `services/api/src/test/java/com/vitadelta/api/adversarial/**` (four new classes) |
| Scoring | `services/scoring/Dockerfile` (digest pins only) |
| Web | `apps/web/src/app/features/{product,admin,compare}/**`, `core/{api,auth,format}/**`, `shared/dialog/**`, `src/styles/**`, `public/i18n/**`, `playwright.config.ts`, `README.md` |
| Web tests | specs next to the code, `apps/web/e2e/**` (flows, `06-long-text.e2e.ts`, `screenshots.ts`), `apps/web/e2e/adversarial/**` (`09-product-facts.adversarial.ts`, extended header and origin cases) |
| Infrastructure | `infra/docker-compose.yml`, `infra/docker-compose.restore.yml`, `infra/nginx/**` |
| Scripts | `scripts/security-scan.sh`, `scripts/security/**`, `scripts/backup.sh`, `scripts/restore.sh`, `scripts/ci/**`, `scripts/dev-up.sh`, `scripts/test-all.sh` |
| CI and root | `.github/workflows/ci.yml`, `.github/dependabot.yml`, `.gitleaks.toml`, `.env.example`, `README.md`, `README.tr.md` |
| Docs | `docs/runbook.md`, `docs/architecture.md`, `docs/adr/**`, `docs/screenshots/*.png` |

## 3. Tests and checks

Final gate run on 2026-11-25 on a fresh stack (`dev-down.sh --volumes`, `dev-up.sh --bootstrap-admin`, `seed.sh`, all exit 0), then `RUN_E2E=1 scripts/test-all.sh`. The script ended with `RESULT: FAILED (1 step(s) failed or did not run)`: every step passed except the scoring test step. The gate is therefore not fully green in a single uninterrupted pass; details follow the table.

| Step | Result |
|---|---|
| Contract lint (public API, scoring API, import schema) | PASS, 3 of 3 |
| Scoring `ruff check`, `ruff format --check`, `mypy` | PASS |
| Scoring `pytest` | FAIL in the gate run: 368 passed, 1 failed (performance budget, see below) |
| Script tests | 5 and 15 passed |
| Security scan self-tests | 18 passed, 0 failed, 0 skipped |
| `scripts/security-scan.sh` | PASS with one check skipped: the dependency vulnerability check needs an NVD API key |
| API `./gradlew check` | PASS, but every task was up to date: Gradle reused the results of the last full run on unchanged inputs (1399 tests, 0 failures, line coverage 96.4 %, adversarial package 194 of 194) |
| Web `npm ci`, `npm run api:check`, `npm run lint` | PASS (70 templates without literal text) |
| Web `npm test` | 64 files, 693 tests |
| Web `npm run build` | PASS |
| `infra/nginx/check-routing.sh` | "All routing checks passed." |
| `npm run e2e` | 48 passed |
| `npm run e2e:adversarial` (after an API restart) | 60 passed |
| `npm run e2e:screenshots` | 5 passed; six images of 1280 px width written to `docs/screenshots/` |

The failing step: `tests/perf/test_score_perf.py::test_score_p95_within_budget` measured a p95 of 218.5 ms against the 200 ms budget while the machine was at about 90 % CPU from unrelated work. In the first attempt of the script minutes earlier the same suite passed with 369 tests. The suite was then run three times on the idle machine after the stack was stopped: 369 passed each time, with p95 values of 174.7, 163.1 and 180.6 ms. No scoring source changed in this phase besides the digest pins in the scoring `Dockerfile`.

Restore drill (performed once, transcript in runbook section 11.6): encrypted backup of 139363 bytes, volumes deleted, restore into the isolated stack with a green read-only smoke (110 products, 211 offers, 1 account, golden comparison 200 with verdict `NOT_WORTH_IT`; health `DEGRADED` because the isolated stack has no exchange rate), login 200 in the isolated stack; the live recovery procedure (section 11.5) was also executed, login 200 through nginx. A restore run with a hostile environment (`COMPOSE_PROJECT_NAME` and `COMPOSE_FILE` pointing at the live project) left the five live containers untouched.

Clean copy: tracked and untracked-not-ignored files without the Git directory, own compose project; `dev-up.sh` created a fresh `.env`, seed succeeded, `RUN_SECURITY_SCAN=0 scripts/test-all.sh` ended with `RESULT: OK`. The security scan needs a Git checkout by design.

Static checks of the new files: actionlint 1.7.12 clean on the workflow; shellcheck 0.11.0 at warning level clean on all scripts.

Adversarial suite, API side (§15.7): 194 tests in 33 classes (173 in 29 before), three package runs with identical results, no flaky test.

| # | Case | Tests | Result |
|---|---|---|---|
| 1 | JWT forgery | `JwtForgeryAdversarialTest` (12) | PASS |
| 2 | IDOR | `WatchlistIdorAdversarialTest` (8), access-control and availability cases | PASS |
| 3 | Entitlement bypass | `EntitlementBypassAdversarialTest` (11); web 06 | PASS |
| 4 | CSRF, CORS, login CSRF | `CsrfCookieAdversarialTest` (8), cookie-less refresh guard order; web 05 | PASS |
| 5 | Refresh reuse | `SessionAbuseAdversarialTest`, `RefreshWithoutSessionAdversarialTest`; web 03 | PASS |
| 6 | Rate limits | `RateLimitAdversarialTest` (6), untrusted peer, mail, catalog and cookie-less refresh limits; forwarded-address overwrite through nginx | PASS |
| 7 | Oversized input | `OversizedInputAdversarialTest` (9), `RecommendationInputAdversarialTest` (7), import size cases | PASS |
| 8 | Stored XSS and hostile URLs | `ImportAbuseAdversarialTest`, `AlertMailInjectionAdversarialTest`; web 01, 02, 09 | PASS |
| 9 | Injection smoke | `InjectionAdversarialTest` (4), `CatalogInjectionAdversarialTest` (3), hostile country codes | PASS |
| 10 | Deletion completeness | `AccountDeletionAdversarialTest`, `AlertAccountDeletionAdversarialTest` (3) | PASS |
| 11 | Header assertions | access-control, catalog (5), operations, 413 and HSTS cases; web 07; nginx header template | PASS |
| 12 | Enumeration and timing | `EnumerationTimingAdversarialTest` (4) | PASS |
| 13 | Disabled account | `SessionAbuseAdversarialTest` | PASS |
| 14 | E-mail token abuse | `EmailTokenAbuseAdversarialTest` (6), mail and log cases; nginx access log | PASS |
| 15 | Promo abuse | `PromoAbuseAdversarialTest` (5), redemption limit per address | PASS |
| 16 | Limit races | `LimitRaceAdversarialTest` (3), `WatchlistLimitAdversarialTest` (9) | PASS |
| 17 | Open redirect | no API redirect surface; web 04 (19) | PASS (web) |
| 18 | Request id and health | access-control, operations, scoring trust boundary and leakage cases; nginx request id rule | PASS |
| 19 | Bootstrap collision | `BootstrapCollisionAdversarialTest` | PASS |
| §15.4 | Error and log leakage | `LeakageAdversarialTest` (5), mail and trust-boundary leak checks | PASS after fix |
| new | Exchange rate base rejected by the provider | `FxBaseRejectionAdversarialTest` (5), `FxTrustBoundaryAdversarialTest` (7) | PASS after contract change |
| new | Product detail reference and upper limit | `ProductFactsAdversarialTest` (5) | PASS |
| new | Refresh without a cookie | `RefreshWithoutSessionAdversarialTest` (6) | PASS |

Adversarial suite, web and nginx side: 60 tests (51 before).

| Area | Tests | Result |
|---|---|---|
| Stored script payloads, hostile URLs, interpolation markers, product facts at 1280 and 375 px, URL tampering | 01 (8), 02 (2), 09 (4) | PASS after fix |
| Token handling | 03 (4) | PASS |
| Open redirect | 04 (19) | PASS |
| Cross-origin requests, framing, cookie-less refresh from another origin | 05 (5) | PASS |
| Locked data, forced admin routes, forged role | 06 (4) | PASS |
| Security headers on every response class, script policy, caching, repository files never served, refresh 204, 403 and 401 rules, quiet anonymous start | 07 (10) | PASS |
| API outage, rate limit and quota notices | 08 (4) | PASS |

Defects found by the adversarial suite and by an independent review; each has a regression test or check:

| Finding | Fix |
|---|---|
| Restore could replace live services when the environment named the live compose project | every compose call forces the restore project and file; the script refuses the live project |
| Uploaded end-to-end traces could contain credentials | traces off in CI, secrets masked, only scrubbed JUnit reports and failure screenshots uploaded |
| The recovery procedure tore the stack down before reading the account deletions needed afterwards | the deletion record is captured and checked for completeness before teardown; recovery stops when it cannot be shown complete |
| Actions pinned to mutable tags | all 10 references pinned by commit with the version in a comment; the scan checks it |
| An incomplete working-tree copy could still report PASS in the secret scan | every preparation step is checked and counted |
| The fallback secret scan did not scan history and suppressed real credentials by substring | revisions passed correctly, errors distinguished from no match, exact reviewed values only |
| A failing SQL check aborted the scan before the summary | checks run isolated and the failure is recorded |
| Promo codes and token-shaped path segments appeared in the nginx access log | redacted in the logged path |
| Early error responses (400, 413, 414) had no security headers | headers at server scope, each exactly once |
| PostgreSQL and SMTP ports published on the host | removed; only nginx and the Mailpit inbox are published |
| nginx master process ran as root | unprivileged runtime (uid 101) |
| External images pinned by tag only | tag and index digest in compose files and all Dockerfiles; update proposals for both image ecosystems |
| Runbook commands exposed secrets in process arguments | secrets through standard input, hidden prompts and header files |
| The generated bootstrap password was never shown | shown once on an interactive terminal only, never in output streams or CI logs |
| An exchange rate run with a dropped base was reported as succeeded while listing the base as failed; any rejection text containing a currency code dropped that base | contract change (section 8); a base is unsupported only for the exact provider answer naming it, 29 parser variants attacked |
| Request values in debug log lines (encoded e-mail address, password and promo code fragments of malformed bodies) | redaction of encoded addresses, parse errors without body excerpts, framework request loggers pinned to INFO |
| Promo code logged by the framework at debug level | the record hides the code in its text form |
| A long unbroken product name widened the phone page by 47 px | long catalog text wraps on every surface; 21 layout tests at 375 and 1280 px |
| The drawer did not close on Escape when focus was outside it | document-level key handling, innermost drawer only |

## 4. Deviations and assumptions

- The end-to-end CI job is informational and runs for pull requests and the default branch only; whether it is a required check is a repository setting.
- The dependency vulnerability plugin is applied to the API build but is not part of `check`; the scan reports it as skipped without an NVD API key.
- An unsupported base does not fail an exchange rate run on its own; the run fails when a base failed or nothing could be refreshed. A base answered with an empty list still counts as failed because it cannot be told from a broken answer.
- Product detail no longer emits the exceeds-limit flag; the marker comes from `upperLimit.exceeded`, and the web renders server values only.
- Product detail formulation table: the reference amount and set are a second line, the nutrient name is stacked under its code to keep six columns at 1280 px in Turkish, and percentages follow the locale format.
- The restore stack uses the production profile on an internal network without access to the exchange rate provider.
- Backup retention keeps the newest 14 dumps and none older than 14 days; the Python dependency audit covers all dependency groups of the lock file.
- `.gitleaks.toml` holds an exact-value allowlist of reviewed synthetic test values, including an unsigned probe token already present in the adversarial web suite.
- The counter `outbox.dead.letter` keeps its name without the `vitadelta.` prefix; renaming would change an exported name.
- §15 checklist item 22 (spending alerts) stays not applicable in v1: no paid provider is in use; to be revisited with a payment provider or a production SMTP provider.
- The first attempt of the final test script failed at web `npm ci` with a file lock held by a development server left running by an earlier local check; it was stopped and the script was run again.

## 5. Unverified or skipped

- The combined test script was not green in a single uninterrupted pass. The scoring performance test is sensitive to wall-clock load, and its margin on this machine is thin: the idle runs sit about 10 to 18 % under the 200 ms budget.
- The API suite was not re-executed in the final gate run; its result there is the cached result of the last full run on unchanged inputs.
- CI has not run on this branch because the branch is not pushed. The behaviour of the new jobs on a real runner (image builds, browser installation, scanner download, timings) is unverified.
- The dependency vulnerability check has not run: it needs an NVD API key.
- No separate final verification pass was run after the last fixes beyond the gate run above.
- There is no durable record of account deletions outside the database; the runbook procedure depends on the API container log being intact (section 7).
- The nginx error log, unlike the access log, can still show a promo-code path when the API is down during such a call.
- Three of the six README screenshots were inspected by eye.
- The served OpenAPI document still differs from the contract in known, listed gaps (enum-valued strings, cookie-scoped request bodies, the quota response union, the profile patch body, the multipart file field).
- The command table of the local working agreement was not updated with the new commands (security scan, backup, restore, screenshots, `dev-up.sh --bootstrap-admin`); that file is the owner's to change.

## 6. Decisions for the owner

- Approval to push the branch and open the pull request; CI must be green before a merge.
- How to treat the scoring performance budget: keep the 200 ms assertion in the default test script, widen the margin, or run it separately from load-sensitive steps.
- A durable account-deletion ledger (section 7).
- An NVD API key for the dependency vulnerability check, locally and in CI.
- Whether the end-to-end job becomes a required check.
- Whether the nginx error log needs the same path redaction as the access log.
- The new commands in the working agreement's command table.

## 7. Proposed scope changes

- Durable account-deletion ledger. Deletions made after a backup are recorded only in the API container's standard output, which is discarded when the container is removed. For a production deployment either the API output is shipped to a log store with a retention of at least the 14-day backup window (an infrastructure decision), or the API writes an append-only deletion ledger to storage that is not part of the database backup (a new feature). A table in the same database does not help because it is restored to the dump state.

## 8. Branch, commits and contract changes

- Branch `feat/phase-7-hardening`, 51 commits ahead of the default branch, not pushed; small Conventional Commits, one logical change each.

| Scope | Commits | Content |
|---|---|---|
| `contracts` | 2 | product detail facts and refresh 204; unsupported exchange rate bases |
| `api` | 7 | product facts, log context and meters, leakage fixes, dependency check plugin, digest pins, adversarial suite |
| `identity` | 1 | refresh without a cookie answers 204 |
| `fx` | 3 | unsupported base handling, strict provider answer parsing, adversarial cases |
| `web` | 13 | product detail columns, anonymous refresh, formatting, unsupported currencies notice, long-text wrapping, Escape handling, end-to-end flows, screenshots, adversarial cases, README |
| `infra` | 12 | security scan, backup and restore, bootstrap admin, routing check, nginx hardening, port and digest changes, script wiring |
| `scoring` | 1 | digest pins |
| `ci` | 2 | security and end-to-end jobs; pinned actions and artifact hygiene |
| `docs` | 10 | runbook, architecture overview, READMEs, version ADR, screenshots |

Full series, oldest first (the commit that adds this report follows them):

- `95bbc6f` feat(contracts): add reference and upper-limit facts to product detail and a 204 for cookie-less refresh
- `ed88c29` feat(web): regenerate API types for reference and upper-limit facts
- `cb8ac5b` feat(web): show reference coverage and upper limits on product detail
- `ae27f0b` feat(web): treat a cookie-less refresh as an anonymous session
- `ef6cbe1` test(web): complete the end-to-end flows and add a screenshot run
- `1d7d5cd` feat(web): format reference percentages and limits
- `7db17bd` test(web): write the six README screenshots in both languages
- `dc7681c` feat(api): add reference and upper-limit facts to product detail
- `683e7ed` feat(identity): answer a cookie-less refresh with 204
- `30d2cf8` fix(fx): drop a base currency the provider does not publish from the refresh run
- `6076bff` feat(api): add request context to logs and meter rate limits, logins and jobs
- `e71dbc4` fix(api): keep promo codes and framework details out of logs and error bodies
- `e2de1a0` feat(infra): add the security scan script with secret, SQL and dependency checks
- `1149b05` feat(infra): add encrypted backup and isolated restore
- `5710a6a` feat(infra): let dev-up generate a bootstrap admin on request
- `f09e5bd` test(infra): assert headers, HSTS switch and proxy header handling in the routing check
- `80ba2a5` chore(infra): run the security scan from the test script
- `5fff27d` ci: add security and end-to-end jobs
- `92010da` docs: add the operations runbook with the restore drill transcript
- `977e83b` docs: add the architecture overview
- `6ae65e0` docs: restructure both READMEs with scope, technology, setup and validation
- `43aa598` test(api): complete the adversarial suite for refresh, exchange rate bases, product facts and leakage
- `2878676` test(web): attack the cookie-less refresh rule and the product fact columns
- `1358f29` feat(contracts): report unsupported exchange rate bases separately from failed ones
- `570b247` fix(fx): treat only an exact provider rejection of the base as unsupported
- `35a5cdd` fix(api): keep request body fragments and encoded addresses out of every log level
- `a3c5b5c` build(api): add the dependency vulnerability check plugin
- `bff8417` fix(web): wrap long unbroken catalog text on every surface
- `cd5b56a` fix(web): close drawers and dialogs on Escape from anywhere in the document
- `4bab083` feat(web): list unsupported currencies after an exchange rate refresh
- `d589ff7` test(web): check long text layout and compose the README screenshots
- `7e88eed` docs(web): describe the end-to-end and screenshot runs
- `d24704d` test(fx): attack the unsupported-base rule and its strict parser
- `2d9ca55` fix(infra): force the restore project name and refuse the live project
- `ef70cf1` fix(infra): make security scan failures explicit and scan history in the fallback
- `2641104` fix(infra): run nginx unprivileged, mask secrets in access logs and send headers on early errors
- `0dc1644` fix(infra): stop publishing database and SMTP ports and pin images by digest
- `cafabfc` fix(infra): show the generated admin password once on the terminal only
- `3a256e0` ci: pin actions by commit and keep credentials out of uploaded artifacts
- `c6611a9` chore(infra): lint the infrastructure files from the test script
- `3b026a1` docs: capture the deletion record before recovery and keep secrets out of command lines
- `ba1f025` chore(infra): mark the new scripts executable
- `670c7c0` build(api): pin the base images by digest
- `8a3836f` build(scoring): pin the base images by digest
- `b795e8b` test(web): switch Playwright traces off in CI
- `8da7b52` docs: record action and image pins in the version ADR
- `fb3ae15` docs: note the cookie-less refresh rule and unsupported FX bases
- `11a0054` docs: describe product detail facts and the operations layer
- `b36269c` docs: add the metric and log catalogue to the runbook
- `c4d365d` docs: update ports and validation figures in both READMEs
- `7f74da7` docs: add README screenshots in English and Turkish

Contract changes (`contracts/public-api.openapi.yaml`; the scoring contract is unchanged):

| Endpoint or schema | Field | Reason | Compatibility impact |
|---|---|---|---|
| `GET /products/{id}`, `ProductNutrient` | `reference` (new, optional, nullable `NutrientReference`) | owner decision: country reference per nutrient | additive; the API always writes it, `null` without a value |
| `GET /products/{id}`, `ProductNutrient` | `upperLimit` (new, optional, nullable `NutrientUpperLimit`) | owner decision: applicable upper limit and whether the daily amount exceeds it | additive |
| components | `UlBasis`, `NutrientReference`, `NutrientUpperLimit` | support the two fields | additive |
| `POST /auth/refresh` | response `204`; anonymous security alternative | owner decision: no 401 on every anonymous page load; forgery checks (403) and rate limit (429) still run first | behavioural change for the no-cookie case only (401 to 204) |
| `POST /admin/fx/refresh`, `FxRefreshResult` | `unsupportedBases` (new, required); `failedBases` no longer lists unsupported bases | separate a currency the provider does not publish from a failed call | additive for readers; the meaning of `failedBases` is narrowed |
| `GET /admin/health`, `FxHealth` | `unsupportedBases` (new, required) | show the last run's unsupported bases to the operator | additive |
| `JobRun.lastOutcome` | description | an exchange rate run fails when a base failed or nothing was refreshed; unsupported bases alone do not fail it | text only |
| `info.description` | convention note | the served description is public by design | none |

The web types were regenerated and `npm run api:check` passes; the API contract-equality test passes.

## 9. Next phase

Phase 8 is optional: a payment provider adapter. It requires a separate explicit approval and the owner's choice of provider, and nothing of it is scaffolded.

## 10. Awaiting approval

Work stops here. The branch is not pushed and no pull request is open. No force-push or rewrite of pushed history has happened. Merging requires a green CI run.
