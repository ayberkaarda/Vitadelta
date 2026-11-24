# Phase 0 report: discovery and scaffold

Date: 2026-11-24. Branch: `feat/phase-0-scaffold`.

## 1. Completed

- Environment inspection and version pins (ADR-0005). Every pin was checked against its registry on 2026-11-24.
- FX provider verification with live calls against Frankfurter v2 (ADR-0009).
- Monorepo skeleton: `apps/web`, `services/api`, `services/scoring`, `contracts`, `infra`, `scripts`, `docs`.
- Root files: `README.md`, `.env.example` (28 `VITADELTA_*` variables, no values), `.editorconfig`, `.gitignore`, `.gitattributes`, `.dockerignore`.
- CI workflow `.github/workflows/ci.yml` (runs `scripts/test-all.sh`; runs `scripts/security-scan.sh` once that script exists) and `.github/dependabot.yml` (npm, uv, gradle, github-actions, weekly).
- `infra/docker-compose.yml` with `postgres`, `mailpit`, `api`, `scoring`, `web`; nginx is the single public origin with the full security header set.
- `scripts/dev-up.sh`, `scripts/dev-down.sh`, `scripts/test-all.sh`.
- Runnable shells with health endpoints: Spring Boot API (`GET /api/v1/health`, everything else denied), FastAPI scoring service (`GET /internal/v1/health`), Angular shell page that shows the API health.
- Complete contracts: `contracts/public-api.openapi.yaml`, `contracts/scoring-api.openapi.yaml`, `contracts/schemas/import-products.schema.json`, `contracts/schemas/offers-csv.columns.md`.
- ADR-0001 to ADR-0011.

## 2. Files by area

| Area | Files |
|---|---|
| Root | `README.md`, `.env.example`, `.editorconfig`, `.gitignore`, `.gitattributes`, `.dockerignore`, `.github/workflows/ci.yml`, `.github/dependabot.yml` |
| Docs | `docs/adr/0001` to `0011`, `docs/reports/phase-0.md` |
| Contracts | `public-api.openapi.yaml` (42 paths, 48 operations, 148 schemas), `scoring-api.openapi.yaml` (3 paths, 42 schemas), `schemas/import-products.schema.json`, `schemas/offers-csv.columns.md` |
| Infra | `infra/docker-compose.yml`, `infra/nginx/Dockerfile`, `nginx.conf`, `snippets/security-headers.conf`, `templates/default.conf.template` |
| Scripts | `scripts/dev-up.sh`, `scripts/dev-down.sh`, `scripts/test-all.sh` |
| API | Gradle build with wrapper 9.9.2, `VitadeltaApiApplication`, `config/SecurityConfig`, `common/HealthController`, `HealthResponse`, `HealthStatus`, `application.yml`, `HealthEndpointTest`, `Dockerfile` |
| Scoring | `pyproject.toml`, `uv.lock`, `.python-version`, `vitadelta_scoring/{__init__,config,main}.py`, `tests/test_health.py`, `Dockerfile` |
| Web | Angular workspace files, `app.config.ts`, `core/health`, `core/i18n`, `shell/`, `styles/_tokens.scss`, `public/i18n/{en,tr}.json` |

No shared-file diffs were needed in this phase.

## 3. Tests and checks

| Command | Result |
|---|---|
| `scripts/dev-up.sh` | exit 0; five services healthy |
| `curl http://localhost:8080/api/v1/health` | `{"status":"UP"}` |
| scoring health through `docker compose exec` | `{"status":"UP","config":{...}}` |
| `curl -I http://localhost:8080/` | 200 with CSP, `X-Content-Type-Options`, `X-Frame-Options: DENY`, `Permissions-Policy`, `Referrer-Policy: strict-origin-when-cross-origin`; no HSTS |
| `/auth/verify` | `Referrer-Policy: no-referrer` |
| `/actuator/health` | 404 from nginx (not proxied); `/api/v1/other` 401 |
| Mailpit UI `http://127.0.0.1:8025/` | 200 |
| Rendered page (headless browser, DOM dump) | health badge shows status `UP` |
| Published ports | `api` and `scoring` none; `postgres` 127.0.0.1:54320; `mailpit` 127.0.0.1:1025 and 8025; `web` 8080 |
| `scripts/test-all.sh` | exit 0; 13 steps passed |
| contracts | Redocly 2.83.0: both files valid, 0 errors; JSON Schema compiles under draft 2020-12; one valid and eight invalid sample documents behave as expected |
| scoring | 5 tests passed; `ruff check`, `ruff format --check`, `mypy --strict` clean |
| api | `./gradlew check --rerun-tasks`: 12 tests, 0 failures; Spotless clean |
| web | 22 tests passed; ESLint and Prettier clean; production build 47.5 kB gzipped initial JS, no source maps, no inline script |
| `scripts/dev-down.sh --volumes` | exit 0; no containers, volumes or networks left |

