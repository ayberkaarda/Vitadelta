package com.vitadelta.api.alerts;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;

/** Reads timestamp columns of {@code listOfRows()} results. */
final class AlertsTestTimes {

  private AlertsTestTimes() {}

  static Instant instant(Object value) {
    if (value instanceof Timestamp timestamp) {
      return timestamp.toInstant();
    }
    return value == null ? null : ((OffsetDateTime) value).toInstant();
  }
}
