package com.vitadelta.api.alerts;

/** {@code notification.type} (contract schema {@code NotificationType}). */
public enum NotificationType {
  ALERT_PRICE_DROP,
  ALERT_BACK_IN_STOCK,
  ALERT_PRICE_CHANGE,
  NOTIFICATION_CAP_REACHED,
  PLAN_ACTIVATED,
  PLAN_EXPIRED;

  /** Every type as written to the contract enumeration. */
  public static final String[] NAMES = {
    "ALERT_PRICE_DROP",
    "ALERT_BACK_IN_STOCK",
    "ALERT_PRICE_CHANGE",
    "NOTIFICATION_CAP_REACHED",
    "PLAN_ACTIVATED",
    "PLAN_EXPIRED"
  };

  /** Whether the type is one of the three alert notifications. */
  public boolean isAlert() {
    return this == ALERT_PRICE_DROP || this == ALERT_BACK_IN_STOCK || this == ALERT_PRICE_CHANGE;
  }
}
