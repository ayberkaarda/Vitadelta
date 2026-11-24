# ADR-0004: Reference resolution hierarchy, utility ceiling and upper-limit application

- Status: Accepted
- Date: 2026-11-24

## Context

The score of a supplement depends on how much of each nutrient it supplies relative to a reference value. Regulatory reference values (for example NRVs) do not exist for every nutrient. Very high doses should not dominate the score, and amounts above a tolerable upper limit (UL) must never add value. UL tables contain supplemental-only limits, total-intake limits and form-specific limits, and some supplemental limits are lower than the regulatory reference. These rules need one explicit definition shared by the scoring service, the API and the UI copy.

## Decision

**Reference resolution, per nutrient and per request:**

1. **REGULATORY**: the nutrient has a value in the request's reference values; that value is the reference.
2. **MARKET_MEDIAN**: no regulatory value, and at least 3 products in the market profile contain the nutrient; the reference is the median of their daily amounts. The median is the middle value for an odd count and the mean of the two middle values for an even count. The market profile contains every product that is in market in the request's country at the current catalog version (independent of stock filter, offers, required nutrients, weights and UL filters), each product counted once; it is built once per country and catalog version and cached, so the median is identical for every query and comparison in that country and version.
3. **PRESENCE_ONLY**: otherwise the nutrient contributes a fixed utility of 0.5 if the daily amount is at least 0.1 times the presence floor, and 0 (row flagged `BELOW_PRESENCE_FLOOR`) if below; with no known presence floor the check is skipped and the utility is 0.5. No coverage ratio is reported.

Every breakdown row carries `reference_source`.

**Utility with ceiling.** With `r = amount_per_day / ref`:

```
u(r) = r                              for 0 <= r <= 1
u(r) = min(1 + beta * ln(r), u_max)   for r > 1
```

`beta = 0.35` (config key `diminishing_beta`), `u_max = 1.5` (config key `utility_ceiling`, must be at least 1). The ceiling is reached at `r = exp((u_max - 1) / beta)`, about 4.17 (417 % of the reference); amount beyond it earns nothing and the row reports `capped_at_u_max = true`. The value 1.5 bounds how much one mega-dosed nutrient can dominate the coverage score while keeping both the logarithmic segment and the ceiling exercised by the canonical example. `u_max` is reviewed together with the verdict thresholds during the Phase 2 calibration. Property-based tests enforce: `u` continuous and non-decreasing, `u(0) = 0`, `u(1) = 1`, concave for `r > 1`, `u <= u_max` everywhere.

**UL matching** (one rule for every UL set):

1. If the product form is not `UNKNOWN`, look up `(ul_set, nutrient, form)`.
2. Otherwise, or if absent, look up `(ul_set, nutrient, ANY)`.
3. If still absent and the set has at least one form-specific row for the nutrient, no UL applies and the product gets `UL_FORM_DEPENDENT` with the nutrient code.
4. Otherwise no UL applies. A matched `exempt` row means "no UL" without a flag.

**UL application basis (permissive).** Both `SUPPLEMENTAL` and `TOTAL` limits are applied directly to the supplement's UL-relevant daily amount (`ul_amount_per_serving`, or `amount_per_serving` when not set, times servings per day). For `TOTAL` limits this is **permissive: dietary intake is ignored**. A product flagged `EXCEEDS_UL` is already above the limit on its own; a product below the limit may still exceed it once the diet is added. The UI copy for `EXCEEDS_UL` states this.

**UL effects:**

- `EXCEEDS_UL` (product-level flag with the nutrient code) is set when a UL applies and the UL-relevant daily amount exceeds it, independent of the reference source; the `exclude_exceeding_ul` filter drops such products.
- When a reference exists, the creditable amount is capped at the UL (plus any amount that is not UL-relevant), `r` is computed from the capped amount, and the row reports `capped_at_ul = true` when the cap reduced it. Under PRESENCE_ONLY the utility stays as defined above. Amount above the UL never adds score.

**UL below the reference value.** Some supplemental limits are lower than the regulatory reference (EFSA magnesium 250 mg against an NRV of 375 mg; EFSA nicotinic acid 10 mg against an NRV of 16 mg). This is intended: the reference describes total-diet needs, the limit only supplemental intake. Such products are flagged `EXCEEDS_UL` when the UL-relevant amount exceeds the limit, and their utility is capped below 1. The breakdown row carries `ul_below_reference = true`, and the UI badge uses the key `flag.exceeds_ul_supplemental`, which explains that the limit concerns supplemental intake only. A scoring test covers this case.

## Consequences

- Every nutrient gets a defined contribution even without regulatory data, and the source of each reference is visible to the user.
- MARKET_MEDIAN references depend on the country's catalog; they change only with a catalog-version bump, which also invalidates caches.
- The permissive UL rule can under-warn for `TOTAL` limits; this is disclosed in the UI rather than estimated from an unknown diet.
- Products with a UL below the reference can never reach full utility for that nutrient; the badge text prevents this from looking like an error.

## Alternatives considered

- **Unbounded logarithmic utility.** Rejected: a single mega-dosed nutrient could dominate the coverage score.
- **Linear utility capped at 1.** Rejected: it gives no credit at all above the reference, which hides meaningful differences between products that exceed it moderately.
- **Estimating dietary intake for `TOTAL` limits.** Rejected for v1: it requires personal data the product does not collect, and any population default would be arbitrary.
- **Dropping nutrients without a regulatory reference.** Rejected: many products would be scored on an incomplete basis without telling the user.
