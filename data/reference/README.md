# Reference data

Regulatory reference data seeded into the catalog database by the seed loader. Every
value here was checked against the primary source cited below; all sources were accessed
on 2026-11-24. Section numbers (§) refer to the project specification
(`docs/PROJECT_PROMPT.md`).

All files are UTF-8 with LF line endings and a header row. CSV fields that contain a comma
are double-quoted (RFC 4180). Row order is stable and meaningful only for readability.

## Files

| File | Purpose |
|---|---|
| `nutrients.csv` | Nutrient registry: the 38 nutrient codes, category and canonical unit (§5.4). |
| `forms.csv` | Registry of nutrient form codes accepted in `product_nutrient.form_code` (§5.4). |
| `unit_conversions.csv` | Unit conversion factors applied before scoring (§5.5). |
| `reference_sets.csv` | Regional reference value sets (§5.6). |
| `reference_values.csv` | Reference amount per set and nutrient (§5.6). |
| `ul_sets.csv` | Tolerable upper intake level sets (§5.7). |
| `upper_limits.csv` | Tolerable upper intake levels per set, nutrient and form (§5.7). |
| `countries.csv` | Supported countries with currency, reference set, UL set and default locale (§7.1). |
| `weak_passwords.txt` | Common-password deny list used at registration (§8.2). |

## Column definitions

### `nutrients.csv`

| Column | Meaning |
|---|---|
| `code` | Nutrient code, unique. |
| `name_en` | English display name. |
| `category` | `VITAMIN`, `MINERAL`, `COENZYME` or `OTHER`. |
| `canonical_unit` | Unit every amount of this nutrient is converted to before scoring: `mg` or `mcg`. |
| `presence_floor_per_day` | Optional fallback amount (canonical unit) for the PRESENCE_ONLY minimum-amount rule (§6.3). Empty for every nutrient in this release, see "Not seeded". |
| `sort_order` | Display order (steps of 10). |

### `forms.csv`

| Column | Meaning |
|---|---|
| `code` | Form code. `ANY` and `UNKNOWN` are sentinels (§5.3) and are not listed here. |
| `nutrient_code` | Nutrient the form belongs to. |
| `name_en` | English description of the form. |

### `unit_conversions.csv`

| Column | Meaning |
|---|---|
| `nutrient_code` | Nutrient the factor applies to; `*` means every nutrient (generic SI prefix rows). |
| `from_unit`, `to_unit` | One of `g`, `mg`, `mcg`, `IU`. |
| `factor` | `amount_in_to_unit = amount_in_from_unit × factor`. |
| `form_code` | Form the factor applies to; `ANY` means every form. |
| `note` | Basis and source of the factor. |

### `reference_sets.csv` and `ul_sets.csv`

| Column | Meaning |
|---|---|
| `code` | Set code referenced by `countries.csv`. |
| `name` | Display name. |
| `source_citation` | Primary source with URL and access date. |

### `reference_values.csv`

| Column | Meaning |
|---|---|
| `reference_set_code` | `EU_NRV` or `US_DV`. |
| `nutrient_code` | Nutrient. A nutrient without a row has no regulatory value in that set and follows the fallback hierarchy of §6.3. |
| `amount`, `unit` | Reference amount per day, in the nutrient's canonical unit. |

### `upper_limits.csv`

| Column | Meaning |
|---|---|
| `ul_set_code` | `EFSA` or `IOM`. |
| `nutrient_code` | Nutrient. |
| `form_code` | Form the limit applies to; `ANY` means every form, including products whose form is `UNKNOWN`. Never empty. |
| `amount`, `unit` | Adult tolerable upper intake level per day. `amount` is empty only for exempt rows. |
| `basis` | `TOTAL` (the source limit covers intake from all sources) or `SUPPLEMENTAL` (the source limit covers supplements and added forms only). Empty only for exempt rows. |
| `note` | Citation of the source for the row plus scope remarks. An `exempt:` prefix marks a form explicitly without a UL. |

