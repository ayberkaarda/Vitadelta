package com.vitadelta.api.alerts;

import java.util.Optional;

/**
 * {@code alert_subscription.trigger} (contract schema {@code AlertTrigger}). The declaration order
 * is the order in which a product's subscriptions are listed.
 */
public enum AlertTrigger {
  PRICE_DROP(NotificationType.ALERT_PRICE_DROP),
  BACK_IN_STOCK(NotificationType.ALERT_BACK_IN_STOCK),
  PRICE_CHANGE(NotificationType.ALERT_PRICE_CHANGE);

  private final NotificationType notificationType;

  AlertTrigger(NotificationType notificationType) {
    this.notificationType = notificationType;
  }

  /** The type of the notification this trigger creates. */
  public NotificationType notificationType() {
    return notificationType;
  }

  /** The trigger named {@code value}, empty for anything else. */
  public static Optional<AlertTrigger> parse(String value) {
    for (AlertTrigger trigger : values()) {
      if (trigger.name().equals(value)) {
        return Optional.of(trigger);
      }
    }
    return Optional.empty();
  }
}
