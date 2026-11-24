package com.vitadelta.api.entitlement;

import java.time.LocalDate;

/**
 * Result of {@link EntitlementService#consumeNotification}: whether one more notification may be
 * created this calendar month (UTC).
 *
 * @param used the counter after the call (unchanged when the cap is reached)
 * @param limit the plan's monthly limit, {@code null} for unlimited
 * @param periodMonth first day of the counted month
 */
public record NotificationAllowance(
    Outcome outcome, long used, Integer limit, LocalDate periodMonth) {

  /** The decision. */
  public enum Outcome {
    /** Counted: create the notification. */
    COUNTED,
    /**
     * The monthly cap is reached: do not create the notification; create the single {@code
     * NOTIFICATION_CAP_REACHED} notice of the month unless it exists (it is not counted).
     */
    CAP_REACHED
  }

  /** Whether the notification may be created. */
  public boolean allowed() {
    return outcome == Outcome.COUNTED;
  }
}
