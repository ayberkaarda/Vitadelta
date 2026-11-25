# VitaDelta

Türkçe: [README.tr.md](README.tr.md)

VitaDelta is a vitamin and supplement comparison service. It compares supplement products by how well they cover reference nutrient values per country, taking upper limits and price per effective dose into account, and ranks them transparently so that people can see what they actually get for their money. The scoring is deterministic: the same input always produces the same ranking.

> VitaDelta provides comparative information only. It is not medical advice and does not replace a conversation with a physician or pharmacist.

**Status: Phase 6, the web application (query builder, ranked results with locked rows for visitors, comparison verdicts, product pages, accounts, saved products, alerts, notifications, upgrade page, admin pages and privacy notice; Turkish and English UI; light and dark themes), on top of the Phase 5 saved products, alert subscriptions, notifications with e-mail delivery through a transactional outbox, exchange rates with display-currency conversion, and admin health and outbox statistics, the Phase 4 catalog and product endpoints, ranked recommendations with the try-before-sign-up limit, pairwise comparison and admin imports, the Phase 3 accounts, plans and promo codes and the Phase 2 scoring engine (`/score` and `/compare`, golden and property tests, verdict calibration).** The repository contains the runnable shells of every service, the contracts, the local stack and the CI workflow, the catalog database schema, the reference data and a loader that seeds them, and the scoring engine behind the internal `/score` and `/compare` endpoints. The scoring formulas, configuration and error model are described in [services/scoring/README.md](services/scoring/README.md); the verdict calibration result is in [services/scoring/calibration/verdict-calibration.md](services/scoring/calibration/verdict-calibration.md). The API now also provides registration, e-mail verification, login with refresh sessions, password reset, plan entitlements and promo codes. The catalog is readable through the API (countries, nutrients, product search and detail), recommendation queries return a ranked list scored by the scoring service, and two products can be compared pairwise. Remaining product features arrive in later phases.

## Architecture at a glance

```
browser --> nginx (web, :8080, single public origin)
              |-- /      static Angular app
              '-- /api   --> api (Spring Boot) --> PostgreSQL
                                  |
                                  |--> scoring (FastAPI, internal only)
                                  '--> Mailpit (SMTP, local development)
```

| Component | Technology | Notes |
|---|---|---|
| `apps/web` | Angular 22, served by nginx | Single public origin; proxies `/api` to the API |
| `services/api` | Spring Boot 4, Java 25, Gradle | Public REST API, no published port |
| `services/scoring` | FastAPI, Python 3.14, uv | Pure scoring engine, reachable only from the API |
| PostgreSQL 18 | Docker | Persistent named volume |
| Mailpit | Docker | Catches outgoing e-mail in development |

The OpenAPI and JSON Schema files in `contracts/` are authoritative: the web app, the API and the scoring service are implemented against them, and a contract change is made in `contracts/` first.

## Prerequisites

