-- Ordering guarantees of the alert pipeline (section 8.6, ADR-0008). Depends on V2 and V3.
--
-- 1. alert_subscription.catalog_version_boundary: the catalog version that was committed when the
--    subscription was created, re-activated, had its threshold changed or had its baseline set.
--    An OFFERS_CHANGED event carries the version its import committed with, so an event whose
--    catalogVersion is not greater than the boundary describes a change the subscription's state
--    already reflects and is ignored. Rows that existed before this migration get 0 (every
--    event applies, as before).
-- 2. outbox_event.seq: insertion order. OFFERS_CHANGED events of one (product, country) pair are
--    claimed strictly in this order; an older unprocessed event of the same pair holds the later
--    ones back until it is processed or dead-lettered.

ALTER TABLE alert_subscription
    ADD COLUMN catalog_version_boundary bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT alert_subscription_boundary_check CHECK (catalog_version_boundary >= 0);

CREATE SEQUENCE outbox_event_seq_seq AS bigint;

ALTER TABLE outbox_event ADD COLUMN seq bigint;

-- Existing events keep their creation order.
UPDATE outbox_event e
SET seq = o.position
FROM (SELECT id, row_number() OVER (ORDER BY created_at, id) AS position FROM outbox_event) o
WHERE o.id = e.id;

SELECT setval('outbox_event_seq_seq', coalesce((SELECT max(seq) FROM outbox_event), 0) + 1, false);

ALTER TABLE outbox_event
    ALTER COLUMN seq SET DEFAULT nextval('outbox_event_seq_seq'),
    ALTER COLUMN seq SET NOT NULL;

ALTER SEQUENCE outbox_event_seq_seq OWNED BY outbox_event.seq;

-- Older unprocessed OFFERS_CHANGED event of the same pair (aggregate_id = '<productId>:<country>').
CREATE INDEX outbox_event_pending_pair_idx ON outbox_event (aggregate_id, seq)
    WHERE processed_at IS NULL AND event_type = 'OFFERS_CHANGED';
