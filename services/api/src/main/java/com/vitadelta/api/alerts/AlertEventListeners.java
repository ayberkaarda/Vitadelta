package com.vitadelta.api.alerts;

import com.vitadelta.api.entitlement.PlanCode;
import com.vitadelta.api.entitlement.SubscriptionActivatedEvent;
import com.vitadelta.api.entitlement.SubscriptionExpiredEvent;
import com.vitadelta.api.identity.UserDeletingEvent;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Synchronous listeners on the identity and entitlement events; each runs inside the publisher's
 * transaction, so its writes commit or roll back with the change.
 *
 * <ul>
 *   <li>{@link SubscriptionActivatedEvent}: the in-app {@code PLAN_ACTIVATED} notification;
 *   <li>{@link SubscriptionExpiredEvent}: the in-app {@code PLAN_EXPIRED} notification;
 *   <li>{@link UserDeletingEvent}: deletes the user's unprocessed outbox events that reference the
 *       user's notifications (section 5.9); the cascade removes the rest.
 * </ul>
 *
 * Plan notifications are never counted against the monthly cap and have no alert e-mail (the plan
 * e-mails are sent by the entitlement package).
 */
@Component
public class AlertEventListeners {

  private static final Logger LOG = LoggerFactory.getLogger(AlertEventListeners.class);

  private final NotificationRepository notifications;
  private final OutboxRepository outbox;
  private final Clock clock;

  public AlertEventListeners(
      NotificationRepository notifications, OutboxRepository outbox, Clock clock) {
    this.notifications = notifications;
    this.outbox = outbox;
    this.clock = clock;
  }

  @EventListener
  public void onActivated(SubscriptionActivatedEvent event) {
    NotificationPayloads.PlanActivatedPayload payload =
        new NotificationPayloads.PlanActivatedPayload(
            event.planCode().name(),
            event.source().name(),
            event.currentPeriodEnd(),
            event.extended());
    notifications.insert(
        event.userId(),
        NotificationType.PLAN_ACTIVATED,
        OffersChangedEmitter.JSON.writeValueAsString(payload),
        clock.instant());
  }

  @EventListener
  public void onExpired(SubscriptionExpiredEvent event) {
    NotificationPayloads.PlanExpiredPayload payload =
        new NotificationPayloads.PlanExpiredPayload(
            PlanCode.PRO.name(), PlanCode.FREE.name(), event.deactivatedAlertSubscriptions());
    notifications.insert(
        event.userId(),
        NotificationType.PLAN_EXPIRED,
        OffersChangedEmitter.JSON.writeValueAsString(payload),
        clock.instant());
  }

  @EventListener
  public void onUserDeleting(UserDeletingEvent event) {
    int deleted = outbox.deletePendingForUser(event.userId());
    if (deleted > 0) {
      LOG.info("Deleted {} pending outbox event(s) of a deleted account", deleted);
    }
  }
}