The matching rule (form row, then `ANY` row, then `UL_FORM_DEPENDENT`) and the application
basis are defined in §5.7. Both bases are applied to the supplement's UL-relevant daily
amount alone, which is permissive for `TOTAL` limits (dietary intake is ignored).

### `countries.csv`

| Column | Meaning |
|---|---|
| `code` | ISO 3166-1 alpha-2 country code. |
| `name_en` | English country name. |
| `currency` | ISO 4217 currency of offers in that country. |
| `reference_set_code` | Reference value set used for the country. |
| `ul_set_code` | UL set used for the country. |
| `default_locale` | Default UI locale. |

### `weak_passwords.txt`

One password per line, exactly as published by the upstream list (10,001 entries, no
additions or edits). Registration rejects any password contained in this list.

## Sources

### Reference values

- **EU_NRV**: Regulation (EU) No 1169/2011 on the provision of food information to
  consumers, Annex XIII Part A point 1 "Vitamins and minerals which may be declared and
  their nutrient reference values (NRVs)". https://eur-lex.europa.eu/eli/reg/2011/1169/oj
  (text retrieved from the Publications Office of the EU,
  http://publications.europa.eu/resource/celex/32011R1169), accessed 2026-11-24. All 27
  rows match the annex table. Türkiye's Türk Gıda Kodeksi labelling regulation is aligned
  with these values, and Great Britain uses the assimilated version of the same annex
  (https://www.legislation.gov.uk/eur/2011/1169/annex/XIII, accessed 2026-11-24, same
  values), so `TR`, `DE` and `GB` all use `EU_NRV`. The annex lists folate as "Folic
  acid (μg)"; the value is stored under `VIT_B9`. Choline has no NRV.
- **US_DV**: 21 CFR 101.9(c)(8)(iv), RDI column "Adults and children ≥ 4 years" (2016
  update), eCFR, up to date as of 2026-11-20.
  https://www.ecfr.gov/current/title-21/chapter-I/subchapter-B/part-101/subpart-A/section-101.9
  accessed 2026-11-24. All 27 rows match. Vitamin A is in mcg RAE, folate in mcg DFE,
  niacin in mg NE and vitamin E in mg alpha-tocopherol. Fluoride has no Daily Value.
  21 CFR 101.36 (Supplement Facts) uses the same RDIs by reference.

### Unit conversion factors

- **Vitamin D**: 1 IU = 0.025 mcg (40 IU = 1 mcg), FDA "Guidance for Industry: Converting
  Units of Measure for Folate, Niacin, and Vitamins A, D, and E on the Nutrition and
  Supplement Facts Labels" (August 2019), Table 4. https://www.fda.gov/media/129863/download,
  accessed 2026-11-24. 21 CFR 101.9(c)(8)(iv) footnote 2 permits IU only as a voluntary
  parenthetical declaration next to mcg.
- **Vitamin A**: 1 IU = 0.30 mcg for pre-formed vitamin A (retinol), same guidance Table 3.
  The guidance defines one IU of vitamin A as the activity of 0.30 mcg all-trans-retinol,
  so retinyl esters declared in IU convert on the same retinol basis. Beta-carotene IU is
  not converted (§5.5): the guidance itself says no direct IU-to-RAE factor exists for
  mixtures.
- **Vitamin E**: 1 IU = 0.67 mg alpha-tocopherol for natural RRR-alpha-tocopherol and its
  esters, 1 IU = 0.45 mg for synthetic all-rac-alpha-tocopherol and its esters, same
  guidance Table 6. The same factors appear in IOM (2000) Table 6-1. The `VIT_E` `ANY` row
  (0.67, natural assumed, flagged `CONVERSION_ASSUMED`) is a project rule built on the
  natural factor, not a regulatory value.
- Generic rows (`*`) are SI prefix conversions.
- Folate (DFE) and niacin (NE) are not converted; see the label-basis rule in §5.5.

### Upper limit sets

- **EFSA**: "Overview on Tolerable Upper Intake Levels as derived by the Scientific
  Committee on Food (SCF) and the EFSA Panel on Dietetic Products, Nutrition and Allergies
  (NDA)", Version 11 (August 2025).
  https://www.efsa.europa.eu/sites/default/files/2024-05/ul-summary-report.pdf, accessed
  2026-11-24, plus the opinions below. Older values come from "Tolerable Upper Intake
  Levels for Vitamins and Minerals" (EFSA, 2006),
  https://www.efsa.europa.eu/sites/default/files/efsa_rep/blobserver_assets/ndatolerableuil.pdf.
- **IOM**: National Academies of Sciences, Engineering, and Medicine, Dietary Reference
  Intakes for Sodium and Potassium (2019), Appendix J "Dietary Reference Intakes Summary
  Tables", tables "Tolerable Upper Intake Levels, Vitamins" and "Tolerable Upper Intake
  Levels, Elements". https://nap.nationalacademies.org/read/25353/chapter/28, accessed
  2026-11-24. Vitamin E details from Institute of Medicine, Dietary Reference Intakes for
  Vitamin C, Vitamin E, Selenium, and Carotenoids (2000), chapter 6 and Table 6-1,
  https://nap.nationalacademies.org/read/9810/chapter/8, accessed 2026-11-24. Values are
  for adults 19–50 years unless the row note says otherwise.

#### Citations per UL row group

| Rows | Source |
|---|---|
| EFSA `VIT_A` (retinol forms, beta-carotene exempt) | EFSA NDA Panel (2024). Scientific opinion on the tolerable upper intake level for preformed vitamin A and β-carotene. EFSA Journal 22(6). https://doi.org/10.2903/j.efsa.2024.8814 |
| EFSA `VIT_D` | EFSA NDA Panel (2023). Scientific opinion on the tolerable upper intake level for vitamin D. EFSA Journal 21(8). https://doi.org/10.2903/j.efsa.2023.8145 |
| EFSA `VIT_E` | EFSA NDA Panel (2024). Scientific opinion on the tolerable upper intake level for vitamin E. EFSA Journal 22(8). https://doi.org/10.2903/j.efsa.2024.8953 |
| EFSA `VIT_B6` | EFSA NDA Panel (2023). Scientific opinion on the tolerable upper intake level for vitamin B6. EFSA Journal 21(5). https://doi.org/10.2903/j.efsa.2023.8006 |
| EFSA `VIT_B9` | EFSA NDA Panel (2023). Scientific opinion on the tolerable upper intake level for folate. EFSA Journal 21(11). https://doi.org/10.2903/j.efsa.2023.8353 |
| EFSA `VIT_B3` | SCF (2002). Opinion on the Tolerable Upper Intake Levels of nicotinic acid and nicotinamide (niacin), in EFSA (2006) |
| EFSA `CALCIUM` | EFSA NDA Panel (2012). Scientific Opinion on the Tolerable Upper Intake Level of calcium. EFSA Journal 10(7). https://doi.org/10.2903/j.efsa.2012.2814 |
| EFSA `MAGNESIUM` | SCF (2001). Opinion on the Tolerable Upper Intake Level of magnesium, in EFSA (2006) |
| EFSA `ZINC` | SCF (2002). Opinion on the Tolerable Upper Intake Level of zinc, in EFSA (2006) |
| EFSA `SELENIUM` | EFSA NDA Panel (2023). Scientific opinion on the tolerable upper intake level for selenium. EFSA Journal 21(1). https://doi.org/10.2903/j.efsa.2023.7704 |
| EFSA `COPPER` | SCF (2003). Opinion on the Tolerable Upper Intake Level of copper, in EFSA (2006) |
| EFSA `IODINE` | SCF (2003). Opinion on the Tolerable Upper Intake Level of iodine, in EFSA (2006) |
| EFSA `MOLYBDENUM` | SCF (2000). Opinion on the Tolerable Upper Intake Level of molybdenum, in EFSA (2006) |
| IOM `VIT_E` | NASEM (2019) Appendix J and IOM (2000) chapter 6, Table 6-1 |
| All other IOM rows | NASEM (2019) Appendix J |

## Notes on individual nutrients

- **Vitamin A.** Both ULs apply to preformed vitamin A only (retinol and retinyl esters).
  EFSA's 2024 opinion retained 3000 mcg RE/day for adults; the IOM table gives 3000 mcg/day
  "as preformed vitamin A only". Beta-carotene has no UL in either set (EFSA 2024 could not
  establish one; IOM lists carotenoids as not determinable) and is seeded as exempt. EFSA
  advises that smokers avoid food supplements containing beta-carotene and that
  supplemental beta-carotene for the general population be limited to meeting vitamin A
  requirements. For labels that declare both preformed vitamin A and beta-carotene, the
  importer records the preformed part in `ul_amount_per_serving` (label-basis rule, §5.5).
- **Vitamin E.** EFSA's 300 mg/day (2024 opinion, SCF value retained) covers all dietary
  sources and all stereoisomeric forms of alpha-tocopherol, hence basis `TOTAL` and one
  `ANY` row. The IOM UL is 1000 mg/day "of any form of supplemental α-tocopherol", by mass
  of the compound, and covers synthetic forms from supplements and fortified foods (basis
  `SUPPLEMENTAL`). Because §5.5 converts IU to mg of alpha-tocopherol activity, the IOM rows
  are stored on the activity basis using the IOM Table 6-1 factors: 1000 mg
  dl-alpha-tocopherol × 1.10 IU/mg = 1100 IU × 0.45 = 495 mg; 1000 mg d-alpha-tocopherol ×
  1.49 IU/mg = 1490 IU (commonly rounded to 1500 IU) × 0.67 ≈ 1000 mg, which equals the
  mass value because 1 mg RRR-alpha-tocopherol is 1 mg of activity. The `ANY` row uses the
  stricter 495 mg for unknown forms. Products are compared on `amount_per_day` of `VIT_E`
  in mg alpha-tocopherol activity. For all-rac-alpha-tocopheryl acetate (1.00 IU/mg) the
  mass limit would correspond to 450 mg of activity; the seeded 495 mg follows the free
  all-rac-alpha-tocopherol basis.
- **Niacin.** IOM's 35 mg/day applies to all synthetic forms (nicotinic acid and
  nicotinamide) from supplements and fortified foods, hence one IOM `ANY` row with basis
  `SUPPLEMENTAL`. EFSA has form-specific values only: free nicotinic acid 10 mg/day and
  nicotinamide 900 mg/day (SCF 2002; flushing has not been reported for the bound forms of
  nicotinic acid present in foods). Under EFSA a product with form `UNKNOWN` therefore gets
  `UL_FORM_DEPENDENT`.
