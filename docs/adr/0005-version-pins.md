# ADR-0005: Version pins

- Status: Accepted
- Date: 2026-11-24

## Context

The project specification sets a version baseline (Java 25 LTS, Spring Boot 4.2.x, Gradle 9.9.x, Angular 22.x, Python 3.14, FastAPI 0.165, Pydantic 2.14, PostgreSQL 18, nginx stable 1.30, Mailpit 1.34, Playwright 1.65, openapi-generator 7.27) and leaves several items open: the exact Spring Boot patch, the springdoc and Resilience4j versions compatible with it, and whether `openapi-typescript` works with the TypeScript version Angular 22 requires. Builds must be reproducible, so every dependency, tool and image is pinned to an exact version. This record lists the pins as they stand in the repository manifests and the facts checked against the registries (Maven Central, Gradle Plugin Portal, npm, PyPI, Docker Hub, GitHub releases) on 2026-11-24.

## Decision

Every version is pinned exactly in the manifest named in the tables below; ranges and floating tags are not used for dependencies. Java dependencies without an explicit version take the version managed by the Spring Boot BOM, which is itself pinned. Upgrades go through Dependabot pull requests and a green CI run.

### Host toolchain inspected

| Tool | Found | Note |
|---|---|---|
| JDK for builds | Temurin 25.0.5.1+2 LTS | The default `java` on the development host's PATH is Temurin 26.0.2.1; builds set `JAVA_HOME` to the JDK 25. The Gradle toolchain requires Java 25. |
| Node / npm | 22.23.2 / 10.9.8 | Angular 22.3 requires Node `^22.22.3`, `^24.15.0` or `>=26`. |
| uv / Python | uv 0.13.9 / CPython 3.14.10 (managed by uv) | Bare `python3` in Git Bash is not usable on the development host; scripts call Python only through `uv`. |
| Docker / Compose | 29.7.2 / v5.5.1 | |
| POSIX shell | GNU bash 5.3.15 (Git Bash) | WSL2 is also available. |
| gitleaks | 8.30.1 | Available on the host. |
| pip-audit | 2.10.1 (through `uvx`) | Available. |
| psql, just, age | not installed | `psql` is used through the PostgreSQL container; `just` is not used; `age` must be installed before the backup drill. |

### In use in Phase 0

| Component | Exact pin | Where pinned |
|---|---|---|
| Spring Boot (Gradle plugin and BOM) | 4.2.0 | `services/api/build.gradle.kts` |
| Spring dependency management plugin | 1.1.7 | `services/api/build.gradle.kts` |
| Spotless Gradle plugin | 8.12.0 | `services/api/build.gradle.kts` |
| google-java-format (Spotless step) | 2.0.0 | `services/api/build.gradle.kts` |
| Java toolchain | 25 | `services/api/build.gradle.kts` |
| Gradle wrapper | 9.9.2 (bin, `distributionSha256Sum` verified) | `services/api/gradle/wrapper/gradle-wrapper.properties` |
| Spring Framework / Spring Security (BOM-managed) | 7.1.0 / 7.2.0 | Spring Boot 4.2.0 BOM |
| JUnit Jupiter / Mockito / AssertJ (BOM-managed) | 6.1.3 / 5.24.0 / 3.27.7 | Spring Boot 4.2.0 BOM |
| API build image | `eclipse-temurin:25.0.5.1_2-jdk-noble` | `services/api/Dockerfile` |
| API runtime image | `eclipse-temurin:25.0.5.1_2-jre-noble` | `services/api/Dockerfile` |
| Python | `==3.14.*`, `.python-version` 3.14; resolved 3.14.10 | `services/scoring/pyproject.toml`, `services/scoring/.python-version` |
| FastAPI | 0.165.0 | `services/scoring/pyproject.toml` |
| Pydantic | 2.14.4 (pydantic-core 2.50.5 through the lock) | `services/scoring/pyproject.toml`, `uv.lock` |
| uvicorn | 0.54.0 | `services/scoring/pyproject.toml` |
| pytest / httpx / mypy / ruff | 9.2.0 / 0.28.1 / 2.4.1 / 0.17.6 | `services/scoring/pyproject.toml` (dev group) |
| hatchling (build backend) | 1.33.0 | `services/scoring/pyproject.toml` |
| uv (in image) | `ghcr.io/astral-sh/uv:0.13.9` | `services/scoring/Dockerfile` |
| Scoring image | `python:3.14.10-slim-trixie` | `services/scoring/Dockerfile` |
| Angular (`core`, `common`, `compiler`, `platform-browser`, `cli`, `build`, `compiler-cli`) | 22.3.0 | `apps/web/package.json` |
| TypeScript | 6.0.3 | `apps/web/package.json` |
| Vitest / jsdom | 5.1.1 / 31.0.1 | `apps/web/package.json` |
| @jsverse/transloco | 8.4.0 | `apps/web/package.json` |
| rxjs / tslib | 7.8.2 / 2.9.1 | `apps/web/package.json` |
| eslint / @eslint/js / angular-eslint / typescript-eslint | 10.15.0 / 10.0.1 / 22.8.0 / 8.74.0 | `apps/web/package.json` |
| prettier | 3.10.1 | `apps/web/package.json` |
| npm (`packageManager`) / Node engine | 10.9.8 / `>=22.23.0` | `apps/web/package.json` |
| Web build image | `node:22.23.5-alpine` | `infra/nginx/Dockerfile` |
| nginx (stable line) | `nginx:1.30.8-alpine` | `infra/nginx/Dockerfile` |
| PostgreSQL | `postgres:18.7-alpine` | `infra/docker-compose.yml` |
| Mailpit | `axllent/mailpit:v1.34.0` | `infra/docker-compose.yml` |
| @redocly/cli | 2.83.0 | `scripts/test-all.sh` |
| ajv-cli / ajv-formats | 5.0.0 / 3.0.1 | `scripts/test-all.sh` |
| GitHub Actions | `actions/checkout@v7`, `actions/setup-java@v6` (Temurin, Java 25), `actions/setup-node@v7` (Node 22), `astral-sh/setup-uv@v10` (uv 0.13.9, Python 3.14) | `.github/workflows/ci.yml` |