The acceptance run was repeated independently from a clean stack with the same results. A broken copy of a contract (dangling `$ref`) fails the pinned linter, so the contract gate is effective. Coverage thresholds apply from the phases that add business code.

## 4. Deviations and assumptions

- Angular 22 requires TypeScript `>=6.0 <6.1`; 6.0.3 is pinned. `openapi-typescript` 7.13.0 needs TypeScript 5.x, so type generation will use the isolated `apps/web/tools/openapi` package from Phase 6 (ADR-0005).
- Flyway is taken at the version managed by the Spring Boot BOM (12.11.0). Bucket4j's current artifact is `com.bucket4j:bucket4j_jdk17-core`. WireMock 4 exists only as a beta; ADR-0005 records the decision.
- Translation files live in `apps/web/public/i18n/` (the Angular 22 static asset folder) instead of `src/assets/i18n/`.
- PostgreSQL is published on host port 54320 because 5432 is commonly taken by a local PostgreSQL.
- Under `/api/` nginx keeps the API's own stricter `Content-Security-Policy` and `Referrer-Policy` when the API sends them and applies the web values otherwise, so each header appears once.
- The branch is named `feat/phase-0-scaffold`.
- `CI`: the security-scan step is conditional because `scripts/security-scan.sh` is delivered with the security layer.
- The project specification is kept outside the repository.
- Assumptions of the specification (section 2.2) stand as written: one country per query; offers come from imports only; general adult reference values; mass units compared directly with IU conversion only; chemical form does not affect the score except for UL matching and IU conversion; anonymous identity is a signed cookie plus IP limits; plan limits are data (FREE 5/3/30, PRO unlimited, PRO list price 199.00 TRY per month); PRO through promo codes or admin grant; Frankfurter v2 with the ECB provider; single API instance; POSIX shell required for scripts; SMTP from environment with Mailpit locally.

Contract shape decisions taken where the specification is silent (all can be refined as reportable contract changes):

- Imports are asynchronous: `202` with a job resource and `Location`; row errors carry `row_number` and `message`.
- An import with any row error fails as a whole and writes nothing. A header-only offers CSV succeeds.
- `OFFERS_CHANGED` follows the effective-state definition.
- The admin promo-code list shows full codes (admin only), since deletion is by code.
- `404` responses without a dedicated problem code use `about:blank`.
- `DELETE /me` with a wrong password returns `401 invalid_credentials`.
- Comparing a product with itself is not rejected by the contract.
- The scoring breakdown row has a `below_presence_floor` flag, and money-typed fields carry an `x-money` marker.

## 5. Unverified or skipped

- Resilience4j `resilience4j-spring-boot4` 2.4.0 was built against Spring Boot 4.0; compatibility with 4.2.0 is confirmed only when the first context test runs in Phase 4.
- OWASP Dependency-Check was not run: without an NVD API key the initial data download takes more than 20 minutes.
- Frankfurter rate limits are not documented in the responses; the error behaviour for a real currency that the ECB does not publish was not tested.
- `age` is not installed on the development host (needed by the backup script later). `gitleaks` and `pip-audit` are available.
- Dependabot documents `uv` support; lock-file compatibility with uv 0.13 was not tested.
- FX coverage: no gap. TRY, EUR, GBP and USD are all served by the ECB provider and TRY works as a base.

## 6. Decisions for the owner

- PRO list price (default 199.00 TRY per month) and FREE limits, due at the Phase 3 gate.
- License and contact fields for the OpenAPI `info` blocks (the only remaining linter warnings).
- NVD API key for the dependency audit; installing `age`.
- Scoring location: decided on 2026-11-24, scoring stays in Python (ADR-0002).

## 7. Proposed scope changes

None.

## 8. Branch, commits and contract changes

- Branch `feat/phase-0-scaffold`; the commit series is small Conventional Commits, one logical change each (root config, each ADR, each contract file, each script, infra pieces, each service shell split into build, source, tests and image).
- Contract changes: none (first version of all contracts).

## 9. Next phase

Phase 1, reference data and catalog domain: reference CSVs and synthetic fixtures under `data/`, and in parallel the catalog migration `V1__catalog.sql`, entities, repositories, the catalog write transaction with the version bump, the seed loader and the unit conversion service. Risks: reference and upper-limit rows that cannot be verified against the cited sources are omitted and reported; Testcontainers needs Docker in CI.

## 10. Awaiting approval

No force-push or history rewrite has happened. Merging requires a green CI run.