- **Folate.** EFSA's 1000 mcg/day (2023 opinion) applies to the combined intake of folic
  acid and the authorised 5-methyltetrahydrofolate salts added to foods or used in food
  supplements and excludes folate naturally present in food; the importer sums both forms in
  `ul_amount_per_serving` when both are declared. The IOM UL covers synthetic folic acid
  from supplements and fortified foods; methylfolate has no IOM row and yields
  `UL_FORM_DEPENDENT` under IOM.
- **Iron.** EFSA has no UL for iron. Its 2024 opinion
  (https://doi.org/10.2903/j.efsa.2024.8819) established a safe level of intake of
  40 mg/day for adults, which is not a UL and is not seeded; it is mentioned for
  information only. The IOM UL is 45 mg/day.
- **Calcium.** The IOM value 2500 mg/day is the UL for adults 19–50 years. The IOM UL for
  adults 51 years and older is 2000 mg/day; it is not modelled because the scoring targets
  the general adult population (§2.2). EFSA's 2500 mg/day applies to all adults.
- **Chloride.** The IOM UL of 3.6 g/day (all adult groups, set in the 2005 report on
  water, potassium, sodium, chloride and sulfate) is listed in the NASEM 2019 summary table
  and is seeded as 3600 mg, basis `TOTAL`. EFSA has no adequate data to derive a UL for
  chloride.
- **Magnesium.** Both limits exclude magnesium naturally present in food and water (EFSA:
  readily dissociable salts and magnesium oxide in supplements, water and fortified foods;
  IOM: pharmacological agents only), hence basis `SUPPLEMENTAL`.
- **Phosphorus.** The IOM UL is 4 g/day for adults up to 70 years and 3 g/day above
  70 years; 4000 mg is seeded.
- **UL below the reference value.** EFSA's magnesium (250 mg) and nicotinic acid (10 mg)
  limits are lower than the corresponding NRVs (375 mg, 16 mg). This is intended: the
  reference describes total-diet needs, the limit only supplemental intake (§5.7).

## Label-basis rule

Vitamin A and folate amounts are recorded as labelled (mcg RE / mcg RAE, mcg DFE or mcg
folic acid); no equivalence factor is applied. When the UL-relevant amount differs from the
labelled total, the importer stores it in `product_nutrient.ul_amount_per_serving`. See
§5.5 for the full rule.

## Not seeded / differences from the planning table

- **EFSA `FLUORIDE` (planned 7 mg/day): not seeded.** The EFSA Scientific Committee's 2025
  updated risk assessment of fluoride (https://doi.org/10.2903/j.efsa.2025.9478) replaced
  the earlier adult UL: ULs now exist only for infants, toddlers and children 4–8 years
  (1.0, 1.6 and 2.0 mg/day); for adults and children over 8 years it set a safe level of
  intake of 3.3 mg/day from all oral sources, which is not a UL. The EFSA overview (Version
  11) lists the adult UL as not defined.
- **IOM `CHLORIDE`: seeded** (3600 mg, `TOTAL`); the planning table listed it as pending
  verification.
- **IOM `VIT_E`: seeded** as planned (`ANY` 495 mg, `D_ALPHA_TOCOPHEROL` 1000 mg,
  `DL_ALPHA_TOCOPHEROL` 495 mg). The IU equivalents (1100 IU synthetic, about 1490–1500 IU
  natural) follow from the IOM Table 6-1 factors; the IOM report itself states the UL in mg
  only. The basis is `SUPPLEMENTAL` (the IOM UL covers supplemental alpha-tocopherol).
- **EFSA iron safe level (40 mg/day)**: not seeded (not a UL).
- **`presence_floor_per_day`**: empty for every nutrient. No primary source stating a
  typical supplemental daily amount was verified for this release; affected nutrients use
  the §6.3 fallback hierarchy.
- The EFSA overview Version 11 still cites the SCF 2003 vitamin E opinion with the remark
  that a review is ongoing, although the 2024 opinion has been published; both give
  300 mg/day for adults.
- All reference values (`EU_NRV`, `US_DV`) and all other UL rows match the planning table.

## Common-password list

`weak_passwords.txt` is `Passwords/Common-Credentials/10k-most-common.txt` from SecLists
(Daniel Miessler and contributors), retrieved 2026-11-24 at commit
`913b327317496d062bcc7cace524aaad8a693be2`:
https://github.com/danielmiessler/SecLists/blob/913b327317496d062bcc7cace524aaad8a693be2/Passwords/Common-Credentials/10k-most-common.txt

It is redistributed unchanged under the MIT License:

```
MIT License

Copyright (c) 2018 Daniel Miessler

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
