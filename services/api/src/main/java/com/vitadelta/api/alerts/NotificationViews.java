package com.vitadelta.api.alerts;

import tools.jackson.databind.json.JsonMapper;

/** Maps stored notifications to their typed contract schema ({@code Notification} alternatives). */
final class NotificationViews {

  private static final JsonMapper JSON = OffersChangedEmitter.JSON;

  private NotificationViews() {}

  static AlertViews.Notification view(NotificationRepository.StoredNotification n) {
    String type = n.type().name();
    return switch (n.type()) {
      case ALERT_PRICE_DROP, ALERT_BACK_IN_STOCK, ALERT_PRICE_CHANGE ->
          new AlertViews.AlertNotification(
              n.id(),
              type,
              JSON.readValue(n.payload(), NotificationPayloads.AlertNotificationPayload.class),
              n.createdAt(),
              n.readAt());
      case NOTIFICATION_CAP_REACHED ->
          new AlertViews.CapReachedNotification(
              n.id(),
              type,
              JSON.readValue(n.payload(), NotificationPayloads.CapReachedPayload.class),
              n.createdAt(),
              n.readAt());
      case PLAN_ACTIVATED ->
          new AlertViews.PlanActivatedNotification(
              n.id(),
              type,
              JSON.readValue(n.payload(), NotificationPayloads.PlanActivatedPayload.class),
              n.createdAt(),
              n.readAt());
      case PLAN_EXPIRED ->
          new AlertViews.PlanExpiredNotification(
              n.id(),
              type,
              JSON.readValue(n.payload(), NotificationPayloads.PlanExpiredPayload.class),
              n.createdAt(),
              n.readAt());
    };
  }
}
