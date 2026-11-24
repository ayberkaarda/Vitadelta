# Real US label snapshot (DSLD)

This folder holds a snapshot of real US multivitamin and mineral labels, taken from the
[Dietary Supplement Label Database](https://dsld.od.nih.gov/) (DSLD) of the Office of Dietary
Supplements, National Institutes of Health, and converted to the project's import formats.

| File | Content |
|---|---|
| `products.dsld.json` | 84 on-market products of 75 brands in the products import format (`contracts/schemas/import-products.schema.json`), marked `synthetic: false`, available in `US`, without offers. |
| `provenance.json` | Per product: DSLD label id, source URL, entry date, product version code, retrieval time (UTC), API base, licence statement, and the counts of mapped, unmapped and skipped label rows. Also every skipped label with its reason, every unit string seen and how it was mapped, the unmapped ingredient names, and the demo price rule. |
| `offers.us-demo.synthetic.csv` | Demo offers in USD for the products above, in the offers CSV format (`contracts/schemas/offers-csv.columns.md`). |

## What the data is

- **Retrieved on 2026-11-24** through the DSLD API (`https://api.ods.od.nih.gov/dsld/v9/`). Label
  data is a point-in-time snapshot: products, formulas and market status change, and a label in
  this snapshot can differ from the package on a shelf today.
- Products are selected from the DSLD categories "Multi-Vitamin and Mineral" and "Adults and
  children 4 years and above", on market at retrieval, with a spread of brands and dosage forms.
  Amounts are per serving as labelled and mapped to the project's nutrient and form registries;
  the selection and mapping rules are documented in `scripts/dsld/README.md`.
- Amounts are loaded as labelled. The snapshot can contain products whose amounts exceed an upper
  limit; scoring flags them, it does not correct them.
- No label images, logos or other artwork are stored, only the label text fields needed for
  identification and scoring.

## Prices are demo values

DSLD contains no prices. Every offer in `offers.us-demo.synthetic.csv` is a **demo value**: the
retailers `Demo Store` and `Demo Market` are fictional, the URLs point to reserved example
domains, and the prices are computed from the product data by a fixed rule (see
`provenance.json` → `prices.rule`). They are not real prices and say nothing about what any
product costs.

## Licence and use

- The DSLD API specification states its data licence as **CC0 1.0 Universal (CC0 1.0)**
  (<https://creativecommons.org/publicdomain/zero/1.0/>), read from the API specification page at
  `https://api.ods.od.nih.gov/dsld/v9/` (version 9.4.0) on 2026-11-24.
- Brand and product names are used only to identify the labelled products. This project is not
  affiliated with, sponsored by or endorsed by any of the brands, by the Office of Dietary
  Supplements or by the National Institutes of Health.
- This data is not medical advice. Check the actual product label and ask a qualified health
  professional before taking supplements.

## Refreshing the snapshot

```sh
uv run scripts/dsld/dsld_snapshot.py fetch <empty-cache-dir>
uv run scripts/dsld/dsld_snapshot.py convert <cache-dir>
```

`fetch` downloads every response once (about 150 requests, paced) into the cache directory, which
should live outside the repository. `convert` works offline and writes the three files above;
running it again on the same cache gives byte-identical output. Update the retrieval date and the
counts in this README after a refresh.
