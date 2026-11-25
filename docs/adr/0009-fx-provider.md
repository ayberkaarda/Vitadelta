# ADR-0009: FX provider port with Frankfurter v2 (ECB) default, persisted daily rates, display-only conversion

- Status: Accepted
- Date: 2026-11-24

## Context

Prices are stored and ranked in the native currency of each country (TRY, EUR, GBP, USD). Users may want to see money values in another currency. One query targets exactly one country, so conversion never affects ranking. The rate source must be free, need no API key, be replaceable, and its outages must not break queries.

## Decision

**Port.** `FxRateProvider.fetchLatest(baseCurrency, Set<quoteCurrency>) -> FxQuote { base, asOf, rates }` in package `fx`. The port signature does not depend on any provider.

**Default adapter.** `FrankfurterFxRateProvider` against `VITADELTA_FX_BASE_URL` (default `https://api.frankfurter.dev/v2`), with the ECB provider selected explicitly. Connect timeout 2 s, read timeout 5 s, no retries inside the adapter (the job retries on its next run).

**Verified request and response (live calls on 2026-11-24; the API reported version 2.1.1 in `GET /v2/openapi.json`):**

- Request: `GET /v2/rates?base=TRY&quotes=EUR,GBP,USD&providers=ECB`. `base` is a single ISO code (default `EUR` when omitted), `quotes` and `providers` are comma-separated lists. The equivalent path form `GET /v2/providers/ecb/rates?base=TRY&quotes=EUR,GBP,USD` returned an identical body; the provider key is case-insensitive (`ECB`, `ecb`).
- Response: `200`, `application/json`, a JSON **array** of objects `{ "date": "2026-11-23", "base": "TRY", "quote": "EUR", "rate": 0.01807 }`, one per quote. There is no top-level object and no `amount` field in v2. `rate` is a JSON number with ECB precision (4 to 6 significant digits, for example `0.01807`, `0.019767`, `55.3405`, `50.59`); it is parsed as a decimal from the JSON text and stored with 8 decimals. All rows of one call carried the same `date`.
- A single pair is also available as `GET /v2/rate/{base}/{quote}?providers=ECB`, returning one object of the same shape; the adapter uses the list form.
- No API key, no required headers; responses are served with public cache headers.

**Mapping to `FxQuote`.** `base` is the request base; `asOf` is the `date` of the rows (all rows of a call are checked to share it; the adapter rejects the response otherwise); `rates` maps each `quote` to its `rate`.

**Coverage.** TRY, EUR, GBP and USD are all published by the ECB provider (`GET /v2/providers/ecb` lists them in `currencies`, pivot currency EUR, cadence daily). Every base/quote combination of the four returned a rate, and **TRY works as a base** (cross rate through the EUR pivot). All four currencies stay in the supported list.

**Provider must be explicit.** Without `providers`, `GET /v2/rates?base=TRY&quotes=EUR` returned a blended rate across providers with a different date and value (`2026-11-24`, `0.01804`) than ECB (`2026-11-23`, `0.01807`). The adapter therefore always sends `providers=ECB`, so that rates are reproducible ECB reference rates.

**Weekends and holidays.** ECB publishes on working days around 16:00 CET, so the newest date lags (on Tuesday 2026-11-24 the newest ECB date was 2026-11-23). A request for a Saturday or Sunday returns the last published business day, with that day in `date` (2026-11-21 and 2026-11-22 both returned `2026-11-20`). The stored `as_of` is always the row `date`, never the fetch time.

**Error shapes.**

| Case | Status | Body |
|---|---|---|
| Nonexistent currency code in `quotes` or `base` | 422 | `{"status":422,"message":"invalid currency: <code>"}` |
| Unknown query parameter (for example singular `provider=`) | 422 | `{"status":422,"message":"unknown parameter: provider"}` |
| Unknown path or unknown provider in the path | 404 | `{"status":404,"message":"not found"}` |
| Unknown provider as query value (`providers=NOPE`) | 200 | `[]` |
| ISO code the ECB does not publish in `quotes` (for example `ARS`; verified 2026-11-25) | 200 | the rows of the other quotes only; `[]` when no requested quote is published |
| ISO code the ECB does not publish as `base` | 200 | `[]` |

