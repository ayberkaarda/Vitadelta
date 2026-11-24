# Phase 2 report: scoring engine

Date: 2026-11-24. Branch: `feat/phase-2-scoring-engine`.

## 1. Completed

- Complete scoring engine in `services/scoring`: `POST /internal/v1/score`, `POST /internal/v1/compare`, `GET /internal/v1/health`.
- Pure engine package (no web framework imports): normalisation, reference resolution (regulatory, market median, presence only), utility with diminishing returns and ceiling, upper-limit matching and cap, content and value scores, ranking modes with deterministic tie-breaks, pre-scoring filters, pairwise marginal analysis with verdicts, row flags and explanation keys, product flags, explainability payload.
- Thin API layer: request and response models that mirror the contract, RFC 9457 problem responses, request id handling, structured JSON logs.
- Tests: golden tests GT-1 to GT-9, property tests, rule tests, contract equality, performance budget, fixture market bound, calibration check.
- Verdict calibration script and its committed result (`services/scoring/calibration/`).
- `services/scoring/README.md` with the formulas restated.

## 2. Files by area

| Area | Files |
|---|---|
| Scoring | `src/vitadelta_scoring/engine/*`, `src/vitadelta_scoring/api/*`, `main.py`, `config.py`, `scripts/calibrate_verdicts.py`, `scripts/fixture_market.py`, `calibration/verdict-calibration.{md,json}`, `README.md`, `Dockerfile`, `pyproject.toml`, `uv.lock` |
| Scoring tests | `tests/test_golden.py`, `test_properties.py`, `test_rules.py`, `test_api.py`, `test_contract.py`, `test_review_fixes.py`, `tests/perf/*`, additions to `tests/data/*` |
| Shared files | `contracts/scoring-api.openapi.yaml`, `.github/workflows/ci.yml` (performance factor for shared runners), `README.md`, `README.tr.md`, `docs/adr/0005-version-pins.md` |

## 3. Tests and checks

| Command | Result |
|---|---|
| `uv run pytest` | 369 passed; line coverage 99.30 % (gate 95 %, enforced by the pytest configuration) |
| `uv run ruff check .`, `uv run ruff format --check .`, `uv run mypy` | clean (strict typing) |
| Performance: 500 candidates x 40 nutrients through the application | p95 about 145 ms against a 200 ms budget (engine alone about 63 ms) |
| Contract lint (Redocly 2.83.0) | valid, 0 errors |
| Contract equality test | the served OpenAPI document equals the contract (operations, schemas, required lists, enums, bounds) |
| Calibration `--check` | committed result reproduced byte for byte |
| Container image | builds; a GT-1 request returns GOLD-A at rank 1 |

Golden values (computed, all equal to the specification within tolerance):

| Test | Result |
|---|---|
| GT-1 | C 4.9719 / 5.0093, V 0.3729 / 0.3340, marginal rate 44.58, ratio 16.62, NOT_WORTH_IT; ranks per mode as specified; swapped request returns the same payload with `swapped = true` |
| GT-2 | u(ZINC) 1.3207, EXCEEDS_UL, excluded count 1 with the filter |
| GT-3 | market median 100; contributions 0.9941 and 0.24; presence-only 0.4; floor variant flagged |
| GT-4 | EQUIVALENT and DOMINATES in both request orders; floating-point equal cost handled |
| GT-5 | median 150; utilities 0.2000, 0.6667, 1.1007, 1.2426 |
| GT-6 | CUSTOM ranks for lambda 0.5, 1 and 0 as specified |
| GT-7 | C 3.3521 with EXCEEDS_UL for niacin and UL_FORM_DEPENDENT for folate under IOM; neither under EFSA |
| GT-8 | zero content handled; INSUFFICIENT_DATA; all-zero weights rejected |
| GT-9 | unit mismatch rejected with 422 |

Independent verification: a separate reference implementation written from the specification agreed with the service on 117 golden checks and on 800 seeded random requests (0 mismatches); 200 shuffled requests returned byte-identical bodies; extreme valid inputs produced no NaN, Infinity or server error; ten rule mutations were tried on a copy and nine were caught by tests.

An independent static review and the verification produced the following confirmed items; each got a regression test that failed first, then the fix:

