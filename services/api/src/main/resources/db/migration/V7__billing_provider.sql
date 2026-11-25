-- Payment provider state (Phase 8, ADR-0014). Depends on V2 only.
--
-- billing_customer: the provider customer of a user, created by the provider's hosted checkout and
--   used to open the hosted billing portal. A provider customer belongs to exactly one user.
-- billing_subscription: the last applied state of each provider subscription. It only moves
--   forward: current_period_end never decreases and ended_at, once set, is never cleared, so a
--   late or reordered webhook cannot re-activate or shorten a subscription.
-- billing_webhook_event: provider event ids that were applied (idempotency of webhook deliveries).
--   It holds no personal data and is not linked to a user.
--
-- The rows of a user are deleted with the user (account deletion cascade, section 5.9).

CREATE TABLE billing_customer (
    user_id      uuid         NOT NULL,
    provider     varchar(20)  NOT NULL,
    customer_ref varchar(255) NOT NULL,
    created_at   timestamptz  NOT NULL,
    updated_at   timestamptz  NOT NULL,
    CONSTRAINT billing_customer_pk PRIMARY KEY (user_id),
    CONSTRAINT billing_customer_provider_check CHECK (provider IN ('STRIPE')),
    CONSTRAINT billing_customer_ref_unique UNIQUE (provider, customer_ref),
    CONSTRAINT billing_customer_user_fk
        FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
);

CREATE TABLE billing_subscription (
    provider           varchar(20)  NOT NULL,
    external_ref       varchar(200) NOT NULL,
    user_id            uuid         NOT NULL,
    customer_ref       varchar(255) NOT NULL,
    status             varchar(30)  NOT NULL,
    current_period_end timestamptz,
    ended_at           timestamptz,
    updated_at         timestamptz  NOT NULL,
    CONSTRAINT billing_subscription_pk PRIMARY KEY (provider, external_ref),
    CONSTRAINT billing_subscription_provider_check CHECK (provider IN ('STRIPE')),
    CONSTRAINT billing_subscription_user_fk
        FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
);

CREATE INDEX billing_subscription_user_idx ON billing_subscription (user_id);

CREATE TABLE billing_webhook_event (
    provider     varchar(20)  NOT NULL,
    event_id     varchar(255) NOT NULL,
    event_type   varchar(100) NOT NULL,
    outcome      varchar(30)  NOT NULL,
    processed_at timestamptz  NOT NULL,
    CONSTRAINT billing_webhook_event_pk PRIMARY KEY (provider, event_id),
    CONSTRAINT billing_webhook_event_provider_check CHECK (provider IN ('STRIPE'))
);

-- Provider rows of the subscription table are found by their external reference.
CREATE INDEX subscription_external_ref_idx ON subscription (external_ref)
    WHERE external_ref IS NOT NULL;
