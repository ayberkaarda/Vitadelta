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
| GitHub Actions | pinned by full commit SHA with the version in a comment (Phase 7, see "Added in Phase 7"): `actions/checkout` v7.0.1, `actions/setup-java` v6.3.0 (Temurin, Java 25), `actions/setup-node` v7.1.0 (Node 22), `astral-sh/setup-uv` v10.4.0 (uv 0.13.9, Python 3.14), `actions/upload-artifact` v7.0.2 | `.github/workflows/ci.yml` |

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

- **Resilience4j against Boot 4.2.** Resolved in Phase 4: `resilience4j-spring-boot4` 2.4.0 (2026-03-14) is compiled against Spring Boot 4.0.0, and it works with Spring Boot 4.2.0, verified by a context-load test and behaviour tests (configuration binding, retry, circuit breaker, state gauge). The fallback to the core modules was not needed.
- **OWASP Dependency-Check without an NVD API key.** The plugin uses the NVD API; without a key the initial download is rate limited (5 requests per 30 seconds, about 216 pages for the current CVE count) and takes at least about 25 minutes, realistically longer. A full run without a key was not executed. Until an NVD API key secret and a cached data directory are provided, `scripts/security-scan.sh` reports this check as `SKIPPED(no NVD_API_KEY)`. The Sonatype OSS Index analyzer is disabled without credentials. Since Phase 7 the plugin is applied in `services/api/build.gradle.kts`; `scripts/security-scan.sh` reports `SKIPPED(no NVD_API_KEY)` without a key and `FAIL` when a key is set but the plugin is missing.
- **WireMock Spring Boot integration** `wiremock-spring-boot` 4.4.3 with Boot 4.2: not tested. The decision in Deviations item 5 is applied: Phase 4 uses only the standalone server in test scope.
- **Dependabot with uv 0.13.9 lock files:** not tested (see Deviations).
- **Image digests:** resolved in Phase 7. External images are pinned by exact tag and index digest (`<image>:<tag>@sha256:<digest>`) in `infra/` and in the two service Dockerfiles (see "Added in Phase 7"). Docker resolves the digest, so a re-pushed tag can no longer change what is pulled.
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

### Added in Phase 2

| Item | Pin | Where pinned | Notes |
|---|---|---|---|
| `hypothesis` | 6.170.17 | `services/scoring/pyproject.toml` | dev dependency; property-based tests of the scoring engine (derandomised); PyPI release 2026-11-20 |
| `pytest-cov` | 7.1.0 | `services/scoring/pyproject.toml` | dev dependency; coverage gate of 95 % in the pytest options; PyPI release 2026-03-21 |
| `pyyaml` | 6.0.4 | `services/scoring/pyproject.toml` | dev dependency; reads the OpenAPI contract in the contract-equality tests; PyPI release 2026-11-20 |
| `types-pyyaml` | 6.0.12.20261122 | `services/scoring/pyproject.toml` | dev dependency; typing stubs for strict mypy; PyPI release 2026-11-22 |
| `coverage`, `sortedcontainers` | 7.17.5, 2.4.0 | `services/scoring/uv.lock` | transitive (`pytest-cov`, `hypothesis`); no direct pin |
| Runtime dependencies | unchanged | `services/scoring/pyproject.toml` | no new runtime dependency in Phase 2 |

### Added in Phase 3

| Item | Pin | Where pinned | Notes |
|---|---|---|---|
| `spring-boot-starter-validation` | managed by the Spring Boot 4.2.0 BOM | `services/api/build.gradle.kts` | hibernate-validator 9.1.6.Final as managed |
| `spring-boot-starter-actuator` | managed by the Spring Boot 4.2.0 BOM | `services/api/build.gradle.kts` | spring-boot-actuator 4.2.0, micrometer-core 1.18.0 as managed |
| `spring-boot-starter-oauth2-resource-server` | managed by the Spring Boot 4.2.0 BOM | `services/api/build.gradle.kts` | spring-security-oauth2-resource-server, -jose and -core 7.2.0; `nimbus-jose-jwt` 10.9.1 comes with Spring Security 7.2.0 (not a Boot BOM property) |
| `spring-boot-starter-mail` | managed by the Spring Boot 4.2.0 BOM | `services/api/build.gradle.kts` | jakarta.mail-api 2.1.5, Angus Mail 2.0.5 as managed |
| `spring-boot-starter-thymeleaf` | managed by the Spring Boot 4.2.0 BOM | `services/api/build.gradle.kts` | Thymeleaf and thymeleaf-spring6 3.1.7.RELEASE as managed |
| `com.github.ben-manes.caffeine:caffeine` | managed by the Spring Boot 4.2.0 BOM | `services/api/build.gradle.kts` | 3.3.1 as managed |
| `org.bouncycastle:bcprov-jdk18on` | 1.87 | `services/api/build.gradle.kts` | explicit, not BOM-managed; Maven Central latest release, jar dated 2026-11-11 |
| `com.bucket4j:bucket4j_jdk17-core` | 8.23.0 | `services/api/build.gradle.kts` | explicit, not BOM-managed; Maven Central latest release, jar dated 2026-11-22 |
| Structured JSON logging | Spring Boot 4.2.0 built-in (`logging.structured.format.console=logstash`) | `services/api/src/main/resources/application.yml` | no dependency added; logback-classic 1.6.6 as managed |

