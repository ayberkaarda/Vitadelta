# Verdict calibration over the fixture market

Produced with `scripts/calibrate_verdicts.py` (deterministic; do not edit by hand).
Every same-country pair of products with an in-stock offer is compared with the
country's in-stock candidate set as `market[]`, default weights and the country's
reference and UL sets. Variants move one parameter by +-20 % at a time.

Defaults: `diminishing_beta = 0.35`, `utility_ceiling = 1.5`, `comparison_epsilon = 1e-09`, `verdict_worth_it_max_ratio = 1.0`, `verdict_marginal_max_ratio = 1.5`.

## Verdict shares (defaults)

| set | products | pairs | m_ref | WORTH_IT | MARGINAL | NOT_WORTH_IT | DOMINATES | EQUIVALENT | INSUFFICIENT_DATA |
|---|---|---|---|---|---|---|---|---|---|
| TR | 12 | 66 | 2.0369 | 19 (28.8 %) | 2 (3.0 %) | 45 (68.2 %) | 0 (0.0 %) | 0 (0.0 %) | 0 (0.0 %) |
| TR-price-drop | 13 | 78 | 2.3070 | 20 (25.6 %) | 2 (2.6 %) | 56 (71.8 %) | 0 (0.0 %) | 0 (0.0 %) | 0 (0.0 %) |
| DE | 10 | 45 | 0.0392 | 5 (11.1 %) | 5 (11.1 %) | 35 (77.8 %) | 0 (0.0 %) | 0 (0.0 %) | 0 (0.0 %) |
| GB | 9 | 36 | 0.0356 | 10 (27.8 %) | 6 (16.7 %) | 20 (55.6 %) | 0 (0.0 %) | 0 (0.0 %) | 0 (0.0 %) |
| US | 11 | 55 | 0.0549 | 6 (10.9 %) | 1 (1.8 %) | 48 (87.3 %) | 0 (0.0 %) | 0 (0.0 %) | 0 (0.0 %) |
| US-with-real-labels | 95 | 4465 | 0.0186 | 1074 (24.1 %) | 204 (4.6 %) | 3185 (71.3 %) | 2 (0.0 %) | 0 (0.0 %) | 0 (0.0 %) |

## Ratio distribution (pairs with a ratio)

| set | min | p10 | p25 | median | p75 | p90 | max |
|---|---|---|---|---|---|---|---|
| TR | 0.0239 | 0.1061 | 0.2622 | 1.0329 | 4.5317 | 16.7673 | 52.7751 |
| TR-price-drop | 0.0211 | 0.0981 | 0.2837 | 1.3961 | 5.2738 | 12.4247 | 46.5975 |
| DE | 0.0225 | 0.2561 | 0.7542 | 1.2731 | 2.6666 | 7.1834 | 9.5340 |
| GB | 0.0036 | 0.1136 | 0.2688 | 0.9365 | 1.2150 | 3.0821 | 16.4846 |
| US | 0.0339 | 0.4010 | 0.8193 | 2.0068 | 5.5108 | 10.2243 | 43.0813 |
| US-with-real-labels | 0.0012 | 0.1849 | 0.3642 | 0.9495 | 2.9035 | 8.5316 | 1679.9313 |

## Verdict flips under +-20 % changes

Flipped pairs per variant (share of all pairs of the set).

| set | worth_it -20% | worth_it +20% | marginal -20% | marginal +20% | u_max -20% | u_max +20% |
|---|---|---|---|---|---|---|
| TR | 1 (1.5 %) | 0 (0.0 %) | 2 (3.0 %) | 0 (0.0 %) | 0 (0.0 %) | 0 (0.0 %) |
| TR-price-drop | 1 (1.3 %) | 2 (2.6 %) | 0 (0.0 %) | 2 (2.6 %) | 0 (0.0 %) | 0 (0.0 %) |
| DE | 1 (2.2 %) | 2 (4.4 %) | 3 (6.7 %) | 0 (0.0 %) | 1 (2.2 %) | 0 (0.0 %) |
| GB | 1 (2.8 %) | 4 (11.1 %) | 2 (5.6 %) | 0 (0.0 %) | 1 (2.8 %) | 0 (0.0 %) |
| US | 1 (1.8 %) | 1 (1.8 %) | 0 (0.0 %) | 2 (3.6 %) | 1 (1.8 %) | 0 (0.0 %) |
| US-with-real-labels | 121 (2.7 %) | 97 (2.2 %) | 107 (2.4 %) | 78 (1.7 %) | 144 (3.2 %) | 98 (2.2 %) |

### Flipped pairs

- TR:
  - worth_it -20%: FL-OMEGA-LIQUID vs GOLD-B: WORTH_IT -> MARGINAL (ratio 0.8108 -> 0.8108)
  - marginal -20%: ZN40 vs GOLD-A: MARGINAL -> NOT_WORTH_IT (ratio 1.2549 -> 1.2549)
  - marginal -20%: ZN40 vs GOLD-B: MARGINAL -> NOT_WORTH_IT (ratio 1.4640 -> 1.4640)
