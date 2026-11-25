-- Stored FX rates for display-currency conversion (section 7.7, ADR-0009).
-- One row per (base, quote, as_of): as_of is the provider's rate date, never the fetch time.
-- Rows are written only by FxRefreshJob (upsert on the primary key); queries read the newest
-- as_of per pair and never call the provider. This migration does not depend on V3 or V5
-- (Flyway runs out-of-order, ADR-0013).

CREATE TABLE fx_rate (
    base_currency  varchar(3)     NOT NULL,
    quote_currency varchar(3)     NOT NULL,
    as_of          date           NOT NULL,
    rate           numeric(18, 8) NOT NULL,
    fetched_at     timestamptz    NOT NULL,
    source         varchar(40)    NOT NULL,
    CONSTRAINT fx_rate_pk PRIMARY KEY (base_currency, quote_currency, as_of),
    -- NaN compares greater than every number in PostgreSQL, so it is excluded explicitly.
    CONSTRAINT fx_rate_rate_positive CHECK (rate > 0 AND rate <> 'NaN'::numeric),
    CONSTRAINT fx_rate_base_format CHECK (base_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT fx_rate_quote_format CHECK (quote_currency ~ '^[A-Z]{3}$'),
    -- The identity pair has rate 1 by definition and is never stored.
    CONSTRAINT fx_rate_distinct_pair CHECK (base_currency <> quote_currency),
    CONSTRAINT fx_rate_source_format CHECK (source ~ '^[A-Z][A-Z0-9_]{0,39}$')
);

-- Newest rate of a pair: the first entry of (base, quote) in as_of descending order.
CREATE INDEX fx_rate_pair_newest_idx ON fx_rate (base_currency, quote_currency, as_of DESC);
