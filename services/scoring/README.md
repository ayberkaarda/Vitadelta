# Vitadelta scoring service

Stateless Python service that scores supplements by daily nutrient coverage and daily cost,
ranks a candidate set and produces the pairwise marginal verdict ("is the more expensive
product worth it?"). It implements section 6 of the project specification; its HTTP
interface is defined by [`contracts/scoring-api.openapi.yaml`](../../contracts/scoring-api.openapi.yaml)
and the decisions behind it are recorded in ADR-0002 (scoring in Python, stateless, no
database, no outbound calls, no currency conversion) and ADR-0004 (reference resolution,
utility ceiling and upper-limit rules).

Every request carries everything the engine needs (registry block, reference values, upper
limits, weights, market profile and candidates). The engine is a set of pure, deterministic
functions: no randomness, no clock, no I/O, so equal inputs give byte-identical JSON.

## Layout

| Path | Content |
|---|---|
| `src/vitadelta_scoring/engine/` | Pure engine (no HTTP imports): `model` (inputs, outputs, enums), `numeric` (epsilon rule, median, sort keys), `references` (reference resolution, UL matching), `utility`, `scoring` (normalisation, rows, flags, filters, display, ranking), `compare` (pairwise analysis), `validation` (semantic rules), `serialise` (JSON-ready output) |
| `src/vitadelta_scoring/api/` | Thin HTTP layer: Pydantic models mirroring the contract, problem responses, request ids and JSON logging, FastAPI routes |
| `src/vitadelta_scoring/config.py` | Immutable engine configuration (`EngineConfig`) |
| `src/vitadelta_scoring/main.py` | ASGI entrypoint `vitadelta_scoring.main:app` |
| `scripts/fixture_market.py` | Builds scoring inputs for a country from `data/` (used by the calibration and the data tests) |
| `scripts/calibrate_verdicts.py` | Verdict calibration over the fixture market |
| `calibration/` | Committed calibration result (`verdict-calibration.md`, `verdict-calibration.json`) |
| `tests/` | Golden tests, property tests, rules, HTTP layer, contract equality, calibration; `tests/data/` (data-layer suite, read-only over `data/`); `tests/perf/` (performance budget) |

## Running and testing

`uv` is required; dependencies are locked in `uv.lock`.

```bash
cd services/scoring
uv sync --locked
uv run uvicorn vitadelta_scoring.main:app --host 127.0.0.1 --port 8000

uv run ruff check .
uv run ruff format --check .
uv run mypy            # strict, over src, tests and scripts
uv run pytest          # all tests; fails below 95 % line coverage
uv run pytest -m perf -s   # only the performance budget, printing the measured p95
uv run python scripts/calibrate_verdicts.py          # regenerate calibration/
uv run python scripts/calibrate_verdicts.py --check  # verify calibration/ is current
```

The container image installs from the lock file without development dependencies
(`uv sync --locked --no-dev`) and runs as an unprivileged user on port 8000:

```bash
docker build -t vitadelta-scoring services/scoring
```

`VITADELTA_PERF_FACTOR` (a number >= 1, default 1) scales the 200 ms performance budget of
`tests/perf/test_score_perf.py` for slower machines such as shared CI runners; the assertion
itself always runs. `VITADELTA_DATA_ROOT` redirects the data directory used by the data tests,
the fixture loader and the calibration.

## Endpoints

Base path `/internal/v1`; the generated OpenAPI document is served at
`/internal/v1/openapi.json` and is semantically equal to the contract (checked by
`tests/test_contract.py`). Interactive documentation pages are disabled.

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | `{"status": "UP", "config": {...}}` with the engine configuration in effect |
| POST | `/score` | Score, filter and rank a candidate set (`ScoreRequest` -> `ScoreResponse`) |
| POST | `/compare` | Pairwise marginal analysis (`CompareRequest` -> `CompareResponse`) |

## Configuration

`EngineConfig` is immutable; `/health` reports it.

| Key | Default | Meaning |
|---|---|---|
| `diminishing_beta` | 0.35 | slope `beta` of the logarithmic utility segment (> 0) |
| `utility_ceiling` | 1.5 | utility ceiling `u_max` (>= 1) |
| `comparison_epsilon` | 1e-9 | relative tolerance of the epsilon equality rule (> 0) |
| `verdict_worth_it_max_ratio` | 1.0 | largest ratio that yields `WORTH_IT` (> 0) |
| `verdict_marginal_max_ratio` | 1.5 | largest ratio that yields `MARGINAL` (>= the previous key) |

Fixed engine constants: market-median minimum of 3 products, presence-only utility 0.5,
presence floor fraction 10 %, market-rate minimum of 3 products, default `lambda` 0.5,
5 top contributors, sort keys rounded to 10 decimals.

## Formulas

### Serving and cost normalisation (6.2)

```
servings_per_container = units_per_container / units_per_serving
days_supply            = servings_per_container / servings_per_day
cost_per_day           = effective_price / days_supply
amount_per_day(n)      = amount_per_serving(n) * servings_per_day
ul_amount_per_day(n)   = ul_amount_per_serving(n) * servings_per_day   (amount_per_day when not set)
```

Constraints: `units_per_serving >= 1`, `units_per_container >= units_per_serving`,
`0 < servings_per_day <= 99.99`, `effective_price > 0`, `amount_per_serving > 0`,
`0 <= ul_amount_per_serving <= amount_per_serving`.

### Reference resolution (6.3)

Per nutrient `n` and request, first match wins:

1. **REGULATORY**: `n` has a row in `reference_values` -> `ref = amount`.
2. **MARKET_MEDIAN**: otherwise, if at least 3 products of `market_profile` contain `n`
   -> `ref = median(amount_per_day(n))` over those products. Products are de-duplicated by
   `product_id` (first occurrence wins) and a nutrient counts once per product.
3. **PRESENCE_ONLY**: otherwise there is no reference and no coverage ratio; `u = 0.5` when
   `amount_per_day >= presence_floor_per_day / 10` or the floor is unknown, else `u = 0` and
   the row is `below_presence_floor`.

Median: values sorted ascending; the middle value for an odd count, the mean of the two
middle values for an even count, computed overflow-safely (`a / 2 + b / 2` when `a + b`
is not finite).

### Upper limits (5.7, 6.4)

UL matching over the request's `upper_limits`, the same rule for every set:

1. form not `UNKNOWN`: look up `(n, form)`;
2. else, or if absent: look up `(n, ANY)`;
3. still absent and the set has a form-specific row for `n`: no UL, flag `UL_FORM_DEPENDENT`;
4. otherwise no UL. A matched exempt row (amount, unit and basis null) means no UL and no flag.

Both `TOTAL` and `SUPPLEMENTAL` limits are applied to the supplement's UL-relevant daily
amount (dietary intake ignored; permissive for `TOTAL` limits).

- `EXCEEDS_UL` <=> a UL applies and `ul_amount_per_day > UL`, for every reference source.
- When a reference exists and the UL is exceeded, the creditable amount is
  `min(amount_per_day, UL + (amount_per_day - ul_amount_per_day))` and `capped_at_ul = true`
  when it is below `amount_per_day`. Amount above the UL never adds score.
- `ul_below_reference = true` when a UL applies and `UL < ref` (for example EFSA magnesium
  250 mg against an NRV of 375 mg).

### Coverage ratio and utility (6.4)

```
r    = creditable_amount / ref
u(r) = r                              for 0 <= r <= 1
u(r) = min(1 + beta * ln(r), u_max)   for r > 1
```

`capped_at_u_max = true` when `1 + beta * ln(r) > u_max`. The ceiling is reached at
`r = exp((u_max - 1) / beta)`, about 4.17 with the defaults. `u` is continuous,
non-decreasing, `u(0) = 0`, `u(1) = 1`, concave for `r > 1` and `u <= u_max`
(property-tested).

### Content score (6.5)

```
w_n = category_weight[category(n)] * override[n]      (override default 1.0)
C   = sum over the product's nutrients of w_n * u_n   (exactly rounded sum, math.fsum)
content_score_display = 100 * (C / max C)             over the surviving set; 0 when max C = 0
```

Category weights are in `[0, 2]` (defaults VITAMIN 1.0, MINERAL 1.0, COENZYME 0.8,
OTHER 0.5), overrides in `[0, 3]`. A weight of 0 keeps the row in the breakdown with
contribution 0. Weights that make every registry nutrient 0 are rejected
(`weights_all_zero`).

### Value score (6.6)

```
V = C / cost_per_day                                   (per unit of the native currency)
value_score_display = 100 * (V / max V)                over the surviving set; 0 when max V = 0
```

### Pre-scoring filters, ranking and meta (6.7)

Filters, in order, counted in `meta.excluded`: `required_nutrients` (present, and the
literal comparison `amount_per_day >= min_amount_per_day` when a minimum is given),
then `exclude_exceeding_ul` (drops products with `EXCEEDS_UL`).

| mode | primary | tie-break 1 | tie-break 2 |
|---|---|---|---|
| `PRICE` | `cost_per_day` ascending | `C` descending | `product_id` ascending |
| `RICHNESS` | `C` descending | `cost_per_day` ascending | `product_id` ascending |
| `VALUE` (default) | `V` descending | `C` descending | `product_id` ascending |
| `CUSTOM` | `S` descending | `C` descending | `product_id` ascending |

`S = lambda * minmax(C) + (1 - lambda) * minmax(-cost_per_day)` over the surviving set,
`lambda` in `[0, 1]` (default 0.5, only valid in `CUSTOM`: `lambda_requires_custom`);
`minmax` returns 1 for every product only when all values are exactly equal (no epsilon). Numeric sort keys are
rounded to 10 decimals; `product_id` is compared in binary order, so the order is total.

`meta.market_size` is the de-duplicated candidate count before the filters;
`meta.m_ref` is the median of `cost_per_day / C` over those candidates with `C > 0`, or
null with fewer than 3 such products.

### Pairwise marginal analysis (6.8)

- Market: `market[]` plus baseline and candidate, de-duplicated by `product_id` (the
  request's baseline and candidate win); `market_size` is its size.
- Epsilon rule: `x == y` <=> `|x - y| <= epsilon * max(1, |x|, |y|)`; "> 0" means greater
  than 0 and not equal to 0 under this rule.
- Ordering: A is the cheaper product by `cost_per_day`; on equal cost the lower `C`; on equal
  `C` the lower `product_id`. `swapped = true` when A is not the request's baseline, so the
  result does not depend on request order.
- `delta_cost = cost_per_day_B - cost_per_day_A`, `delta_content = C_B - C_A`; each is
  exactly 0 when the two values are equal under the epsilon rule.
- `m_ref` = median of `cost_per_day / C` over market products with `C > 0`; with fewer
  than 3 such products `cost_per_day_A / C_A` when `C_A > 0`, else null.
- `marginal_rate = delta_cost / delta_content`, `ratio = marginal_rate / m_ref`; both null
  unless `delta_cost > 0`, `delta_content > 0` and `m_ref` is not null.

Verdict, first matching row wins:

| condition | verdict |
|---|---|
| `delta_cost == 0` and `delta_content == 0` | `EQUIVALENT` |
| `delta_cost == 0` and `delta_content > 0` | `DOMINATES` |
| `delta_content <= 0` | `NOT_WORTH_IT` |
| `m_ref` is null | `INSUFFICIENT_DATA` |
| `ratio <= verdict_worth_it_max_ratio` | `WORTH_IT` |
| `ratio <= verdict_marginal_max_ratio` | `MARGINAL` |
| otherwise | `NOT_WORTH_IT` |

Per-nutrient delta rows cover the union of A's and B's nutrients, sorted by code; an absent
nutrient has amount 0, utility 0 and ratio 0 (null under PRESENCE_ONLY);
`delta_contribution = w * (u_B - u_A)`. Row flags (none when `delta_amount = 0`):
`NEW_IN_B` (absent in A, present in B), `REMOVED_IN_B` (present in A, absent in B),
`DIMINISHING` (`delta_amount > 0` and `r_A >= 1`), `ABOVE_CEILING_NO_CREDIT`
(`delta_amount > 0` and B's row is `capped_at_u_max`), `ABOVE_UL_NO_CREDIT`
(`delta_amount > 0`, a UL applies and B's UL-relevant amount exceeds it).

`explanation` is an i18n key (`explain.verdict.<verdict>`) with parameters `currency`,
`deltaCostPerDay`, `deltaContent`, `marginalRate`, `mRef` and `ratio`; null parameters are
omitted. Verdicts are indicative.

### Product flags (6.9) and payload (6.10)

Flags, in this order and each at most once, with sorted nutrient codes where applicable:
`EXCEEDS_UL`, `UL_FORM_DEPENDENT`, `CONVERSION_ASSUMED` (from the candidate's
`conversion_assumed`), `PRICE_STALE` (the offer is stale or out of stock), `OUT_OF_STOCK`,
`MARKET_MEDIAN_REFERENCE_USED`, `PRESENCE_ONLY_NUTRIENTS`, `BELOW_PRESENCE_FLOOR`.
Breakdown rows are sorted by code; `top_contributors` are the first five rows by
contribution descending, ties by code.

## Numerical rules

- IEEE-754 doubles throughout, natural logarithm, no `Decimal` in the hot path.
- Outputs are unrounded; rounding happens once, in the core API's response assembly.
- JSON numbers are the shortest round-trip representation of each double. Inputs whose
  derived daily values would overflow are rejected (`out_of_range`), derived values that
  are not representable are rejected (`value_out_of_range`), so no non-finite number is
  ever produced.
- Request integers follow JSON Schema: `60.0` is the integer 60; `60.5`, strings and
  booleans are invalid. Numbers are never parsed from strings.
- Sorting is stable and total, keys are rounded to 10 decimals, and the output key order is
  the contract's property order, so equal inputs give byte-identical JSON regardless of
  candidate order.
- Performance budget: 500 candidates x 40 nutrients in under 200 ms p95 through the ASGI
  application (`tests/perf/test_score_perf.py`, fixed seed). The entrypoint raises the
  garbage collector's young-generation threshold because one large request allocates a few
  hundred thousand short-lived objects.

## Error model

Errors are RFC 9457 `application/problem+json` documents with `type`
(`https://vitadelta.dev/problems/<code>`), `title`, `status`, `detail`, `instance` and
`requestId`.

- `422 validation_failed` with `errors[]: {field, code, message}`; `field` is the JSON path
  of the offending value (for example `candidates[0].nutrients[1].unit`). Every violation of
  a request is reported at once. Codes: `required`, `invalid_type`, `out_of_range`,
  `invalid_format`, `unknown_field`, `unknown_nutrient_code`, `unit_mismatch`,
  `duplicate_nutrient`, `duplicate_registry_code`, `container_smaller_than_serving`,
  `ul_amount_exceeds_amount`, `currency_mismatch`, `lambda_requires_custom`,
  `weights_all_zero`, `same_product`, `duplicate_product_id`, `value_out_of_range`.
- Duplicate `product_id`s in `candidates[]`, `market[]` (including baseline and candidate)
  or `market_profile.products[]` are de-duplicated when their content is identical and
  rejected with `duplicate_product_id` when it differs, so results never depend on order.
- A derived value that cannot be represented as a finite double (a coverage ratio, a market
  rate, a marginal rate or a ratio) is rejected with `value_out_of_range`; no NaN or
  infinity is ever returned.
- `404 not_found`, `405 method_not_allowed` (with `Allow`) and other framework HTTP errors
  are problem documents too; a POST whose `Content-Type` is not `application/json` is
  `415 unsupported_media_type`.
- `500 internal_error` for anything unexpected; the response carries no internal detail and
  the error is logged with its traceback.

`X-Request-Id` is accepted when it matches `^[A-Za-z0-9-]{1,64}$`; otherwise, or when it is
absent, a new UUID is generated. The id is returned in the `X-Request-Id` response header of
every response, in `requestId` of every problem, and in every log line. Logs are one JSON
object per line on standard output (`timestamp`, `level`, `logger`, `message`,
`request_id`, plus `method`, `path`, `status` and `duration_ms` for the per-request line).

## Calibration

`scripts/calibrate_verdicts.py` compares every same-country pair of fixture products with an
in-stock offer (TR before and after the price-drop file, DE, GB, US, and the US again with
the real label snapshot and its demo prices), reports the ratio distribution and the share of
each verdict, and lists every pair whose verdict flips when one of
`verdict_worth_it_max_ratio`, `verdict_marginal_max_ratio` or `utility_ceiling` moves by
+-20 %. The result and its proposal are in [`calibration/verdict-calibration.md`](calibration/verdict-calibration.md);
the defaults are not changed by the script, a change is an owner decision.
