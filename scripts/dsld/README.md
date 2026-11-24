# DSLD snapshot converter

`dsld_snapshot.py` builds the real US label snapshot in `data/real/` from the
[Dietary Supplement Label Database](https://dsld.od.nih.gov/) (DSLD) of the NIH Office of
Dietary Supplements. It uses only the Python standard library and runs with `uv`.

```sh
# 1. Download raw API responses into a cache directory (network; resumes from the cache)
uv run scripts/dsld/dsld_snapshot.py fetch <cache-dir>

# 2. Convert the cache into data/real/ (offline, deterministic)
uv run scripts/dsld/dsld_snapshot.py convert <cache-dir>
uv run scripts/dsld/dsld_snapshot.py convert <cache-dir> --out <other-dir>
```

Keep the cache directory outside the repository. Running `convert` twice on the same cache
produces byte-identical files.

## Fetch

- API base `https://api.ods.od.nih.gov/dsld/v9/`, mirror `https://dsldapi.od.nih.gov/v9/`. No API
  key. Plain GET requests with a generic user agent, at least 2 seconds apart, at most 3 attempts
  per URL (alternating between the base and the mirror). The step stops if a URL still fails.
- Every response is stored once in the cache with a `.meta.json` sidecar (URL, API base,
  retrieval time in UTC). Cached URLs are never requested again, so a refresh starts from an empty
  cache directory.
- Requests: the API specification page (`spec/api-spec.html`, read for the licence statement),
  one `search-filter` page per dosage form (`q=*`, `status=1` on market, `product_type=a1315`
  multi-vitamin and mineral, `target_group=p0250` adults and children 4 years and above,
  `supplement_form=<form>`, newest entries first, 200 hits), and one `label/{id}` request per
  candidate label. A full run makes about 150 requests.

## Candidate selection

From the cached search hits, per dosage form in the order softgel, liquid, powder, gummy,
capsule, tablet (scarce forms first):

- on market, the only target group is "adults and children 4 years and above", the physical
  state matches the form, and at least 8 vitamin or mineral ingredients are listed;
- names that point at children, teens, infants, pregnancy, lactation or pets are left out;
- brands are taken round robin (newest entry first within a brand), at most 2 labels per brand
  overall and one label per brand and product name, until the quota of the form is reached
  (softgel 15, liquid 12, powder 20, gummy 25, capsule 30, tablet 40).

## Conversion rules

Per label, in this order (a failing step skips the label with the reason shown):

1. Exactly one serving size (`SERVING_SIZE_NOT_SINGLE`); the serving unit is listed in
   `serving-unit-map.csv` (`SERVING_UNIT_UNSUPPORTED`); the serving quantity is a single whole
   number (`SERVING_SIZE_RANGE`, `SERVING_SIZE_NOT_INTEGER`); the daily servings are stated
   (`DAILY_SERVINGS_MISSING`) as a single value (`DAILY_SERVINGS_RANGE`).
2. `units_per_serving` is the serving quantity; `units_per_container` is the net contents when
   they are given in the serving unit, else `servingsPerContainer` times the serving quantity
   (`CONTAINER_COUNT_UNKNOWN` when neither is a whole number); `servings_per_day` is the daily
   servings value.
3. Ingredient rows are mapped to the nutrient registry through `nutrient-map.csv`: first by UNII
   code, then by the normalised ingredient name, then by the normalised DSLD ingredient group.
   Rows outside the registry are counted as unmapped. Proprietary blends (and their nested rows)
   and rows without an exact amount (`<` amounts, no quantity) are skipped and counted. Amounts are
   per serving as labelled, rounded to 4 decimals.
4. Units are mapped through `unit-map.csv`. `mcg DFE` (folate), `mcg RAE` (vitamin A) and `mg NE`
   (niacin) are recorded in mcg or mg as labelled, without equivalence factors. `IU` is accepted
   only for vitamins A, D and E. Vitamin A rows in mg or g state carotenoid mass, not vitamin A
   activity, and are not mapped. A label with vitamin A in IU whose forms are not all retinol,
   retinyl acetate or retinyl palmitate is skipped (`UNSUPPORTED_IU_FORM`), because the importer
   rejects such a row.
5. Forms are mapped through `form-map.csv` to the form registry, only for the nutrient the form
   belongs to. When every stated form maps to one registry code that code is used; otherwise
   `form_code` is `UNKNOWN` and the form text goes to `form_label`.
6. `ul_amount_per_serving` is set only when the label states the split:
   - vitamin A: the preformed part, when the label gives the preformed and beta-carotene amounts
     as separate rows; `form_code` is the preformed form. Vitamin A that is beta-carotene only gets
     `form_code` `BETA_CAROTENE` and no UL amount. Mixed forms without stated amounts keep the UL
     amount empty (the full amount counts as UL-relevant).
   - folate: the folic acid and methylfolate mcg amounts stated next to the folate total (nested
     rows or separate rows); `form_code` is the form of that part.
7. Several rows of the same nutrient in the same unit are summed; rows of one nutrient in
   different units are dropped and counted (`CONFLICTING_UNITS`).
8. Labels with fewer than 5 mapped nutrients are skipped (`TOO_FEW_MAPPED_NUTRIENTS`), and so are
   labels that fail the structural checks of the import schema (`SCHEMA_INVALID`).
9. Label versions are de-duplicated by brand, name and `productVersionCode`; the latest
   `entryDate` is kept (`DUPLICATE_LABEL_VERSION`). At most 100 products are written
   (`OVER_SELECTION_CAP`).

Each product gets the code `DSLD-<label id>`, `source` `DSLD`, `source_ref` (label id),
`source_entry_date` (DSLD entry date), `synthetic` false, availability `AVAILABLE` in `US`, and a
GTIN only when the label UPC has a valid check digit. Offers are not part of the products file.

## Demo prices

DSLD has no prices. `offers.us-demo.synthetic.csv` holds demo offers at the fictional retailers
`Demo Store` and `Demo Market` on reserved example domains, computed by a fixed rule (also written
into `provenance.json`):

- `h` = the first 8 hex digits of `sha256(product code)` as an integer;
- `days` = `units_per_container / (units_per_serving * servings_per_day)`;
- Demo Store: `4.00 + 0.08 * days + 0.25 * mapped nutrients + (h mod 200) / 100` USD, rounded half
  up to cents, in stock;
- Demo Market: listed when `h mod 3 != 0`, at the Demo Store price times
  `1 + ((h div 256) mod 15) / 100`, rounded half up to cents, out of stock when
  `(h div 65536) mod 7 == 0`;
- `checked_at` is midnight UTC of the day the label was retrieved.

## Mapping tables

| File | Columns | Purpose |
|---|---|---|
| `nutrient-map.csv` | `match,key,nutrient_code,note` | `match` is `unii` or `name`; names are compared after lower-casing and replacing every non-alphanumeric run with one space. |
| `form-map.csv` | `form_name,nutrient_code,form_code,note` | Label form text to registry form, per nutrient. `IGNORE` marks qualifier texts such as "Calcium Salt". |
| `unit-map.csv` | `dsld_unit,unit,nutrients,note` | DSLD unit string to accepted unit. `nutrients` is `*`, a `|` list, or a `|` list of `!`-prefixed exclusions. |
| `serving-unit-map.csv` | `dsld_unit,counted_as,note` | Serving units that can be counted per container. |

Unit strings or ingredient names that appear in a new snapshot and are not covered stay unmapped;
`provenance.json` lists every unit string seen and the unmapped ingredient names with their row
counts, which is the starting point for extending the tables.
