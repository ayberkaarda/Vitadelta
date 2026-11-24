# Synthetic fixture dataset

**Everything in this folder is synthetic.** The brands (`Fixture Labs`, `Northline Nutrition`,
`Meridian Vitals`, `Bosphorus Health`, `Redwood Daily`), products, formulations, retailers, prices
and URLs are fictional and were written for testing. They do not describe any real product or
shop, and they are not dosage or purchasing advice. Every product carries `"synthetic": true`.
Real catalog data enters the system only through the admin import pipeline.

## Files

| File | Format | Content |
|---|---|---|
| `products.synthetic.json` | `contracts/schemas/import-products.schema.json` | 26 products with nutrients and per-country availability (no offers). |
| `offers.synthetic.csv` | `contracts/schemas/offers-csv.columns.md` | 67 offers across TR, DE, GB and US. |
| `offers.price-drop.synthetic.csv` | same | Identical to `offers.synthetic.csv` except two rows (see below). |

Load order: import the products document first, then an offers file. All files are UTF-8 without
a byte order mark and use LF line endings. Offer timestamps are fixed UTC instants between
2026-08-14 and 2026-11-23; offer URLs use hosts under the reserved `.example` top-level domain.

## Market overview

| Availability (`AVAILABLE`) | Products |
|---|---|
| TR only | `GOLD-A`, `GOLD-B`, `ZN40`, `BH-DAILY`, `BH-COQ10-100`, `BH-FAMILY-GUMMY`, `BH-MAG-B6` |
| US only | `RD-ADULT`, `RD-BONE`, `RD-EYE`, `RD-BCOMPLEX`, `RD-COQ10-ENERGY`, `NN-ELECTRO`, `RD-MEN-50` |
| DE and GB only | `MV-ACTIVE-50`, `MV-IMMUNE`, `NN-WOMENS` |
| All four countries | `MV-UBIQ-200`, `NN-ESSENTIAL`, `NN-GUMMY` |
| Other combinations | `FL-VISION-30`, `FL-OMEGA-LIQUID`, `FL-HAIR` (TR, DE); `FL-D3K2`, `FL-IRON` (TR, GB); `MV-COMPLETE-4` (DE, GB, US) |

Products in market per country: TR 15, DE 10, GB 9, US 11. Offer currencies always equal the
country currency (TRY, EUR, GBP, USD). Dosage forms cover tablets, capsules, softgels, gummies, a
powder and a liquid.

## Test scenarios built into the data

- **Golden pair** (`GOLD-A`, `GOLD-B`): the canonical formulation of the scoring golden tests,
  60 tablets, one per day, best in-stock TR offers 800.00 and 900.00 TRY. Content scores 4.9719 and
  5.0093; marginal rate about 44.6 TRY per point per day (about 20.5 after the price drop).
- **Market calibration:** the median `cost_per_day / C` over TR products with in-stock offers
  (default weights, EU_NRV and EFSA) is about 2.04 TRY (2.31 after the price drop), well below the
  10.00 bound, so the golden pair stays `NOT_WORTH_IT` before and after the price drop.
- **Upper limit:** `ZN40` (zinc 40 mg, one per day, TR) is the only product above an applicable
  limit (EFSA zinc 25 mg). No other product exceeds an EFSA limit in TR, DE or GB or an IOM limit
  in US; per-day amounts are checked, including products taken twice a day.
- **Market median reference:** exactly three TR in-market products contain coenzyme Q10, at 30
  (`FL-VISION-30`, also with lutein), 100 (`BH-COQ10-100`) and 200 mg per day (`MV-UBIQ-200`,
  100 mg twice a day). `RD-COQ10-ENERGY` also contains coenzyme Q10 but is `NOT_SOLD` in TR.
- **Availability:** `MV-ACTIVE-50`, `RD-ADULT`, `RD-BONE` and `RD-COQ10-ENERGY` have `NOT_SOLD`
  rows for TR; `BH-COQ10-100` is `NOT_SOLD` in DE; `RD-MEN-50` is `DISCONTINUED` in GB.
- **Stock and staleness:** `BH-FAMILY-GUMMY`, `BH-MAG-B6` and `FL-HAIR` have only out-of-stock TR
  offers. `FL-IRON` in TR has a single in-stock offer checked on 2026-08-14 (older than 30 days).
- **Units and labels:** vitamin D in IU (`FL-D3K2`, `RD-BONE`), vitamin E in IU as
  d-alpha-tocopherol (`MV-UBIQ-200`, `RD-EYE`), potassium in g (`NN-ELECTRO`), lycopene in mcg
  (`RD-MEN-50`); mixed vitamin A and folate labels with `ul_amount_per_serving` (`NN-ESSENTIAL`,
  `RD-ADULT`); beta-carotene-only vitamin A (`FL-VISION-30`, `RD-MEN-50`).
- **Serving arithmetic:** `MV-COMPLETE-4` uses two capsules per serving and two servings per day;
  `BH-MAG-B6` and `MV-UBIQ-200` use two servings per day; `FL-OMEGA-LIQUID` counts millilitres.
- Every product has at least five nutrients except the single-nutrient `ZN40`.

## Price-drop file

`offers.price-drop.synthetic.csv` differs from `offers.synthetic.csv` in exactly two rows, so
re-importing it changes the effective state of exactly two pairs:

1. `GOLD-B` / TR / Fixture Pharmacy: price 900.00 to 846.00 TRY (a 6 % drop, still above
   `GOLD-A` at 800.00).
2. `BH-FAMILY-GUMMY` / TR / Fixture Pharmacy: back in stock at 329.90 TRY.
