package com.vitadelta.api.alerts;

/** {@code outbox_event.outcome}, set together with {@code processed_at} (section 5.9). */
public enum OutboxOutcome {
  /** Handled successfully. */
  DELIVERED,
  /** The notification or its user no longer exists. */
  SKIPPED_DELETED,
  /** The user may not receive alert e-mail (unverified, disabled or alerts off). */
  SKIPPED_INELIGIBLE,
  /** Failed on every allowed attempt; kept for inspection. */
  DEAD_LETTER
}
