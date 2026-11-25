package com.vitadelta.api.alerts;

import com.vitadelta.api.common.logging.EmailMasking;
import com.vitadelta.api.identity.UserAccount;
import com.vitadelta.api.identity.UserRepository;
import com.vitadelta.api.identity.UserStatus;
import com.vitadelta.api.mail.EmailMessage;
import com.vitadelta.api.mail.EmailSender;
import com.vitadelta.api.mail.MailLinks;
import com.vitadelta.api.mail.MailModel;
import com.vitadelta.api.mail.MailTemplateRenderer;
import com.vitadelta.api.mail.OutgoingMail;
import java.time.Clock;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles one {@code NOTIFICATION_CREATED} event (sections 8.6 and 8.7): sends the localized alert
 * or cap e-mail of the notification. It runs without a surrounding transaction; every read is a
 * single auto-committed statement and SMTP is called with no transaction or row lock held.
 *
 * <ul>
 *   <li>notification or user gone: {@code SKIPPED_DELETED} (never retried);
 *   <li>e-mail unverified, user {@code DISABLED} or {@code email_alerts_enabled = false}, or a type
 *       without alert e-mail: {@code SKIPPED_INELIGIBLE};
 *   <li>{@code email_sent_at} already set (re-checked right before sending): {@code DELIVERED}
 *       without sending again;
 *   <li>otherwise the template of the type is rendered in the user's locale and sent synchronously
 *       through the mail port ({@link EmailSender}) with a {@code List-Unsubscribe} header to the
 *       notification preferences; success sets {@code email_sent_at} and {@code DELIVERED} in one
 *       short transaction, a failure records {@code email_error} and is rethrown so the outbox
 *       retry policy applies.
 * </ul>
 */
@Component
public class AlertEmailDispatcher {

  private static final Logger LOG = LoggerFactory.getLogger(AlertEmailDispatcher.class);

