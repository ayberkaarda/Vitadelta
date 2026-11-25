package com.vitadelta.api.alerts;

/** {@code outbox_event.event_type} (section 8.6). */
public enum OutboxEventType {
  /** Effective offer state of a (product, country) pair changed in an import. */
  OFFERS_CHANGED,
  /** A notification was created; its e-mail is due. */
  NOTIFICATION_CREATED
}
