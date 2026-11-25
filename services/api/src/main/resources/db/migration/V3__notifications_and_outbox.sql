-- In-app notifications, the transactional outbox and the alert dedupe table (sections 5.9 and 8.6,
-- ADR-0008). Written only by the alerts package; the offers import inserts OFFERS_CHANGED events
-- inside its own transaction through the OfferChangeListener seam.
-- This migration depends only on V1 and V2 (Flyway runs out-of-order, ADR-0013).

CREATE TABLE notification (
    id               uuid         NOT NULL,
    user_id          uuid         NOT NULL,
    type             varchar(30)  NOT NULL,
    -- The API payload of the type (camelCase); alert payloads are snapshots and never updated.
    payload          jsonb        NOT NULL,
    -- First day of the capped calendar month (UTC); set exactly for NOTIFICATION_CAP_REACHED so
    -- that the single cap notice of a user and month is enforced by a unique index.
    cap_period_month date,
    created_at       timestamptz  NOT NULL,
    read_at          timestamptz,
    email_sent_at    timestamptz,
    -- Class of the last delivery failure; never an address or a server message.
    email_error      varchar(200),
    CONSTRAINT notification_pk PRIMARY KEY (id),
    CONSTRAINT notification_type_check CHECK (type IN ('ALERT_PRICE_DROP', 'ALERT_BACK_IN_STOCK',
        'ALERT_PRICE_CHANGE', 'NOTIFICATION_CAP_REACHED', 'PLAN_ACTIVATED', 'PLAN_EXPIRED')),
    CONSTRAINT notification_payload_object CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT notification_cap_month_presence
        CHECK ((type = 'NOTIFICATION_CAP_REACHED') = (cap_period_month IS NOT NULL)),
    CONSTRAINT notification_cap_month_first_day
        CHECK (cap_period_month = date_trunc('month', cap_period_month)::date),
    CONSTRAINT notification_user_fk
        FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
);

-- Inbox order (created_at desc, id desc) and the unread counter.
CREATE INDEX notification_user_created_idx ON notification (user_id, created_at DESC, id DESC);
CREATE INDEX notification_user_unread_idx ON notification (user_id) WHERE read_at IS NULL;
-- At most one cap notice per user and month (section 8.4), also under concurrency.
CREATE UNIQUE INDEX notification_cap_once_per_month ON notification (user_id, cap_period_month)
    WHERE type = 'NOTIFICATION_CAP_REACHED';

CREATE TABLE outbox_event (
    id              uuid         NOT NULL,
    event_type      varchar(30)  NOT NULL,
    -- PRODUCT_COUNTRY (aggregate_id = '<productId>:<countryCode>') or NOTIFICATION (its id).
    aggregate_type  varchar(30)  NOT NULL,
    aggregate_id    varchar(80)  NOT NULL,
    payload         jsonb        NOT NULL,
    created_at      timestamptz  NOT NULL,
    processed_at    timestamptz,
    outcome         varchar(20),
    attempts        integer      NOT NULL DEFAULT 0,
    -- Class of the last failure; never an address or a server message.
    last_error      varchar(500),
    next_attempt_at timestamptz  NOT NULL,
    -- Claim lease of the outbox processor; an expired lease makes the event claimable again.
    lease_until     timestamptz,
    CONSTRAINT outbox_event_pk PRIMARY KEY (id),
    CONSTRAINT outbox_event_type_check
        CHECK (event_type IN ('OFFERS_CHANGED', 'NOTIFICATION_CREATED')),
    CONSTRAINT outbox_event_aggregate_type_check
        CHECK (aggregate_type IN ('PRODUCT_COUNTRY', 'NOTIFICATION')),
    CONSTRAINT outbox_event_payload_object CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT outbox_event_outcome_check
        CHECK (outcome IN ('DELIVERED', 'SKIPPED_DELETED', 'SKIPPED_INELIGIBLE', 'DEAD_LETTER')),
    -- The outcome is set together with processed_at, and only then.
    CONSTRAINT outbox_event_outcome_with_processed CHECK ((processed_at IS NULL) = (outcome IS NULL)),
    CONSTRAINT outbox_event_attempts_check CHECK (attempts >= 0)
);

-- Due events of the claim query (section 8.6).
CREATE INDEX outbox_event_due_idx ON outbox_event (next_attempt_at) WHERE processed_at IS NULL;
-- Housekeeping by processing time (section 8.11).
CREATE INDEX outbox_event_processed_idx ON outbox_event (processed_at)
    WHERE processed_at IS NOT NULL;
-- Pending NOTIFICATION_CREATED events of a notification (purged with the user, section 5.9).
CREATE INDEX outbox_event_pending_notification_idx ON outbox_event (aggregate_id)
    WHERE processed_at IS NULL AND event_type = 'NOTIFICATION_CREATED';

CREATE TABLE alert_delivery (
    subscription_id uuid        NOT NULL,
    outbox_event_id uuid        NOT NULL,
    -- NULL when the firing was skipped by the monthly cap or the notification no longer exists.
    notification_id uuid,
    created_at      timestamptz NOT NULL,
    -- The dedupe key: one delivery per subscription and OFFERS_CHANGED event.
    CONSTRAINT alert_delivery_pk PRIMARY KEY (subscription_id, outbox_event_id),
    CONSTRAINT alert_delivery_subscription_fk
        FOREIGN KEY (subscription_id) REFERENCES alert_subscription (id) ON DELETE CASCADE,
    CONSTRAINT alert_delivery_event_fk
        FOREIGN KEY (outbox_event_id) REFERENCES outbox_event (id) ON DELETE CASCADE,
    CONSTRAINT alert_delivery_notification_fk
        FOREIGN KEY (notification_id) REFERENCES notification (id) ON DELETE SET NULL
);

CREATE INDEX alert_delivery_event_idx ON alert_delivery (outbox_event_id);
CREATE UNIQUE INDEX alert_delivery_notification_idx ON alert_delivery (notification_id)
    WHERE notification_id IS NOT NULL;