### Added in Phase 4

| Item | Pin | Where pinned | Notes |
|---|---|---|---|
| openapi-generator Gradle plugin `org.openapi.generator` | 7.27.0 | `services/api/build.gradle.kts` | generator `java`, library `restclient`, `useSpringBoot4`, `useJackson3`, `openApiNullable=false`; output under `build/generated/scoring-client` in its own source set (no `-Werror`, no Spotless, not in JaCoCo) |
| springdoc-openapi | `org.springdoc:springdoc-openapi-starter-webmvc-api:3.2.1` | `services/api/build.gradle.kts` | brings swagger-core 3.0.0; API document only, no UI |
| Resilience4j | `io.github.resilience4j:resilience4j-spring-boot4:2.4.0` | `services/api/build.gradle.kts` | context load and behaviour tests pass on Spring Boot 4.2.0 (configuration binding from `resilience4j.*`, registries, Micrometer state gauge) |
| WireMock | `org.wiremock:wiremock-standalone:4.0.0-beta.44` | `services/api/build.gradle.kts` | test scope; standalone server, no Spring integration |
| Testcontainers core (`org.testcontainers:testcontainers`) | managed by the Spring Boot 4.2.0 BOM | `services/api/build.gradle.kts` | test scope; 2.0.5 as managed; `GenericContainer` and `ImageFromDockerfile` start the real scoring service |
| Caffeine | managed by the Spring Boot 4.2.0 BOM | `services/api/build.gradle.kts` | 3.3.1 as managed; already present since Phase 3, now also used for the ranking and market-profile caches |

### Added in Phase 5

| Item | Pin | Where pinned | Notes |
|---|---|---|---|
| Mailpit as a test container | `axllent/mailpit:v1.34.0` | `services/api/src/test/java/com/vitadelta/api/alerts/AlertsIntegrationTest.java` | same tag as `infra/docker-compose.yml`; the alert e-mail tests deliver over real SMTP and read the message back through its HTTP API |
| Runtime and test dependencies | unchanged | `services/api/build.gradle.kts` | no dependency was added in Phase 5; the FX client uses the JDK HTTP client, and WireMock, Caffeine, Thymeleaf and Testcontainers were already pinned |

### Added in Phase 6

| Item | Pin | Where pinned | Notes |
|---|---|---|---|
| `@angular/router` | 22.3.0 | `apps/web/package.json` | dependency; same version as the other Angular packages; npm registry checked 2026-11-25 |
| `@angular/forms` | 22.3.0 | `apps/web/package.json` | dependency; reactive forms of the feature pages |
| `@vitest/coverage-v8` | 5.1.1 | `apps/web/package.json` | devDependency; peer `vitest` 5.1.1; line coverage gate of 70 % in `npm test` |
| `@types/node` | 22.20.5 | `apps/web/package.json` | devDependency; newest 22.x, matching the Node 22 engine; used by spec files and the e2e sources only (excluded from the application build) |
| `@playwright/test` | 1.65.1 | `apps/web/package.json` | devDependency; end-to-end suite (`npm run e2e`), the pin named in the table above; npm `latest` on 2026-11-25 |
| `@axe-core/playwright` | 4.14.0 | `apps/web/package.json` | devDependency; accessibility scans in the e2e suite; its `axe-core` resolves to 4.14.x through the lock file; npm `latest` is the 4.14.1 patch, left to Dependabot |
| Headless browser | Chrome Headless Shell 159.0.8154.6 (Playwright `chromium-headless-shell` build 1255) | installed by `npm run e2e:install` (`playwright install --only-shell chromium`) | no headed browser is downloaded or opened |
| `openapi-typescript` | 7.13.0 | `apps/web/tools/openapi/package.json` | isolated package with its own `package-lock.json`; generates `schema.d.ts` from `contracts/public-api.openapi.yaml` (`npm run api:types`, checked by `npm run api:check`) |
| `typescript` (generator package only) | 5.9.3 | `apps/web/tools/openapi/package.json` | `openapi-typescript` 7.13.0 declares the peer `typescript: ^5.x`, while the application stays on TypeScript 6.0.3 (Deviations, item 2); the two installs never share a `node_modules` |
| Application toolchain | unchanged | `apps/web/package.json` | Angular 22.3.0, TypeScript 6.0.3, Vitest 5.1.1, Transloco 8.4.0, rxjs, ESLint and Prettier keep their Phase 0 pins; no UI library or other runtime dependency was added |

### Added in Phase 7

Digests are the OCI index (multi-platform) digests of the exact tags, read with `docker buildx imagetools inspect <image>:<tag>` on 2026-11-25. Action commit SHAs were read with `gh api repos/<owner>/<repo>/git/ref/tags/<tag>` on the same day; every tag is a lightweight tag pointing to a commit.