| Finding | Fix |
|---|---|
| Even-count median of very large values overflowed | overflow-safe midpoint |
| Rates and coverage ratios could overflow to Infinity or NaN | such requests are rejected with 422 `value_out_of_range`; a property test asserts every output is finite |
| Conflicting duplicate product ids made results depend on input order | rejected with 422 `duplicate_product_id`; exact duplicates are still merged |
| CUSTOM min-max treated nearly equal values as equal | exact equality only |
| Required-nutrient minimum used the comparison epsilon | literal `>=` as specified |
| Null `min_amount_per_day` or `unit` accepted; integral floats rejected for integer fields | aligned with the contract |
| 404, 405 and wrong content type did not return problem responses | problem responses for all three (415 for a non-JSON content type) |
| Three boundaries had no test (amount equal to the UL, coverage exactly 1 with a positive delta, UL equal to the reference) | tests added |

Two review remarks were not changed because the implementation follows the specification: a content score is treated as positive under the comparison epsilon when the market rate is computed, and `capped_at_ul` is not reported for presence-only nutrients.

## 4. Deviations and assumptions

- `/score` reports `meta.m_ref` as null when fewer than three products have a positive content score (the compare fallback needs a baseline product, which a ranking does not have).
- Comparing a product with itself is rejected with 422 `same_product`.
- Requests are typed strictly: a number sent as a string is rejected.
- `PRICE_STALE` is set when the request marks the price stale or the product out of stock, as described in the contract.
- The presence-floor check is computed as `amount < floor / 10`, which is the exact form of the documented 10 % rule.
- The young-generation garbage-collector threshold is raised at start-up to meet the performance budget with a margin.
- The container runs the server without its default access log; the service writes its own access line with the request id.
- Exempt upper-limit rows are sent to the service with amount, unit and basis all null.

## 5. Unverified or skipped

- The performance budget is measured on a developer machine; in CI the budget is multiplied by a factor of 3 because shared runners are slower.
- Presence floors are empty in the reference data, so the presence-floor path is exercised by tests only.

## 6. Decisions for the owner

Verdict calibration (pairs within one country, default weights):

| Market | WORTH_IT | MARGINAL | NOT_WORTH_IT |
|---|---|---|---|
| TR | 28.8 % | 3.0 % | 68.2 % |
| DE | 11.1 % | 11.1 % | 77.8 % |
| GB | 27.8 % | 16.7 % | 55.6 % |
| US (synthetic) | 10.9 % | 1.8 % | 87.3 % |
| US with the label snapshot | 24.1 % | 4.6 % | 71.3 % |

Changing either threshold by 20 % flips at most four pairs in a synthetic market (at most 11.1 % of the pairs of the smallest market) and at most 3.2 % of the pairs in the US market with real labels; changing `u_max` by 20 % flips at most 3.2 %. GOLD-A against GOLD-B stays NOT_WORTH_IT under every variant (ratio 21.9, and 8.9 after the price drop); the TR market rate is 2.04, inside the bound of 10.00.

Proposal: keep `verdict_worth_it_max_ratio = 1.0`, `verdict_marginal_max_ratio = 1.5` and `u_max = 1.5`. The decision is the owner's; nothing was changed.

## 7. Proposed scope changes

None.

## 8. Branch, commits and contract changes

- Branch `feat/phase-2-scoring-engine`; small Conventional Commits, one logical change each.
- Contract changes in `contracts/scoring-api.openapi.yaml` (all backward compatible, examples and descriptions only):
  - the GT-1 request example lists `VIT_A` in its registry block, because its upper-limit rows use it;
  - the GT-1 score response example has `meta.m_ref` null (two products only);
  - the validation error codes list gains `same_product`, `duplicate_product_id` and `value_out_of_range`.

## 9. Next phase

Phase 3, identity, plans and entitlement engine: the identity migration, authentication flows, plans and subscriptions, promo codes, e-mail port with the SMTP adapter, rate limits, admin bootstrap and role checks. It needs the owner's confirmation of the PRO list price and the FREE limits at its gate.

## 10. Awaiting approval

No force-push or rewrite of pushed history has happened. Merging requires a green CI run. Work stops before Phase 3.
