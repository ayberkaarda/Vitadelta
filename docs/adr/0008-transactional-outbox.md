# ADR-0008: Transactional outbox for offer-change events and e-mail delivery

- Status: Accepted
- Date: 2026-11-24

## Context

Price and stock alerts fire when an admin import changes offers. The import, the alert evaluation, the in-app notification and the alert e-mail must not get out of step: an event must not be lost if the process crashes after the import commits, and an SMTP call must never hold database locks or transactions open. There is no message broker in the stack.

## Decision

- **Emission in the business transaction.** Inside the offers-import transaction, for every `(product_id, country_code)` whose offer set changed, the API computes the effective-price state before and after and inserts an `outbox_event` of type `OFFERS_CHANGED` with `{ productId, countryCode, before, after, catalogVersion }`. No event is written when nothing changed.
- **Processor.** `OutboxProcessor` runs with `@Scheduled(fixedDelay = 10s)` under its advisory lock (ADR-0011).
- **Claim.** A short transaction selects **up to 100** events where `processed_at IS NULL AND next_attempt_at <= now() AND (lease_until IS NULL OR lease_until < now())` with `FOR UPDATE SKIP LOCKED`, sets `lease_until = now() + 2 minutes` (the **lease**) and commits.
- **Process.** Each claimed event is handled on its own in short transactions. The alert evaluator handles `OFFERS_CHANGED` in one short transaction per event (subscriptions, cap, notifications, `alert_delivery`, a follow-up `NOTIFICATION_CREATED` event and `processed_at` commit together). E-mail dispatch for `NOTIFICATION_CREATED` calls **SMTP outside any transaction or row lock**.
- **Finish.** On success a short transaction sets `processed_at` and `outcome`. On failure it increments `attempts`, stores `last_error`, sets `next_attempt_at = now + 2^attempts minutes` (**exponential backoff**) and clears `lease_until`. After **5 attempts** the event is marked processed with outcome **`DEAD_LETTER`** and the metric `outbox_dead_letter_total` is incremented.
- **Crash recovery.** An expired lease makes the event claimable again.
- **Skips.** E-mail dispatch marks events `SKIPPED_DELETED` when the notification or user no longer exists and `SKIPPED_INELIGIBLE` when the e-mail is unverified, the user is disabled or e-mail alerts are turned off.
- **At-least-once delivery.** `OFFERS_CHANGED` handling is idempotent through the unique `alert_delivery (subscription_id, outbox_event_id)` row; `NOTIFICATION_CREATED` re-checks `notification.email_sent_at` before sending. A duplicate e-mail can therefore occur only when a crash happens between SMTP acceptance and the commit; this is documented and accepted.
- Admin visibility: `GET /admin/outbox/stats` returns pending, failed and dead-letter counts. Housekeeping deletes processed events after 30 days and dead letters after 90 days (ADR-0011).

## Consequences

- Events are never lost once the import commits, without a broker in the stack.
- Alerts are delivered with a delay of up to the processor interval plus processing time; this is acceptable for price alerts.
- Handlers must be idempotent; tests cover claim and lease, lease-expiry re-claim, SMTP outside transactions, backoff and dead-lettering.
- The database carries the event table load; batch size and housekeeping keep it bounded.

## Alternatives considered

- **Sending e-mail and creating notifications directly in the import transaction.** Rejected: SMTP latency would hold the import transaction and its locks, and a rollback after a sent e-mail cannot be undone.
- **Message broker (Kafka, RabbitMQ).** Rejected for v1: an additional infrastructure component for low event volume, and the dual-write problem would still need an outbox.
- **Spring application events after commit only.** Rejected: events in memory are lost on a crash between commit and handling.
- **Exactly-once delivery.** Rejected: not achievable with SMTP; idempotent handlers with at-least-once semantics are the practical alternative.