- TR-price-drop:
  - worth_it -20%: ZN40 vs BH-FAMILY-GUMMY: WORTH_IT -> MARGINAL (ratio 0.8801 -> 0.8801)
  - worth_it +20%: ZN40 vs GOLD-A: MARGINAL -> WORTH_IT (ratio 1.1080 -> 1.1080)
  - worth_it +20%: ZN40 vs GOLD-B: MARGINAL -> WORTH_IT (ratio 1.1869 -> 1.1869)
  - marginal +20%: FL-OMEGA-LIQUID vs BH-COQ10-100: NOT_WORTH_IT -> MARGINAL (ratio 1.6054 -> 1.6054)
  - marginal +20%: ZN40 vs BH-COQ10-100: NOT_WORTH_IT -> MARGINAL (ratio 1.7754 -> 1.7754)
- DE:
  - worth_it -20%: FL-OMEGA-LIQUID vs MV-COMPLETE-4: WORTH_IT -> MARGINAL (ratio 0.8084 -> 0.8084)
  - worth_it +20%: FL-VISION-30 vs MV-COMPLETE-4: MARGINAL -> WORTH_IT (ratio 1.1329 -> 1.1329)
  - worth_it +20%: MV-IMMUNE vs MV-COMPLETE-4: MARGINAL -> WORTH_IT (ratio 1.1741 -> 1.1741)
  - marginal -20%: FL-HAIR vs MV-COMPLETE-4: MARGINAL -> NOT_WORTH_IT (ratio 1.2199 -> 1.2199)
  - marginal -20%: NN-GUMMY vs MV-COMPLETE-4: MARGINAL -> NOT_WORTH_IT (ratio 1.3870 -> 1.3870)
  - marginal -20%: NN-WOMENS vs MV-COMPLETE-4: MARGINAL -> NOT_WORTH_IT (ratio 1.3263 -> 1.3263)
  - u_max -20%: MV-IMMUNE vs FL-VISION-30: NOT_WORTH_IT -> MARGINAL (ratio 8.7012 -> 1.1813)
- GB:
  - worth_it -20%: FL-D3K2 vs MV-IMMUNE: WORTH_IT -> MARGINAL (ratio 0.9365 -> 0.9365)
  - worth_it +20%: FL-D3K2 vs FL-IRON: MARGINAL -> WORTH_IT (ratio 1.1757 -> 1.1757)
  - worth_it +20%: FL-D3K2 vs MV-COMPLETE-4: MARGINAL -> WORTH_IT (ratio 1.0894 -> 1.0894)
  - worth_it +20%: FL-IRON vs MV-COMPLETE-4: MARGINAL -> WORTH_IT (ratio 1.0810 -> 1.0810)
  - worth_it +20%: MV-IMMUNE vs MV-COMPLETE-4: MARGINAL -> WORTH_IT (ratio 1.1177 -> 1.1177)
  - marginal -20%: NN-GUMMY vs MV-COMPLETE-4: MARGINAL -> NOT_WORTH_IT (ratio 1.2983 -> 1.2983)
  - marginal -20%: NN-WOMENS vs MV-COMPLETE-4: MARGINAL -> NOT_WORTH_IT (ratio 1.2543 -> 1.2543)
  - u_max -20%: FL-D3K2 vs FL-IRON: MARGINAL -> WORTH_IT (ratio 1.1757 -> 0.9337)
- US:
  - worth_it -20%: RD-BONE vs MV-COMPLETE-4: WORTH_IT -> MARGINAL (ratio 0.9224 -> 0.9224)
  - worth_it +20%: NN-GUMMY vs MV-COMPLETE-4: MARGINAL -> WORTH_IT (ratio 1.1180 -> 1.1180)
  - marginal +20%: RD-BCOMPLEX vs MV-COMPLETE-4: NOT_WORTH_IT -> MARGINAL (ratio 1.6702 -> 1.6702)
  - marginal +20%: RD-MEN-50 vs MV-COMPLETE-4: NOT_WORTH_IT -> MARGINAL (ratio 1.5297 -> 1.5297)
  - u_max -20%: RD-BCOMPLEX vs RD-MEN-50: NOT_WORTH_IT -> MARGINAL (ratio n/a -> 1.0160)
- US-with-real-labels: 645 flips over 452 of 4465 pairs; every flip is listed in `verdict-calibration.json`.

## Golden pair (GOLD-A vs GOLD-B)

- TR: NOT_WORTH_IT at ratio 21.8860; verdicts under all variants: NOT_WORTH_IT
- TR-price-drop: NOT_WORTH_IT at ratio 8.8891; verdicts under all variants: NOT_WORTH_IT

## Proposal

- Golden pair NOT_WORTH_IT under every variant, before and after the price drop: yes.
- Every threshold flip comes from a pair whose default ratio lies within +-20 % of the threshold that moved: yes.
- Largest share of pairs flipped by one threshold variant: 11.1 %.
- Largest share of pairs flipped by a +-20 % change of `u_max`: 3.2 % (review limit 5 %).

Result: keep the defaults (`verdict_worth_it_max_ratio = 1.0`, `verdict_marginal_max_ratio = 1.5`, `utility_ceiling = 1.5`). Changing them is an owner decision.