### Verified for later phases

These versions were checked on 2026-11-24 and are the pins to use when the corresponding feature lands; they are added to the manifests in that phase.

| Component | Version | Note |
|---|---|---|
| springdoc-openapi | 3.2.1 | Its parent is `spring-boot-starter-parent` 4.2.0, so it targets Boot 4.2. |
| Resilience4j Spring Boot 4 module | `io.github.resilience4j:resilience4j-spring-boot4:2.4.0` | Built against Spring Boot 4.0.0; see Unverified. |
| Bucket4j | `com.bucket4j:bucket4j_jdk17-core:8.23.0` | See Deviations. |
| Flyway | 12.11.0, managed by the Boot 4.2.0 BOM (`spring-boot-starter-flyway` + `org.flywaydb:flyway-database-postgresql`) | See Deviations. |
| BouncyCastle | `org.bouncycastle:bcprov-jdk18on:1.87` | Argon2 backend for `Argon2PasswordEncoder` (ADR-0006). |
| Testcontainers | 2.0.5 (BOM-managed, equal to the newest `testcontainers-bom`) | |
| Other BOM-managed libraries | Hibernate 7.4.15.Final, Jackson 3.1.8, Caffeine 3.3.1, PostgreSQL JDBC 42.7.14, Thymeleaf 3.1.7.RELEASE, Angus Mail 2.0.5, Spring Data 2026.1.0 | Taken from the Spring Boot 4.2.0 BOM; no explicit versions in the build file. |
| WireMock | `org.wiremock:wiremock-standalone:4.0.0-beta.44` (test scope) | See Deviations. |
| openapi-generator | 7.27.0 (CLI and Gradle plugin `org.openapi.generator`) | Generator `java`, library `restclient`, options `useSpringBoot4=true` (Spring RestClient 7.x) and `useJackson3=true`; both options confirmed in `config-help -g java` of 7.27.0. |
| Playwright | `@playwright/test` 1.65.1 | |
| @axe-core/playwright | 4.14.0 | |
| openapi-typescript | 7.13.0, with TypeScript 5.9.3 in an isolated package | See Deviations. |
| Hypothesis | 6.170.17 | Property-based tests of the scoring service. |
| OWASP Dependency-Check Gradle plugin | `org.owasp.dependencycheck` 13.0.0 (Gradle Plugin Portal; the Maven Central metadata is stale) | See Unverified. |
| pip-audit | 2.10.1 | Run through `uvx`. |
| gitleaks | 8.30.1 | |

### Deviations from the specification baseline

1. **TypeScript 6.0.x.** Angular 22.3 requires TypeScript `>=6.0 <6.1` (peer of `@angular/compiler-cli` and `@angular/build`); the newest patch is 6.0.3, which is pinned. The npm `latest` tag (7.0.2) is not allowed; `typescript-eslint` 8.74.0 also excludes it.
2. **openapi-typescript needs TypeScript 5.x.** `openapi-typescript` 7.13.0 (newest) declares the peer `typescript: ^5.x`, so installing it next to TypeScript 6.0.3 fails with `ERESOLVE`. A trial showed that a separate package with `typescript@5.9.3` and `openapi-typescript@7.13.0` installs cleanly and generates output that type-checks under TypeScript 6.0.3 in strict mode. Decision: the specification's fallback applies from Phase 6, when the public API client is generated: an isolated `apps/web/tools/openapi/package.json` pins `openapi-typescript` 7.13.0 and `typescript` 5.9.3. The application never uses `--legacy-peer-deps`.
3. **Flyway version managed by the BOM.** Spring Boot 4.2.0 manages Flyway 12.11.0, while the newest Flyway release is 13.13.0. Decision: use the BOM-managed 12.11.0 and do not override it, so that the Spring Boot auto-configuration is used with the version it was tested against.
4. **Bucket4j artifact id.** The maintained artifact is `com.bucket4j:bucket4j_jdk17-core` (8.23.0, 2026-11-22). The plain `com.bucket4j:bucket4j-core` stopped at 8.10.1 in 2024, and there is no `_jdk25` variant. Decision: use `bucket4j_jdk17-core` 8.23.0 (the variant for Java 17 and newer).
5. **WireMock 4 exists only as a beta.** There is no 4.x GA release; the newest is 4.0.0-beta.44, and `org.wiremock.integrations:wiremock-spring-boot` 4.4.3 exists. Decision: use `org.wiremock:wiremock-standalone:4.0.0-beta.44` in test scope only, pinned exactly; it never reaches the runtime image. The Spring Boot integration is not used unless its compatibility with Boot 4.2 is confirmed. The pin moves to 4.0.0 GA when it is released.
6. **Dependabot `uv` support.** GitHub documents a `uv` package ecosystem with version updates (documented supported version: uv v0.11). It is used for `services/scoring` in `.github/dependabot.yml`; the project uses uv 0.13.9, so lock-file compatibility of Dependabot updates is confirmed with the first Dependabot pull request. If it fails, the fallback is the `pip` ecosystem.
7. **Patch levels beyond the baseline.** Spring Boot 4.2.0 brings Spring Framework 7.1.0 and Spring Security 7.2.0; the baseline only says "7", so this is consistent.