- JDK 25 (Temurin recommended), with `JAVA_HOME` pointing to it. A newer JDK on `PATH` is not enough; Gradle uses `JAVA_HOME`.
- Node.js 22.23 or newer in the 22 line, with npm
- [uv](https://docs.astral.sh/uv/) with Python 3.14 (`uv python install 3.14`)
- Docker with Compose v2
- Git Bash or WSL on Windows, because everything under `scripts/` is POSIX `bash`

## Quick start

```bash
scripts/dev-up.sh
```

On the first run the script creates `.env` from `.env.example`, fills in development values and generates fresh random secrets, then builds the images, starts the stack and waits until every service is healthy. It finishes by checking the endpoints below. An existing `.env` is never overwritten. `.env` is ignored by Git; never commit it.

| What | URL |
|---|---|
| Web app | http://localhost:8080 |
| API health | http://localhost:8080/api/v1/health |
| Mailpit inbox | http://127.0.0.1:8025 |
| Mailpit SMTP | 127.0.0.1:1025 |
| PostgreSQL | 127.0.0.1:54320 (container port 5432; the host port is 54320 so it does not clash with a local PostgreSQL) |

Load the catalog into the running stack with:

```bash
scripts/seed.sh
```

It loads the reference data, the synthetic fixtures and the real US label snapshot (with synthetic demo prices). The seed is idempotent: run it again after changing files under `data/`. A run that changes nothing leaves the catalog version as it is; changed offers trigger price alerts like an admin import.

Stop the stack with:

```bash
scripts/dev-down.sh             # keeps the database volume
scripts/dev-down.sh --volumes   # also deletes the database volume (all local data is lost)
```

### Without a POSIX shell

If neither Git Bash nor WSL is available, create `.env` yourself by copying `.env.example` and giving every variable a value (use long random strings for the secrets), then run Compose directly from the repository root:

```bash
docker compose --env-file .env -f infra/docker-compose.yml up -d --build --wait
docker compose --env-file .env -f infra/docker-compose.yml down
```

Add `--volumes` to the `down` command to delete the database volume as well.

## Accounts and e-mail in development

Register through the API (`POST /api/v1/auth/register`); the verification and password-reset e-mails are caught by Mailpit and can be read at http://127.0.0.1:8025. The first administrator is created from `VITADELTA_BOOTSTRAP_ADMIN_EMAIL` and `VITADELTA_BOOTSTRAP_ADMIN_PASSWORD` in `.env`; no default credentials exist, so set both before starting the API or no administrator is created. The first `scripts/dev-up.sh` run generates every secret into `.env`. Actuator health and metrics are served on an internal port inside the api container that is not published or proxied, and logs are structured JSON with credentials and e-mail addresses redacted.

## Using the web app

After `scripts/dev-up.sh` and `scripts/seed.sh`, open http://localhost:8080. The interface is available in Turkish and English, in light and dark themes. Without an account a visitor can build a query, run three free queries and see the first three results of each ranking; the remaining rows are shown locked. Registering unlocks the full ranking and adds saved products, price alerts, notifications and the upgrade page. Verification and password-reset e-mails are caught by Mailpit and can be read at http://127.0.0.1:8025. To become an administrator locally, set `VITADELTA_BOOTSTRAP_ADMIN_EMAIL` and `VITADELTA_BOOTSTRAP_ADMIN_PASSWORD` in `.env` before the API starts and sign in with those credentials; the admin pages (imports, promo codes, users) then appear in the account menu.

The app shows comparative information only and is not medical advice. Products marked as label snapshots come from the NIH DSLD snapshot described under Data sources and disclaimers, and their prices are demo offers.

The web tests run from `apps/web`; the end-to-end suite needs the stack up and seeded, a bootstrap administrator in `.env`, and a restarted `api` service between runs because the API rate limits are held in memory:

```bash
cd apps/web
npm run lint
npm test
npm run build
npm run e2e:install
npm run e2e

# or, from the repository root, with the stack up
RUN_E2E=1 scripts/test-all.sh
```

## Trying the API

After `scripts/dev-up.sh` and `scripts/seed.sh`, the API is reachable through nginx on http://localhost:8080:

```bash
curl -s "http://localhost:8080/api/v1/countries"
curl -s "http://localhost:8080/api/v1/products?country=TR&q=gold"
curl -s -X POST "http://localhost:8080/api/v1/recommendations/query" \
  -H "Content-Type: application/json" \
  -d '{"country": "TR"}'
curl -s -X POST "http://localhost:8080/api/v1/recommendations/compare" \
  -H "Content-Type: application/json" \
  -d '{"country": "TR", "baselineProductId": "<id from the product search>", "candidateProductId": "<another id from the product search>"}'
curl -s "http://localhost:8080/api/v1/openapi.json"
```

Without an account, a visitor gets three free recommendation queries and sees the top three results of each; the fourth query is answered with `quota_exceeded`. Use `-c cookies.txt -b cookies.txt` with the query call to keep the same anonymous session between requests. The OpenAPI document of the running API is served at `/api/v1/openapi.json`. Administrators import products (JSON) and offers (CSV) through `/api/v1/admin/imports/*` (role ADMIN); the formats are defined in `contracts/schemas/`. Exchange rates are ECB reference rates fetched through Frankfurter and refreshed every six hours; they are indicative only, and when no rate is stored for a currency pair the prices stay in their native currency and the response says so.

Registering, saving a product, subscribing to an alert and reading the results looks like this (the verification token is read from the latest message in Mailpit; the saved product is the one used for the price-drop demo below):

```bash
API=http://localhost:8080/api/v1
JSON='Content-Type: application/json'
curl -s -X POST "$API/auth/register" -H "$JSON" \
  -d '{"email": "me@example.com", "password": "correct-horse-battery", "countryCode": "TR"}'
VERIFY=
until [ -n "$VERIFY" ]; do
  sleep 1
  VERIFY=$(curl -s http://127.0.0.1:8025/api/v1/message/latest | grep -o '#token=[A-Za-z0-9_-]*' | head -1 | cut -d= -f2)
done
curl -s -X POST "$API/auth/verify-email" -H "$JSON" -d "{\"token\": \"$VERIFY\"}"
ACCESS=$(curl -s -X POST "$API/auth/login" -H "$JSON" \
  -d '{"email": "me@example.com", "password": "correct-horse-battery"}' | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
PRODUCT=$(curl -s "$API/products?country=TR&q=Gold%20Standard%20Daily%20B" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
curl -s -X POST "$API/me/saved-products" -H "$JSON" -H "Authorization: Bearer $ACCESS" \
  -d "{\"productId\": \"$PRODUCT\", \"countryCode\": \"TR\"}"
curl -s -X POST "$API/me/alerts" -H "$JSON" -H "Authorization: Bearer $ACCESS" \
  -d "{\"productId\": \"$PRODUCT\", \"countryCode\": \"TR\", \"trigger\": \"PRICE_DROP\", \"thresholdPercent\": 5}"
curl -s "$API/me/notifications" -H "Authorization: Bearer $ACCESS"
curl -s "$API/me/notifications/unread-count" -H "Authorization: Bearer $ACCESS"
curl -s "$API/fx/currencies"
curl -s "$API/fx/rates?base=TRY"
curl -s -X POST "$API/recommendations/query" -H "$JSON" \
  -d '{"country": "TR", "displayCurrency": "USD"}'
```

To see an alert fire locally, sign in as the bootstrap administrator and import `data/fixtures/offers.price-drop.synthetic.csv` through `POST /api/v1/admin/imports/offers` (multipart field `file`). It lowers the Gold Standard Daily B price from 900.00 to 846.00; within about ten seconds the outbox processor creates the notification, which then shows up in `GET /api/v1/me/notifications`, and the alert e-mail is visible in Mailpit at http://127.0.0.1:8025.

## Running the tests

```bash
scripts/test-all.sh
```

The script runs every suite that exists (contract linting, scoring, API, web), prints a per-step summary and exits non-zero if anything fails. The API suite starts PostgreSQL through Testcontainers and therefore needs a running Docker daemon. The suites can also be run on their own:

```bash
# scoring (from services/scoring)
uv sync --locked
uv run --locked ruff check .
uv run --locked ruff format --check .
uv run --locked mypy .
uv run --locked pytest

# api (from services/api, with JAVA_HOME set to JDK 25)
./gradlew check

# web (from apps/web)
npm ci
npm run lint
npm test
npm run build
```

## Repository layout

```
apps/web/            Angular application
services/api/        Spring Boot API
services/scoring/    FastAPI scoring service
contracts/           OpenAPI and JSON Schema contracts
infra/               Docker Compose stack and nginx configuration
scripts/             dev-up.sh, dev-down.sh, test-all.sh
.github/             CI workflow and Dependabot configuration
data/                Reference data, synthetic fixtures and the real label snapshot (from Phase 1)
scripts/dsld/        Converter for the real label snapshot (from Phase 1)
```

Architecture decisions are recorded in [docs/adr](docs/adr).

## Data sources and disclaimers

- **Synthetic fixtures.** The test and demonstration fixtures use fictional brands and are marked as synthetic.
- **Real US label snapshot.** From Phase 1 the repository also contains a snapshot of real US multivitamin and mineral label data from the [NIH Dietary Supplement Label Database](https://dsld.od.nih.gov/) (DSLD), obtained through its public API. The API specification states the data as CC0 1.0; the website's terms pages could not be read, so the licence is confirmed only from the API specification. See [ADR-0012](docs/adr/0012-real-label-data-source.md).
- **Names for identification only.** Brand and product names are used only to identify products. VitaDelta is not affiliated with, and not endorsed by, the brands or NIH.
- **A snapshot, not live data.** Label data is a snapshot with the entry date shown; labels may have changed since.
- **Prices are demo values.** Prices of real products are invented demo offers and do not reflect any real retailer.
- **No images or logos.** No label images or brand logos are used.
- **Not medical advice.** The comparison is informational and does not replace advice from a physician or pharmacist.

## Continuous integration

Every push and pull request runs `scripts/test-all.sh` on GitHub Actions. A pull request is mergeable only with a green run.