| Item | Pin | Where pinned | Notes |
|---|---|---|---|
| nginx base image | `nginx:1.30.8-alpine@sha256:6437a63b5c529f0e06162b3f593c0154cff6fa2ea0f94fd7c27928cb399e022d` | `infra/nginx/Dockerfile` (stage `runtime`), `infra/nginx/check-routing.sh` (API stand-in) | runs as the image user `nginx` (uid 101), master included |
| Web build image | `node:22.23.5-alpine@sha256:1314fb30ec37592d5845e5a160d522c0e680972aa534549940b8d73bde88481a` | `infra/nginx/Dockerfile` (stage `build`) | |
| PostgreSQL | `postgres:18.7-alpine@sha256:0115267ab5a509022df9319c77be365cef335adb71556c75f60f5360ee247e71` | `infra/docker-compose.yml`, `infra/docker-compose.restore.yml` | the API Testcontainers tests still name the tag only (test scope) |
| Mailpit | `axllent/mailpit:v1.34.0@sha256:23a2ef85bc50853ab3093645d1d64679afd8887e89da5e48a88940c172591a4e` | `infra/docker-compose.yml`, `infra/docker-compose.restore.yml` | |
| API build / runtime images | `eclipse-temurin:25.0.5.1_2-jdk-noble@sha256:c7eef3dfeb81468533ea48c67bddaa8c7ca916454259e4d0d734d12bcff8e125`, `eclipse-temurin:25.0.5.1_2-jre-noble@sha256:2ec98aa522c472deadfd2b7c5b39bca680e33ef522a53a72f10789bdf89a6bb5` | `services/api/Dockerfile` | |
| Scoring images | `python:3.14.10-slim-trixie@sha256:22eafb370920ad8b429e0aec3fff433e0669968519f5d60469d83729c138b28b`, `ghcr.io/astral-sh/uv:0.13.9@sha256:d3b601f0c7b118bc1753febf7c41cc80981df678445e0254284eecf84db79a7d` | `services/scoring/Dockerfile` | |
| `actions/checkout` | v7.0.1 = `3d3c42e5aac5ba805825da76410c181273ba90b1` | `.github/workflows/ci.yml` | |
| `actions/setup-java` | v6.3.0 = `d1374f8ac74474200d16ef8ecee22c7ae7a51880` | `.github/workflows/ci.yml` | the job used the floating `v6`, which pointed to v6.3.0 |
| `actions/setup-node` | v7.1.0 = `949feb2413d6458794dcd2491c4babbbce0c15c1` | `.github/workflows/ci.yml` | |
| `astral-sh/setup-uv` | v10.4.0 = `1db35dd8eee11c5894872e0b383ae6c406cf738d` | `.github/workflows/ci.yml` | |
| `actions/upload-artifact` | v7.0.2 = `cf430e030ddbb5b0abf93d22962f4752f3646cd9` | `.github/workflows/ci.yml` | |
| OWASP Dependency-Check Gradle plugin | `org.owasp.dependencycheck` 13.0.0 | `services/api/build.gradle.kts` | Gradle Plugin Portal checked 2026-11-25 (latest release); fails at CVSS >= 7 on `runtimeClasspath`; not part of `check`, run by `scripts/security-scan.sh` only when `NVD_API_KEY` is set (see Unverified) |
| gitleaks (CI) | 8.30.1, `gitleaks_8.30.1_linux_x64.tar.gz` sha256 `551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb` | `.github/workflows/ci.yml` (`security` job), checked by `scripts/security-scan.sh` | GitHub release checked 2026-11-25 (latest) |
| pip-audit | 2.10.1 | `scripts/security-scan.sh` | PyPI latest on 2026-11-25 |
| age | 1.3.2 or newer (host tool, not pinned in the repository) | `scripts/backup.sh`, `scripts/restore.sh` | GitHub release v1.3.2 (2026-08-29) used for the restore drill |
| pytest for the security-tool tests | 9.2.0 | `scripts/test-all.sh` | same as the other script tests |
| Validation only (not in the repository) | actionlint 1.7.12 (`rhysd/actionlint:1.7.12`), shellcheck 0.11.0 (`koalaman/shellcheck:v0.11.0`) | | used to validate the workflow and the scripts |

How the pins are updated:

- **Images.** Dependabot (`.github/dependabot.yml`: ecosystem `docker` for `/infra/nginx`, `/services/api` and `/services/scoring`, ecosystem `docker-compose` for `/infra`) proposes a new tag together with its digest. By hand: `docker buildx imagetools inspect <image>:<new tag>` prints `Digest: sha256:...` (the index digest); replace tag and digest together, then run `scripts/dev-up.sh` and `infra/nginx/check-routing.sh`. A digest is never updated alone for the same tag without checking why the tag moved.
- **Actions.** Dependabot (`github-actions`) moves SHA and version comment together. By hand: `gh api repos/<owner>/<repo>/git/ref/tags/<tag>`; when `.object.type` is `tag` (an annotated tag), dereference it with `gh api repos/<owner>/<repo>/git/tags/<sha>` and use that object's commit SHA. `scripts/security-scan.sh` (check `actions-pinned`) fails on any action reference that is not `<owner>/<repo>@<40 hex> # v<x.y.z>`.

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
