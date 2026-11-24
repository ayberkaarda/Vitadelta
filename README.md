# VitaDelta

Türkçe: [README.tr.md](README.tr.md)

VitaDelta is a vitamin and supplement comparison service. It compares supplement products by how well they cover reference nutrient values per country, taking upper limits and price per effective dose into account, and ranks them transparently so that people can see what they actually get for their money. The scoring is deterministic: the same input always produces the same ranking.

> VitaDelta provides comparative information only. It is not medical advice and does not replace a conversation with a physician or pharmacist.

**Status: Phase 2, scoring engine (`/score` and `/compare`, golden and property tests, verdict calibration).** The repository contains the runnable shells of every service, the contracts, the local stack and the CI workflow, the catalog database schema, the reference data and a loader that seeds them, and the scoring engine behind the internal `/score` and `/compare` endpoints. The scoring formulas, configuration and error model are described in [services/scoring/README.md](services/scoring/README.md); the verdict calibration result is in [services/scoring/calibration/verdict-calibration.md](services/scoring/calibration/verdict-calibration.md). Remaining product features arrive in later phases.

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

It loads the reference data, the synthetic fixtures and the real US label snapshot (with synthetic demo prices). The seed is idempotent: run it again after changing files under `data/`.

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