### Unverified

- **Resilience4j against Boot 4.2.** `resilience4j-spring-boot4` 2.4.0 (2026-03-14) is compiled against Spring Boot 4.0.0; there is no release targeting 4.2. Runtime compatibility with Boot 4.2.0 is confirmed only when the module is added and the build with a context-load test passes. If it fails, the circuit breaker and retry are configured with the core Resilience4j modules without the Boot integration.
- **OWASP Dependency-Check without an NVD API key.** The plugin uses the NVD API; without a key the initial download is rate limited (5 requests per 30 seconds, about 216 pages for the current CVE count) and takes at least about 25 minutes, realistically longer. A full run without a key was not executed. Until an NVD API key secret and a cached data directory are provided, `scripts/security-scan.sh` reports this check as `SKIPPED(no NVD_API_KEY)`. The Sonatype OSS Index analyzer is disabled without credentials.
- **WireMock Spring Boot integration** `wiremock-spring-boot` 4.4.3 with Boot 4.2: not tested.
- **Dependabot with uv 0.13.9 lock files:** not tested (see Deviations).
- **Image digests:** images are pinned by exact tag, not by digest. The index digests of the exact tags were recorded during verification; floating tags can show a different index digest for identical content.
- **Runtime image packages:** the API runtime image installs `curl` from the Ubuntu noble package archive without a pinned package version.

### Added in Phase 1

| Item | Pin | Where pinned | Notes |
|---|---|---|---|
| Spring Data JPA, Flyway (`spring-boot-starter-flyway`, `flyway-database-postgresql`), PostgreSQL JDBC driver | managed by the Spring Boot 4.2.0 BOM | `services/api/build.gradle.kts` | Flyway 12.11.0 as managed |
| Testcontainers (`testcontainers-junit-jupiter`, `testcontainers-postgresql`, `spring-boot-testcontainers`) | managed by the Spring Boot 4.2.0 BOM | `services/api/build.gradle.kts` | test scope; uses the `postgres:18.7-alpine` image, so the API test suite needs a Docker daemon |
| JaCoCo | 0.8.16 | `services/api/build.gradle.kts` | 85 % line coverage gate in `check` |
| `com.networknt:json-schema-validator` | 3.0.9 | `services/api/build.gradle.kts` | test scope; schema and reader parity test; Maven Central, released 2026-11-03 |
| `jsonschema` | 4.26.0 | `services/scoring/pyproject.toml` | dev dependency for the data suite |
| `rfc3339-validator`, `rfc3986-validator` | 0.1.4, 0.1.1 | `services/scoring/pyproject.toml` | format checks for `date-time` and `uri` |
| `types-jsonschema` | 4.26.0.20261006 | `services/scoring/pyproject.toml` | typing stubs for strict mypy |
| pytest for the label-converter tests | 9.2.0 | `scripts/test-all.sh` | same version as the scoring service |

## Consequences

- Builds are reproducible from the manifests and lock files (`uv.lock`, `package-lock.json`, the Gradle wrapper checksum).
- Some pins are newer than or different from the baseline wording; each difference is recorded above, and the specification baseline remains the reference for later reviews.
- The isolated openapi-typescript package adds a second, small Node package to maintain from Phase 6.
- Unverified items have a defined check and fallback; this ADR is updated when each one is resolved.

## Alternatives considered

- **Version ranges or floating tags** (`^`, `~`, `latest`, `stable-alpine`). Rejected: builds would change without a commit.
- **Overriding BOM-managed versions with the newest releases** (for example Flyway 13). Rejected: diverges from the combination Spring Boot tests and supports.
- **`--legacy-peer-deps` for openapi-typescript.** It worked in a trial, but would disable peer checks for the whole web app. Rejected in favour of the isolated package.
- **TypeScript 7.** Rejected: outside the range Angular 22 supports.
