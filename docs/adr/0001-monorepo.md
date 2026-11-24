# ADR-0001: Monorepo with three deployables and shared contracts

- Status: Accepted
- Date: 2026-11-24

## Context

Vitadelta consists of an Angular web app, a Spring Boot core API and a stateless Python scoring service, plus local infrastructure (PostgreSQL, Mailpit, nginx) run with Docker Compose. The three units are developed together: a change to the scoring model usually touches the scoring service, the API that orchestrates it and the web app that presents the result. The interfaces between them (the public API used by the web app and the internal scoring API used by the core API) must stay consistent at every commit, and the reference data and fixtures are shared by the API importer, the scoring tests and the end-to-end tests.

## Decision

All code lives in one Git repository with this top-level layout:

| Path | Content |
|---|---|
| `apps/web` | Angular web app (deployable 1) |
| `services/api` | Spring Boot core API (deployable 2) |
| `services/scoring` | FastAPI scoring service (deployable 3) |
| `contracts/` | `public-api.openapi.yaml`, `scoring-api.openapi.yaml`, JSON Schemas for imports, the offers CSV column definition |
| `data/reference`, `data/fixtures` | Reference data (nutrients, forms, reference values, upper limits, countries) and synthetic fixtures |
| `infra/` | Docker Compose file and the nginx image that serves the web build and proxies `/api/` |
| `scripts/` | POSIX shell entry points (`dev-up.sh`, `dev-down.sh`, `test-all.sh`, later `security-scan.sh`) |
| `docs/` | Architecture decision records, phase reports, runbook |

Rules:

- `contracts/` is the single source of truth for every HTTP interface between deployables. Clients are generated from it (Java client for the scoring API, TypeScript types for the public API); hand-written duplicates of contract models are not allowed.
- Each deployable has its own build tool and lock file (Gradle wrapper, `uv.lock`, `package-lock.json`) and builds into its own container image. Deployables never import each other's source code.
- One CI workflow runs `scripts/test-all.sh` for the whole repository on every push and pull request; a pull request is mergeable only with a green run.
- Scripts require a POSIX shell (Git Bash or WSL on Windows).

## Consequences

- A contract change, its server implementation and its client usage land in one pull request and are tested together; there is no version skew between deployables inside a commit.
- The repository needs three toolchains (JDK, Python with `uv`, Node). Contributors who touch one unit still need the others to run the full test suite; `scripts/test-all.sh` hides the differences behind one command.
- CI time grows with all three units on every change. Path filters may be added later; until then correctness is preferred over speed.
- Releases are per repository commit; independent versioning of the deployables is not supported and not needed in v1.

## Alternatives considered

- **One repository per deployable plus a contracts repository.** Rejected: every cross-cutting change would need coordinated pull requests and published contract versions, which adds release overhead without benefit for a single-team product.
- **Monorepo build orchestrator (Nx, Bazel, Gradle composite build across languages).** Rejected for v1: the three native build tools are sufficient, and an extra layer would have to be learned and maintained for little gain at this size.
