# ADR-0012: Real US label data from the NIH Dietary Supplement Label Database, committed as a snapshot with synthetic demo prices

- Status: Accepted
- Date: 2026-11-24

## Context

Until now every product in the repository is synthetic, with fictional brands. That keeps the data internally consistent and free of third-party claims, but it limits realism: the ranking, the upper-limit handling and the nutrient coverage are never exercised against the variety of real labels (unusual forms, units, many-ingredient formulas). A demonstration with real labels is more convincing and finds data-shape problems earlier. Real prices, however, cannot be obtained in a way that is both reliable and permitted, so they must not be presented as real.

## Decision

**Source.** The NIH Office of Dietary Supplements Dietary Supplement Label Database (DSLD), through its public API at `https://api.ods.od.nih.gov/dsld/v9/`. No API key is needed. The API specification states the data as CC0 1.0. The website's terms pages could not be read, so the licence is confirmed only from the API specification.

**Selection.** Products are limited to US multivitamin/mineral labels that are currently on the market, have a complete ingredient list with quantities, and whose ingredients and units can be mapped to the nutrient registry without guessing. Labels that cannot be mapped fully are skipped rather than approximated.

**Converter and snapshot.** A converter under `scripts/dsld/` reads the API and writes three files, which are committed so that tests and local runs need no network:

- `data/real/products.dsld.json`: products in the import schema.
- `data/real/provenance.json`: the API version, the retrieval date, the selection parameters and the list of included and skipped label ids.
- `data/real/offers.us-demo.synthetic.csv`: demo offers (see below).

**Product fields.** The `product` table gains two nullable columns, `source_ref varchar(100)` (the label id in the source) and `source_entry_date date` (the label's entry date in the source). The import schema gains the same two optional properties. Real products carry `source = 'DSLD'`, `source_ref`, `source_entry_date` and `synthetic = false`.

**Availability.** Real products are AVAILABLE in the US only. They have no rows for TR, DE or GB.

**Prices.** Prices are not real. Offers come from a file named `*.synthetic.csv`, with the retailers `Demo Store` and `Demo Market` and invented prices in USD.

**Synthetic fixtures unchanged.** The golden and test fixtures (GOLD-A and GOLD-B, `ZN40`, the three `COQ10` products, the TR, DE and GB sets and the price-drop file) stay synthetic with fictional brands.

## Honest-presentation rules

- Brand and product names are used for identification only. VitaDelta is not affiliated with, and not endorsed by, the brands or NIH.
- The snapshot is shown with its entry date, so users know the label data may be outdated.
- Prices of real products are demo values and are labelled as such wherever they appear.
- No label images and no logos are used.
- The comparison is not medical advice; the standing disclaimer applies to real products as well.

## Consequences

- US results now contain real products. A real label may exceed an upper limit; it is loaded as labelled and flagged by the normal scoring rules. The expectation that exactly 0 products are excluded for exceeding an upper limit outside TR now holds for the synthetic fixtures only, and the fixture integrity tests are scoped accordingly. The rule against real brand names applies to synthetic fixtures only.
- The data tests additionally check provenance: every product with `synthetic = false` has `source = 'DSLD'`, `source_ref` and `source_entry_date`, is available in the US only, and has offers only from the demo file.
- Refresh procedure: run the converter, review the diff of the three files and the skipped-label list in `provenance.json`, then commit the new snapshot. The application never calls the source at run time.
- Licence confirmation with NIH is an optional follow-up for the owner; until then the CC0 statement in the API specification is the basis.

## Alternatives considered

- **Synthetic data only.** Rejected: simple and safe, but it hides real-world data problems and makes demonstrations less convincing.
- **Scraping retailer sites for products and prices.** Rejected and out of scope: terms-of-use and reliability concerns, and it would put real third-party prices and images into the repository.
- **Other public databases.** Open Food Facts and similar sources are food-oriented and do not provide structured supplement label facts of this quality; national databases of other countries were not evaluated for this phase because their licence or API access was not clearly open.
