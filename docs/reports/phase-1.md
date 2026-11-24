# Phase 1 report: reference data and catalog domain

Date: 2026-11-24. Branch: `feat/phase-1-catalog-data`.

## 1. Completed

- Reference data under `data/reference/` with a citation for every row group: nutrient registry (38 codes), form registry (21), unit conversions (11), reference sets (2) and values (54), upper-limit sets (2) and limits (43), countries (4), common password list (10,001 lines).
- Synthetic fixtures under `data/fixtures/`: 26 products from five fictional brands, 67 offers, and the price-drop offers file; every coverage rule of the fixture specification holds.
- Real US label snapshot under `data/real/` (scope extension, ADR-0012): 84 on-market multivitamin and mineral products from 75 brands taken from the NIH Dietary Supplement Label Database, with provenance per product and 144 demo offers (synthetic prices, clearly labelled). Converter and mapping tables under `scripts/dsld/`.
- Catalog schema `V1__catalog.sql`, JPA entities and read-only repositories, `CatalogWriteTransaction` with exactly one `catalog_version` bump per committing transaction, unit conversion service, idempotent seed loader (profile `seed`), `scripts/seed.sh`.
- Data integrity suite (pytest, 109 tests) and API suite (455 tests) including fixture integrity tests.

## 2. Files by area

| Area | Files |
|---|---|
| Data | `data/reference/*`, `data/fixtures/*`, `data/real/*` |
| Data tooling | `scripts/dsld/dsld_snapshot.py`, mapping tables, `scripts/dsld/tests/*` |
| Data tests | `services/scoring/tests/data/*` (plus dev dependencies in `pyproject.toml`, `uv.lock`) |
| API | `db/migration/V1__catalog.sql`, packages `catalog`, `catalog/unit`, `catalog/seed`, `imports`, `common/csv`, `config/ClockConfig`, `application-seed.yml`, build file |
| Shared files | `scripts/seed.sh` (new), `scripts/test-all.sh` (API suite requires Docker; converter tests added), `infra/docker-compose.yml` (read-only data mount for the API), `README.md`, `README.tr.md`, `.gitignore`, `docs/adr/0005-version-pins.md`, `docs/adr/0012-real-label-data-source.md`, `contracts/schemas/import-products.schema.json` |

Shared-file changes requested by the API work and applied separately: the Docker prerequisite in `scripts/test-all.sh`, the seed section in both READMEs. `SecurityConfig` became conditional on a servlet web application so the seed profile can start without a web context; web behaviour is unchanged.

## 3. Tests and checks

| Command | Result |
|---|---|
| `./gradlew check --rerun-tasks` (services/api) | BUILD SUCCESSFUL; 455 tests, 0 failures; line coverage 94.74 % (gate 85 %) |
| `uv run pytest` (services/scoring) | 114 passed (109 data tests, 5 service tests); ruff, format check and strict mypy clean |
| converter tests | 5 passed |
| `scripts/dev-up.sh`, then `scripts/seed.sh` twice | both runs exit 0; identical row counts (product 110, product_nutrient 1568, product_availability 135, offer 211, reference tables as listed above); identical ordered id checksums; `catalog_version` +1 per run; second run changes no row |
| Direct bad writes in SQL | rejected by the database: wrong offer currency, `javascript:` URL, malformed product code, zero amount, second `catalog_version` row, duplicate offer key, currency change while offers exist |
| Mutation checks on a copy | removing, moving or doubling the version bump, dropping the currency trigger, dropping the form-sentinel CHECK, changing the vitamin D IU factor, inserting instead of upserting: each is caught by at least one test |
| Converter determinism | two offline runs give byte-identical outputs |

An independent static review produced eleven findings; all were confirmed, each got a regression test that failed first, and all are fixed:

