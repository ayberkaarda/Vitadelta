-- Identity, plans, subscriptions, promo codes, anonymous trial identities and the limited-resource
-- tables counted by the entitlement engine (project specification section 5.9).
-- Tokens are never stored in plain text: refresh, verification and reset tokens are SHA-256 hashes
-- (64 lowercase hex characters). Deleting an app_user cascades to every row that belongs to it;
-- anonymous_identity.converted_user_id and promo_code.created_by are set NULL.

CREATE EXTENSION IF NOT EXISTS citext;

-- Users --------------------------------------------------------------------------------------

CREATE TABLE app_user (
    id                   uuid         NOT NULL,
    email                citext       NOT NULL,
    password_hash        varchar(255) NOT NULL,
    email_verified_at    timestamptz,
    role                 varchar(10)  NOT NULL DEFAULT 'USER',
    status               varchar(10)  NOT NULL DEFAULT 'ACTIVE',
    default_country_code varchar(2),
    display_currency     varchar(3),
    locale               varchar(2)   NOT NULL DEFAULT 'en',
    email_alerts_enabled boolean      NOT NULL DEFAULT true,
    -- Set on every successful login; unverified accounts that never logged in are purged (8.11).
    last_login_at        timestamptz,
    created_at           timestamptz  NOT NULL,
    updated_at           timestamptz  NOT NULL,
    CONSTRAINT app_user_pk PRIMARY KEY (id),
    CONSTRAINT app_user_email_unique UNIQUE (email),
    CONSTRAINT app_user_email_length CHECK (char_length(email::text) BETWEEN 3 AND 254),
    CONSTRAINT app_user_role_check CHECK (role IN ('USER', 'ADMIN')),
    CONSTRAINT app_user_status_check CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT app_user_locale_check CHECK (locale IN ('tr', 'en')),
    CONSTRAINT app_user_display_currency_format CHECK (display_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT app_user_default_country_fk
        FOREIGN KEY (default_country_code) REFERENCES country (code)
);

CREATE INDEX app_user_role_idx ON app_user (role);
CREATE INDEX app_user_unverified_idx ON app_user (created_at)
    WHERE email_verified_at IS NULL AND last_login_at IS NULL;

CREATE TABLE refresh_token (
    id                  uuid         NOT NULL,
    user_id             uuid         NOT NULL,
    token_hash          varchar(64)  NOT NULL,
    family_id           uuid         NOT NULL,
    expires_at          timestamptz  NOT NULL,
    revoked_at          timestamptz,
    replaced_by         uuid,
    -- AES-GCM encrypted successor token, kept only for the 20-second grace window (8.1).
    grace_successor_enc varchar(200),
    user_agent          varchar(255),
    created_at          timestamptz  NOT NULL,
    CONSTRAINT refresh_token_pk PRIMARY KEY (id),
    CONSTRAINT refresh_token_hash_unique UNIQUE (token_hash),
    CONSTRAINT refresh_token_hash_format CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT refresh_token_user_fk
        FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT refresh_token_replaced_by_fk
        FOREIGN KEY (replaced_by) REFERENCES refresh_token (id) ON DELETE SET NULL,
    CONSTRAINT refresh_token_successor_requires_revocation
        CHECK (grace_successor_enc IS NULL OR (revoked_at IS NOT NULL AND replaced_by IS NOT NULL))
);

CREATE INDEX refresh_token_user_idx ON refresh_token (user_id);
CREATE INDEX refresh_token_family_idx ON refresh_token (family_id);
CREATE INDEX refresh_token_expires_idx ON refresh_token (expires_at);
CREATE INDEX refresh_token_revoked_idx ON refresh_token (revoked_at) WHERE revoked_at IS NOT NULL;
CREATE INDEX refresh_token_grace_idx ON refresh_token (revoked_at)
    WHERE grace_successor_enc IS NOT NULL;

CREATE TABLE email_token (
    id         uuid        NOT NULL,
    user_id    uuid        NOT NULL,
    kind       varchar(20) NOT NULL,
    token_hash varchar(64) NOT NULL,
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL,
    CONSTRAINT email_token_pk PRIMARY KEY (id),
    CONSTRAINT email_token_hash_unique UNIQUE (token_hash),
    CONSTRAINT email_token_hash_format CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT email_token_kind_check CHECK (kind IN ('VERIFY_EMAIL', 'RESET_PASSWORD')),
    CONSTRAINT email_token_user_fk
        FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
);

CREATE INDEX email_token_user_kind_idx ON email_token (user_id, kind);
CREATE INDEX email_token_expires_idx ON email_token (expires_at);
CREATE INDEX email_token_used_idx ON email_token (used_at) WHERE used_at IS NOT NULL;

-- Plans and subscriptions --------------------------------------------------------------------

CREATE TABLE plan (
    code                          varchar(10)   NOT NULL,
    name                          varchar(50)   NOT NULL,
    -- NULL limit means unlimited.
    saved_products_limit          integer,
    alert_subscriptions_limit     integer,
    notifications_per_month_limit integer,
    price_amount                  numeric(12,2),
    price_currency                varchar(3),
    billing_period                varchar(10),
    CONSTRAINT plan_pk PRIMARY KEY (code),
    CONSTRAINT plan_code_check CHECK (code IN ('FREE', 'PRO')),
    CONSTRAINT plan_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT plan_saved_products_limit_check CHECK (saved_products_limit >= 0),
    CONSTRAINT plan_alert_subscriptions_limit_check CHECK (alert_subscriptions_limit >= 0),
    CONSTRAINT plan_notifications_limit_check CHECK (notifications_per_month_limit >= 0),
    CONSTRAINT plan_price_amount_check CHECK (price_amount > 0),
    CONSTRAINT plan_price_currency_format CHECK (price_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT plan_billing_period_check CHECK (billing_period IN ('MONTHLY')),
    CONSTRAINT plan_price_complete CHECK (
        (price_amount IS NULL AND price_currency IS NULL AND billing_period IS NULL)
        OR (price_amount IS NOT NULL AND price_currency IS NOT NULL AND billing_period IS NOT NULL))
);

INSERT INTO plan (code, name, saved_products_limit, alert_subscriptions_limit,
                  notifications_per_month_limit, price_amount, price_currency, billing_period)
VALUES ('FREE', 'Free', 5, 3, 30, NULL, NULL, NULL),
       ('PRO', 'Pro', NULL, NULL, NULL, 199.00, 'TRY', 'MONTHLY');

CREATE TABLE subscription (
    id                 uuid         NOT NULL,
    user_id            uuid         NOT NULL,
    plan_code          varchar(10)  NOT NULL,
    status             varchar(10)  NOT NULL,
    source             varchar(10)  NOT NULL,
    started_at         timestamptz  NOT NULL,
    -- NULL for the open-ended FREE subscription.
    current_period_end timestamptz,
    canceled_at        timestamptz,
    external_ref       varchar(200),
    CONSTRAINT subscription_pk PRIMARY KEY (id),
    CONSTRAINT subscription_status_check CHECK (status IN ('ACTIVE', 'EXPIRED', 'CANCELED')),
    CONSTRAINT subscription_source_check CHECK (source IN ('MANUAL', 'PROMO', 'PROVIDER')),
    CONSTRAINT subscription_period_check
        CHECK (current_period_end IS NULL OR current_period_end > started_at),
    CONSTRAINT subscription_user_fk
        FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT subscription_plan_fk FOREIGN KEY (plan_code) REFERENCES plan (code)
);

-- Every user has exactly one ACTIVE subscription; changes are serialized by locking the user row
-- (8.3), this index is the backstop.
CREATE UNIQUE INDEX subscription_one_active_per_user ON subscription (user_id)
    WHERE status = 'ACTIVE';
CREATE INDEX subscription_user_idx ON subscription (user_id);
CREATE INDEX subscription_active_period_end_idx ON subscription (current_period_end)
    WHERE status = 'ACTIVE' AND current_period_end IS NOT NULL;

CREATE TABLE promo_code (
    -- Case-insensitive: lookups of user-entered codes ignore case (8.8).
    code             citext      NOT NULL,
    plan_code        varchar(10) NOT NULL,
    duration_days    integer     NOT NULL,
    max_redemptions  integer     NOT NULL,
    redeemed_count   integer     NOT NULL DEFAULT 0,
    expires_at       timestamptz,
    created_by       uuid,
    created_at       timestamptz NOT NULL,
    CONSTRAINT promo_code_pk PRIMARY KEY (code),
    CONSTRAINT promo_code_length CHECK (char_length(code::text) BETWEEN 16 AND 40),
    CONSTRAINT promo_code_duration_check CHECK (duration_days > 0),
    CONSTRAINT promo_code_max_redemptions_check CHECK (max_redemptions > 0),
    CONSTRAINT promo_code_redeemed_count_check
        CHECK (redeemed_count >= 0 AND redeemed_count <= max_redemptions),
    CONSTRAINT promo_code_plan_fk FOREIGN KEY (plan_code) REFERENCES plan (code),
    CONSTRAINT promo_code_created_by_fk
        FOREIGN KEY (created_by) REFERENCES app_user (id) ON DELETE SET NULL
);

CREATE TABLE promo_redemption (
    id          uuid        NOT NULL,
    code        citext      NOT NULL,
    user_id     uuid        NOT NULL,
    redeemed_at timestamptz NOT NULL,
    CONSTRAINT promo_redemption_pk PRIMARY KEY (id),
    CONSTRAINT promo_redemption_code_user_unique UNIQUE (code, user_id),
    CONSTRAINT promo_redemption_code_fk
        FOREIGN KEY (code) REFERENCES promo_code (code) ON DELETE CASCADE,
    CONSTRAINT promo_redemption_user_fk
        FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
);

CREATE INDEX promo_redemption_user_idx ON promo_redemption (user_id);

-- Anonymous trial identities -----------------------------------------------------------------

CREATE TABLE anonymous_identity (
    -- SHA-256 of the 128-bit id carried in the signed vd_anon cookie.
    id_hash           varchar(64) NOT NULL,
    queries_used      integer     NOT NULL DEFAULT 0,
    first_seen_at     timestamptz NOT NULL,
    last_seen_at      timestamptz NOT NULL,
    converted_user_id uuid,
    CONSTRAINT anonymous_identity_pk PRIMARY KEY (id_hash),
    CONSTRAINT anonymous_identity_hash_format CHECK (id_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT anonymous_identity_queries_used_check CHECK (queries_used >= 0),
    CONSTRAINT anonymous_identity_converted_user_fk
        FOREIGN KEY (converted_user_id) REFERENCES app_user (id) ON DELETE SET NULL
);

CREATE INDEX anonymous_identity_last_seen_idx ON anonymous_identity (last_seen_at);
CREATE INDEX anonymous_identity_converted_user_idx ON anonymous_identity (converted_user_id)
    WHERE converted_user_id IS NOT NULL;

-- Limited resources counted by the entitlement engine (endpoints arrive in Phase 5) -----------

CREATE TABLE saved_product (
    user_id      uuid        NOT NULL,
    product_id   uuid        NOT NULL,
    country_code varchar(2)  NOT NULL,
    created_at   timestamptz NOT NULL,
    CONSTRAINT saved_product_pk PRIMARY KEY (user_id, product_id, country_code),
    CONSTRAINT saved_product_user_fk
        FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT saved_product_product_fk
        FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE CASCADE,
    CONSTRAINT saved_product_country_fk FOREIGN KEY (country_code) REFERENCES country (code)
);

CREATE TABLE alert_subscription (
    id                uuid          NOT NULL,
    user_id           uuid          NOT NULL,
    product_id        uuid          NOT NULL,
    country_code      varchar(2)    NOT NULL,
    trigger           varchar(20)   NOT NULL,
    -- Required for PRICE_DROP (default 5.00) and NULL for the other triggers.
    threshold_percent numeric(5,2)  DEFAULT 5.00,
    -- Native-currency price of the country; NULL only until a first price exists (8.6).
    baseline_price    numeric(12,2),
    active            boolean       NOT NULL DEFAULT true,
    created_at        timestamptz   NOT NULL,
    last_triggered_at timestamptz,
    CONSTRAINT alert_subscription_pk PRIMARY KEY (id),
    CONSTRAINT alert_subscription_unique UNIQUE (user_id, product_id, country_code, trigger),
    CONSTRAINT alert_subscription_trigger_check
        CHECK (trigger IN ('PRICE_DROP', 'BACK_IN_STOCK', 'PRICE_CHANGE')),
    CONSTRAINT alert_subscription_threshold_presence
        CHECK ((trigger = 'PRICE_DROP') = (threshold_percent IS NOT NULL)),
    CONSTRAINT alert_subscription_threshold_range
        CHECK (threshold_percent > 0 AND threshold_percent < 100),
    CONSTRAINT alert_subscription_baseline_check CHECK (baseline_price > 0),
    CONSTRAINT alert_subscription_user_fk
        FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT alert_subscription_product_fk
        FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE CASCADE,
    CONSTRAINT alert_subscription_country_fk FOREIGN KEY (country_code) REFERENCES country (code)
);

CREATE INDEX alert_subscription_product_country_active_idx
    ON alert_subscription (product_id, country_code) WHERE active;
CREATE INDEX alert_subscription_user_idx ON alert_subscription (user_id);

CREATE TABLE usage_counter (
    user_id               uuid    NOT NULL,
    -- First day of the calendar month (UTC).
    period_month          date    NOT NULL,
    notifications_created integer NOT NULL DEFAULT 0,
    CONSTRAINT usage_counter_pk PRIMARY KEY (user_id, period_month),
    CONSTRAINT usage_counter_period_month_check
        CHECK (period_month = date_trunc('month', period_month)::date),
    CONSTRAINT usage_counter_notifications_check CHECK (notifications_created >= 0),
    CONSTRAINT usage_counter_user_fk
        FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
);
