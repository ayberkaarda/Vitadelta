# Offers CSV import format

The admin offers import (`POST /api/v1/admin/imports/offers`, `multipart/form-data`, ADMIN only)
accepts one CSV file in the format below. Each data row is one offer: the price of one product at
one retailer in one country. The import creates an import job of kind `OFFERS_CSV`; its status,
summary and row errors are read with `GET /api/v1/admin/imports/{jobId}`.

## File format

| Rule | Value |
|---|---|
| Encoding | UTF-8. A leading byte order mark (`EF BB BF`) is accepted and ignored. Invalid UTF-8 fails the whole file (`invalid_encoding`). |
| Size limit | 20 MB (20 971 520 bytes). Larger uploads are rejected before processing with `413` (problem+json); no job rows are written. |
| Dialect | RFC 4180. Delimiter `,` (comma). Line endings `CRLF` or `LF`; a final line break is optional. |
| Quoting | A field containing a comma, a double quote, `CR` or `LF` is enclosed in double quotes; a double quote inside a quoted field is written as two double quotes (`""`). Any other field may be quoted. |
| Whitespace | Significant inside quotes. Unquoted leading or trailing spaces and tabs are trimmed. |
| Header | The first record is the header. It must contain exactly the eight column names below, each once, in the order given, written in lower case (`invalid_header` otherwise; the whole file fails). |
| Empty lines | Lines that are completely empty are skipped and are not counted as rows. |
| Rows | Every data record has exactly eight fields (`wrong_column_count` otherwise). A file with a header and no data rows is valid and changes nothing. |
| Row numbers | `row_number` in row errors is the 1-based record number in the file, header included, so the first data row is row 2. |

## Columns

| # | Column | Type | Required | Rules | Example |
|---|---|---|---|---|---|
| 1 | `product_code` | string | yes | Pattern `^[A-Z0-9][A-Z0-9-]{1,39}$`; must be the `code` of an existing product (`unknown_product_code`). | `GOLD-A` |
| 2 | `country_code` | string | yes | ISO 3166-1 alpha-2, upper case, must exist in the country registry (`unknown_country`). | `TR` |
| 3 | `retailer` | string | yes | 1 to 100 characters after trimming; part of the natural key, compared case-sensitively (binary). | `Fixture Pharmacy` |
| 4 | `price` | decimal | yes | Plain decimal with `.` as the separator, no thousands separator, no currency symbol, at most 2 decimals, `> 0`, at most `9999999999.99` (`invalid_price`). | `800.00` |
| 5 | `currency` | string | yes | ISO 4217 alphabetic code, upper case; must equal the country's currency (`currency_mismatch`). | `TRY` |
| 6 | `in_stock` | boolean | yes | `true` or `false`, lower case (`invalid_in_stock`). | `true` |
| 7 | `url` | string | yes | Absolute URI with scheme `http` or `https` (scheme case-insensitive) and a non-empty host, at most 2048 characters. Any other scheme (`javascript:`, `data:`, `ftp:`), relative references and unparseable values are rejected (`invalid_offer_url`). | `https://shop.example.com/gold-a` |
| 8 | `checked_at` | timestamp | yes | RFC 3339 date-time with `Z` or a numeric offset (`invalid_checked_at`); stored in UTC; must not be in the future (`checked_at_in_future`). | `2026-11-20T09:00:00Z` |

An empty value in any column is `missing_value`.

## Row errors

Every row is validated before anything is written. All row errors of the file are recorded as
`import_job_error` rows (`row_number`, `message`, where the message starts with the code below).

| Code | Condition |
|---|---|
| `missing_value` | A required column is empty. |
| `wrong_column_count` | The record does not have exactly eight fields. |
| `unknown_product_code` | `product_code` is malformed or no product has that code. |
| `unknown_country` | `country_code` is malformed or not in the country registry. |
| `invalid_price` | `price` is not a plain decimal, has more than 2 decimals, is `<= 0` or exceeds the maximum. |
| `currency_mismatch` | `currency` is malformed or differs from the country's currency. |
| `invalid_in_stock` | `in_stock` is neither `true` nor `false`. |
| `invalid_offer_url` | `url` fails the scheme, host or length rule. |
| `invalid_checked_at` | `checked_at` is not an RFC 3339 date-time with offset. |
| `checked_at_in_future` | `checked_at` is later than the import time. |
| `duplicate_offer_key` | Another row of the file has the same `(product_code, country_code, retailer)`. |

File-level failures (`invalid_encoding`, `invalid_header`) are recorded as a single error with
`row_number` 1.

**All-or-nothing.** If the file has any row error or file-level failure, the job ends `FAILED`,
no offer is inserted, updated or deleted, `catalog_version` is not bumped and no `OFFERS_CHANGED`
event is written. Otherwise the job ends `SUCCEEDED` and the file is applied as described below.

## Upsert and replace-set semantics

The natural key of an offer is `(product_id, country_code, retailer)`, where `product_id` is
resolved from `product_code`. A *pair* is a `(product, country)` combination. The file is applied
in one write transaction:

1. **Upsert.** Every row is inserted when its natural key does not exist, and otherwise updates
   that offer's `price`, `currency`, `in_stock`, `url` and `checked_at`.
2. **Replace-set per pair.** For every pair that appears in at least one row, the offers of that
   pair whose `retailer` does not appear in the file for that pair are deleted. After the import,
   the offer set of such a pair is exactly the set of rows given for it.
3. **Untouched pairs.** Pairs that do not appear in the file keep all their offers, including
   other products in the same country and the same product in other countries.
4. **Catalog version.** A successful import bumps `catalog_version` exactly once, as the last
   statement of the transaction, whatever its row count.
5. **Changed pairs.** For every pair that appears in the file, the effective state is computed
   before and after the import with the effective-price resolution on the `INCLUDE_OUT_OF_STOCK`
   basis: `effectivePrice`, `inStock`, and the selected offer's `retailer` and `url` (lowest
   in-stock price, else lowest out-of-stock price; ties broken by the most recent `checked_at`,
   then the lowest `retailer`). A pair is **changed** when this state differs between before and
   after; a pair that had no offers before has the state "none". One `OFFERS_CHANGED` outbox
   event is written per changed pair, in the same transaction, with the before and after state
   and the new catalog version. Re-importing an identical file changes no pair and writes no event.
   An update that does not alter the effective state (for example a new `checked_at` on the
   selected offer, or a price change at a retailer that is not the cheapest) is not a change.

The job summary reports the counts `rows`, `inserted`, `updated`, `unchanged`, `deleted` and
`changed_pairs`.

## Example

```csv
product_code,country_code,retailer,price,currency,in_stock,url,checked_at
GOLD-A,TR,Fixture Pharmacy,800.00,TRY,true,https://shop.example.com/gold-a,2026-11-20T09:00:00Z
GOLD-B,TR,Fixture Pharmacy,900.00,TRY,true,https://shop.example.com/gold-b,2026-11-20T09:00:00Z
GOLD-B,TR,"Northline Store, Kadikoy",915.50,TRY,false,https://northline.example.com/p?id=gold-b&src=feed,2026-11-19T18:30:00+03:00
ZN40,TR,Fixture Pharmacy,240.00,TRY,true,https://shop.example.com/zn40,2026-11-20T09:00:00Z
```

Applied to a catalog where `GOLD-B` in `TR` also had an offer from `Redwood Market`, this file
upserts four offers, deletes the `Redwood Market` offer of the `(GOLD-B, TR)` pair, and leaves
the offers of every other pair (for example `GOLD-A` in `DE`) untouched.