| Finding | Fix |
|---|---|
| Concurrent replace-set writes could merge sets | owning product rows are locked before the sets are read |
| Repositories exposed mutating methods that bypass the version bump | catalog repositories are read-only; a test enforces it |
| IU acceptance depended only on conversion data | fixed allow-list in SQL CHECKs and in Java |
| Currency invariant bypassable under REPEATABLE READ | composite foreign key offer(country, currency) to country(code, currency) |
| Same offer pair defined in JSON and CSV made ids unstable | rejected during validation |
| Numeric CHECKs accepted NaN | NaN excluded on every numeric domain column |
| Offer URL without a host accepted | host required in Java and SQL |
| Oversized `schema_version` and explicit nulls accepted | exact comparison; nulls only where the schema allows; parity test against a JSON Schema validator |
| Vitamin A partial split could understate the UL-relevant amount | reduced only when the rest is stated as beta-carotene |
| Folate split in another mass unit was dropped | converted to the unit of the total |

No snapshot product changed after the two converter fixes.

## 4. Deviations and assumptions

- EFSA fluoride: the adult upper limit of 7 mg is not seeded. EFSA's 2025 assessment no longer sets an adult UL (only a safe level of 3.3 mg per day, which is not a UL).
- IOM chloride: seeded at 3600 mg per day (NASEM 2019).
- IOM vitamin E rows are seeded as planned (ANY 495 mg, natural 1000 mg, synthetic 495 mg, supplemental basis); the IU equivalents follow from the report's own conversion factors. For all-rac tocopheryl acetate the mass limit would correspond to 450 mg activity; noted in the data README.
- The IU conversion factors are cited from the FDA unit-conversion guidance of August 2019; 21 CFR 101.9 and 101.36 do not contain them.
- `presence_floor_per_day` is empty for all nutrients: no primary source for typical supplemental amounts could be verified.
- The seed runs as one catalog write transaction (reference data, fixtures, label snapshot, offers): one version bump per run. All files are validated first; any error writes nothing.
- `unit_conversion` has a generated column carrying the nutrient foreign key because generic rows use `*`.
- The `note` column of `upper_limits.csv` is validated but not stored (no column in the data model).
- Real label data: US only, source `DSLD`, label id in `source_ref`, entry date stored; prices are demo values. The rules "no real brand names" and "no upper limit exceeded" apply to synthetic fixtures only. Brand and product names are used for identification only; the project is not affiliated with or endorsed by the brands or by NIH.
- The common password list is the unchanged public list; it contains ordinary words and names.
- The fixture market-rate bound needs the scoring engine and is tested in Phase 2.

## 5. Unverified or skipped

- Licence of the label database: stated as CC0 1.0 in the API specification; the website's terms pages could not be read. Confirmation with NIH (ODScomments@mail.nih.gov) is an optional follow-up for the owner.
- Label database rate limits: not documented in responses; 152 requests were made in total.
- Presence floors (see above).
- 51 of 135 candidate labels were skipped with a recorded reason (non-integer or ranged serving sizes, unsupported IU form, missing daily servings, too few mapped nutrients, inconsistent container counts).

## 6. Decisions for the owner

- Optional licence confirmation for the label snapshot.
- Whether a form registry table should be added for the import pipeline in Phase 4 (today the seed validates forms from `forms.csv` in memory).
- Whether upper-limit citation notes should be stored in the database.
- Adding `invalid_csv` to the offers CSV error codes in the contract (file-level parse errors).

## 7. Proposed scope changes

- Not applied: per-country real label data for TR, DE and GB would need a comparable public source; none was evaluated.

## 8. Branch, commits and contract changes

- Branch `feat/phase-1-catalog-data`; small Conventional Commits, one logical change each.
- Contract change: `contracts/schemas/import-products.schema.json` gains the optional product properties `source_ref` and `source_entry_date` (reason: provenance of real label data; backward compatible).

## 9. Next phase

Phase 2, scoring engine: complete scoring service with golden tests, property tests, contract equality, performance budget and the verdict calibration over the fixture market and the US label snapshot.

## 10. Awaiting approval

No force-push or rewrite of pushed history has happened. Merging requires a green CI run.
