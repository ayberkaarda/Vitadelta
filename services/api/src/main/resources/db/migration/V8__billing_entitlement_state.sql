-- Payment provider state per subscription and per checkout (Phase 8, ADR-0014). Depends on V7.
--
-- billing_subscription.paid_through: the latest period end of the subscription that Stripe
--   reported as paid (status active or trialing with a paid latest invoice). It only moves
--   forward and is never raised after the subscription ended. The user's PRO entitlement follows
--   the live subscription with the latest paid_through, so a second subscription of the same user
--   keeps PRO when the first one ends.
-- billing_checkout_session: the user's latest hosted checkout session (session_ref, url,
--   expires_at), expired at Stripe before another one is created so that at most one is payable,
--   and claim_until, a short lease held while one request creates a session, so that concurrent
--   requests never create two.

ALTER TABLE billing_subscription ADD COLUMN paid_through timestamptz;

UPDATE billing_subscription
SET paid_through = current_period_end
WHERE ended_at IS NULL AND status IN ('active', 'trialing');

CREATE TABLE billing_checkout_session (
    user_id     uuid          NOT NULL,
    provider    varchar(20)   NOT NULL,
    session_ref varchar(255),
    url         varchar(2048),
    expires_at  timestamptz,
    claim_until timestamptz,
    created_at  timestamptz   NOT NULL,
    CONSTRAINT billing_checkout_session_pk PRIMARY KEY (user_id),
    CONSTRAINT billing_checkout_session_provider_check CHECK (provider IN ('STRIPE')),
    CONSTRAINT billing_checkout_session_open_check
        CHECK ((session_ref IS NULL) = (url IS NULL) AND (session_ref IS NULL) = (expires_at IS NULL)),
    CONSTRAINT billing_checkout_session_user_fk
        FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
);
