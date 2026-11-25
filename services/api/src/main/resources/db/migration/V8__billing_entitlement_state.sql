-- Paid horizon per payment-provider subscription (Phase 8, ADR-0014). Depends on V7.
--
-- billing_subscription.paid_through: the latest period end of the subscription that Stripe
--   reported as paid (status active or trialing and a paid latest invoice), set only from a
--   webhook read-back of the subscription. It only moves forward and is never raised after the
--   subscription ended. The user's PRO entitlement follows the live subscription with the latest
--   paid_through.
--
-- Existing rows get no value: their stored status and period end are no proof of payment (Stripe
-- keeps a subscription active while a renewal invoice is open). Until the next read-back of such a
-- subscription its PRO row keeps its period end and is neither extended nor ended.

ALTER TABLE billing_subscription ADD COLUMN paid_through timestamptz;