The adapter treats any non-2xx status, an empty array, a missing requested quote or inconsistent dates as a failed fetch. A failure that names requested quotes (a 400/422 body naming them, or quotes left out of a 200 answer) reports them; the refresh job then requests that base once more without them, and when a 400/422 names no currency it requests the quotes one by one. Every dropped quote is logged as an operator warning and counted (`vitadelta.fx.refresh.unsupported{currency}`); it keeps no rate, so conversions to it are `UNAVAILABLE`, while every other pair is updated. A base the provider does not publish still fails (it has no rates at all).

**Refresh job.** `FxRefreshJob` runs under its advisory lock (ADR-0011): at startup when `fx_rate` is empty or the newest `as_of` is older than 1 day, then every 6 hours. For every supported base it requests all other supported currencies and upserts `fx_rate (base, quote, as_of, rate, fetched_at, source = 'FRANKFURTER')`. Failures are logged with a metric and never crash the application; the last stored rates stay in use. `POST /admin/fx/refresh` runs the job immediately and returns the number of upserted rows.

**Persisted rates and lookup.** Queries never call the provider; they read the newest `as_of` row for `(native, display)` from the database. The identity pair has rate 1. Staleness: `as_of` older than `fx_stale_after_days = 5` sets `meta.fx.stale = true` and the product flag `FX_STALE`. No row at all returns `200` in the native currency with `meta.fx.status = UNAVAILABLE` and flag `FX_UNAVAILABLE`. An unsupported display currency returns `422 fx_unsupported_currency`.

**Display-only conversion.** Ranking, locking and paging happen in the native currency; afterwards every monetary field is computed as the unrounded native value times the unrounded stored rate and rounded once to 4 decimals. `meta.fx = { base, quote, rate, asOf, source, stale, status }` returns the rate unrounded with its stored 8 decimals; native values are also returned as `*_native` fields. The value score stays per unit of native currency. Because all products of a country share one currency, uniform scaling preserves order; a test asserts that a USD ranking equals the TRY ranking item for item.

## Consequences

- An FX outage never fails a query; at worst values are shown in the native currency or marked stale.
- Rates change at most once per ECB working day; the 6-hour refresh is more than sufficient and keeps traffic to the provider low.
- TRY-base rates carry only about 4 significant digits as published (relative error up to roughly 0.03 %); this is acceptable for display-only conversion, and the shown rate reproduces the displayed values.
- Replacing the provider means a new adapter; the port, the table and the conversion logic stay unchanged.
- Tests use WireMock stubs of the verified v2 shapes, so the adapter is tested without network access.

**Not verified on 2026-11-24:** provider rate limits (none documented in the OpenAPI document or visible in response headers; no load test was made). The behaviour for a real currency that ECB does not publish was verified on 2026-11-25 (left out of a 200 answer, see the table).

## Alternatives considered

- **Frankfurter v1 (`/v1/latest?base=TRY&symbols=...`).** Works and returned the same numbers, but uses a different shape (`amount`, a single `date`, a `rates` map, parameter `symbols`) and is the previous API version; v2 is current and allows explicit provider selection.
- **Blended Frankfurter rates without a provider.** Rejected: different date and value than the ECB reference, not reproducible.
- **Calling the provider on every request.** Rejected: adds latency and makes queries depend on an external service.
- **Commercial FX APIs.** Rejected for v1: need API keys and contracts while display-only conversion does not need intraday rates.
- **Converting prices before ranking.** Rejected: one query targets one country, so conversion cannot change order and would only add rounding.