  private final NotificationRepository notifications;
  private final OutboxRepository outbox;
  private final UserRepository users;
  private final MailTemplateRenderer renderer;
  private final MailLinks links;
  private final EmailSender sender;
  private final AlertMetrics metrics;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public AlertEmailDispatcher(
      NotificationRepository notifications,
      OutboxRepository outbox,
      UserRepository users,
      MailTemplateRenderer renderer,
      MailLinks links,
      EmailSender sender,
      AlertMetrics metrics,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.notifications = notifications;
    this.outbox = outbox;
    this.users = users;
    this.renderer = renderer;
    this.links = links;
    this.sender = sender;
    this.metrics = metrics;
    this.transactions = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  /**
   * Sends the e-mail of the event's notification and marks the event processed.
   *
   * @return the outcome written to the event
   * @throws RuntimeException when delivery failed (the event is then retried)
   */
  public OutboxOutcome dispatch(OutboxRepository.ClaimedEvent event) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("alert e-mail must not be sent inside a transaction");
    }
    UUID notificationId =
        OffersChangedEmitter.JSON
            .readValue(event.payload(), NotificationPayloads.NotificationCreated.class)
            .notificationId();
    Optional<NotificationRepository.StoredNotification> found = notifications.find(notificationId);
    if (found.isEmpty()) {
      return finish(event, OutboxOutcome.SKIPPED_DELETED);
    }
    NotificationRepository.StoredNotification notification = found.get();
    Optional<UserAccount> user = users.findById(notification.userId());
    if (user.isEmpty()) {
      return finish(event, OutboxOutcome.SKIPPED_DELETED);
    }
    if (notification.emailSentAt() != null) {
      return finish(event, OutboxOutcome.DELIVERED);
    }
    Optional<MailModel> model = model(notification);
    if (model.isEmpty() || !eligible(user.get())) {
      return finish(event, OutboxOutcome.SKIPPED_INELIGIBLE);
    }
    Locale locale = Locale.forLanguageTag(user.get().locale());
    EmailMessage message =
        renderer.render(new OutgoingMail(user.get().email(), locale, model.get()));
    Optional<NotificationRepository.StoredNotification> current =
        notifications.find(notificationId);
    if (current.isEmpty()) {
      return finish(event, OutboxOutcome.SKIPPED_DELETED);
    }
    if (current.get().emailSentAt() != null) {
      return finish(event, OutboxOutcome.DELIVERED);
    }
    try {
      sender.send(withPreferenceLink(message, links.account()));
    } catch (RuntimeException e) {
      metrics.emailFailed(notification.type());
      notifications.markEmailFailed(notificationId, describe(e));
      LOG.warn(
          "Alert e-mail failed (notification={}, type={}, to={}, error={})",
          notificationId,
          notification.type(),
          EmailMasking.mask(user.get().email()),
          describe(e));
      throw e;
    }
    metrics.emailSent(notification.type());
    LOG.info(
        "Alert e-mail sent (notification={}, type={}, to={})",
        notificationId,
        notification.type(),
        EmailMasking.mask(user.get().email()));
    return transactions.execute(status -> complete(event, notificationId));
  }

  /**
   * The completion after a successful send, inside one short transaction. Lock order: the outbox
   * event first, then the notification, the order of the account deletion ({@link
   * OutboxRepository#deletePendingForUser} before the cascade to {@code notification}); the
   * opposite order could deadlock with a deletion that runs at the same moment. An event that the
   * deletion removed (or another run finished) ends the completion without a write.
   */
  private OutboxOutcome complete(OutboxRepository.ClaimedEvent event, UUID notificationId) {
    if (!outbox.lockPending(event.id())) {
      return OutboxOutcome.SKIPPED_DELETED;
    }
    return notifications.markEmailSent(notificationId, clock.instant())
        ? markProcessed(event, OutboxOutcome.DELIVERED)
        : markProcessed(event, OutboxOutcome.SKIPPED_DELETED);
  }

  /** Whether the user may receive alert and cap e-mail (section 8.6). */
  static boolean eligible(UserAccount user) {
    return user.emailVerified() && user.status() == UserStatus.ACTIVE && user.emailAlertsEnabled();
  }

  private static Optional<MailModel> model(NotificationRepository.StoredNotification notification) {
    if (notification.type().isAlert()) {
      return Optional.of(
          new AlertMails.AlertMail(
              OffersChangedEmitter.JSON.readValue(
                  notification.payload(), NotificationPayloads.AlertNotificationPayload.class)));
    }
    if (notification.type() == NotificationType.NOTIFICATION_CAP_REACHED) {
      return Optional.of(
          new AlertMails.CapReachedMail(
              OffersChangedEmitter.JSON.readValue(
                  notification.payload(), NotificationPayloads.CapReachedPayload.class)));
    }
    return Optional.empty();
  }

  private OutboxOutcome finish(OutboxRepository.ClaimedEvent event, OutboxOutcome outcome) {
    if (outcome == OutboxOutcome.SKIPPED_DELETED || outcome == OutboxOutcome.SKIPPED_INELIGIBLE) {
      metrics.emailSkipped(outcome);
    }
    return markProcessed(event, outcome);
  }

  private OutboxOutcome markProcessed(OutboxRepository.ClaimedEvent event, OutboxOutcome outcome) {
    outbox.markProcessed(event.id(), outcome, clock.instant());
    return outcome;
  }

  /**
   * Adds the {@code List-Unsubscribe} header (RFC 2369) pointing to the notification preferences
   * page. The link is a page, not an endpoint, so no one-click {@code List-Unsubscribe-Post} is
   * set.
   *
   * @throws IllegalArgumentException when the link is not a single-line URL without angle brackets
   */
  static EmailMessage withPreferenceLink(EmailMessage message, String preferencesUrl) {
    if (preferencesUrl == null
        || preferencesUrl.isBlank()
        || preferencesUrl.indexOf('<') >= 0
        || preferencesUrl.indexOf('>') >= 0
        || preferencesUrl.indexOf(' ') >= 0) {
      throw new IllegalArgumentException("preference link must be a single URL");
    }
    return message.withHeader(EmailMessage.LIST_UNSUBSCRIBE, "<" + preferencesUrl + ">");
  }

  /** Class names of a failure and its root cause; never the message (it can echo an address). */
  static String describe(Throwable error) {
    Throwable root = error;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    String name = error.getClass().getSimpleName();
    return root == error ? name : name + " (cause " + root.getClass().getSimpleName() + ")";
  }
}
