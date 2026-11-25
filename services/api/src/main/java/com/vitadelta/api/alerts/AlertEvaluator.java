package com.vitadelta.api.alerts;

import com.vitadelta.api.entitlement.EntitlementService;
import com.vitadelta.api.entitlement.NotificationAllowance;
import com.vitadelta.api.identity.UserRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles one {@code OFFERS_CHANGED} event in one short transaction (section 8.6, ADR-0008): the
 * active subscriptions of the product and country, the triggers, the monthly cap, the
 * notifications, the {@code alert_delivery} dedupe rows, the follow-up {@code NOTIFICATION_CREATED}
 * events and the event's {@code processed_at} commit together.
 *
 * <p>Triggers: {@code PRICE_DROP} fires when {@code after.effectivePrice <= baseline x (1 -
 * threshold / 100)} with both present (a subscription without baseline stores the first price it
 * sees and does not fire); {@code BACK_IN_STOCK} when {@code before.inStock = false} and {@code
 * after.inStock = true}; {@code PRICE_CHANGE} when both prices are present and differ.
 *
 * <p>Per firing subscription: the user's row is locked first (the order every limit check uses),
 * then the subscription row is re-read under lock; the {@code alert_delivery} row is inserted (a
 * duplicate means the event was already delivered to the subscription, for example after a crash
 * before the commit of the outbox finish, and is skipped); the notification is counted through
 * {@link EntitlementService#consumeNotification}. At the cap the month's single {@code
 * NOTIFICATION_CAP_REACHED} notice is created unless it exists (not counted) and neither {@code
 * last_triggered_at} nor the baseline changes. Otherwise the alert notification is created with the
 * native money snapshot, {@code last_triggered_at} is set, a {@code PRICE_DROP} baseline becomes
 * the triggering price, and a {@code NOTIFICATION_CREATED} event queues the e-mail.
 *
 * <p>An event applies to a subscription only when its {@code catalogVersion} is greater than the
 * subscription's catalog version boundary ({@link #appliesTo}): a subscription created,
 * re-activated or re-based after a change never reports that change. A baseline or firing taken
 * from an event moves the boundary to the event's version. Events of one product and country arrive
 * in version order (see {@link OutboxRepository#CLAIM_SQL}).
 */
@Component
public class AlertEvaluator {

  private static final Logger LOG = LoggerFactory.getLogger(AlertEvaluator.class);

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private final WatchlistRepository watchlist;
  private final NotificationRepository notifications;
  private final OutboxRepository outbox;
  private final EntitlementService entitlements;
  private final UserRepository users;
  private final AlertMetrics metrics;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public AlertEvaluator(
      WatchlistRepository watchlist,
      NotificationRepository notifications,
      OutboxRepository outbox,
      EntitlementService entitlements,
      UserRepository users,
      AlertMetrics metrics,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.watchlist = watchlist;
    this.notifications = notifications;
    this.outbox = outbox;
    this.entitlements = entitlements;
    this.users = users;
    this.metrics = metrics;
    this.transactions = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  /** What one subscription does with an event. */
  enum Decision {
    /** Nothing. */
    NONE,
    /** {@code PRICE_DROP} without baseline: store the first price seen, do not fire. */
    STORE_BASELINE,
    /** The trigger fires. */
    FIRE
  }

  /** The decision of {@code subscription} for a change from {@code before} to {@code after}. */
  static Decision decide(
      AlertTrigger trigger,
      BigDecimal baseline,
      BigDecimal thresholdPercent,
      OfferState before,
      OfferState after) {
    return switch (trigger) {
      case PRICE_DROP -> {
        if (baseline == null) {
          yield after.priced() ? Decision.STORE_BASELINE : Decision.NONE;
        }
        if (!after.priced() || thresholdPercent == null) {
          yield Decision.NONE;
        }
        BigDecimal limit =
            baseline.multiply(BigDecimal.ONE.subtract(thresholdPercent.divide(HUNDRED)));
        yield after.effectivePrice().compareTo(limit) <= 0 ? Decision.FIRE : Decision.NONE;
      }
      case BACK_IN_STOCK ->
          Boolean.FALSE.equals(before.inStock()) && Boolean.TRUE.equals(after.inStock())
              ? Decision.FIRE
              : Decision.NONE;
      case PRICE_CHANGE ->
          before.priced()
                  && after.priced()
                  && before.effectivePrice().compareTo(after.effectivePrice()) != 0
              ? Decision.FIRE
              : Decision.NONE;
    };
  }

  /**
   * Evaluates the event and marks it processed ({@code DELIVERED}) in the same transaction.
   *
   * @return the triggers that produced a notification
   */
  public List<AlertTrigger> evaluate(OutboxRepository.ClaimedEvent event) {
    NotificationPayloads.OffersChanged change =
        OffersChangedEmitter.JSON.readValue(
            event.payload(), NotificationPayloads.OffersChanged.class);
    OfferState before = change.before() == null ? OfferState.NONE : change.before();
    OfferState after = change.after() == null ? OfferState.NONE : change.after();
    List<AlertTrigger> fired = new ArrayList<>();
    int[] capped = {0};
    transactions.executeWithoutResult(status -> {
      Instant now = clock.instant();
      Optional<WatchlistRepository.ProductLabel> label = watchlist.productLabel(change.productId());
      Optional<String> currency = watchlist.countryCurrency(change.countryCode());
      if (label.isPresent() && currency.isPresent()) {
        for (WatchlistRepository.AlertRow candidate :
            watchlist.activeAlertsFor(change.productId(), change.countryCode())) {
          Outcome outcome =
              evaluate(
                  event.id(),
                  change.catalogVersion(),
                  candidate,
                  label.get(),
                  currency.get(),
                  before,
                  after,
                  now);
          if (outcome == Outcome.FIRED) {
            fired.add(candidate.trigger());
          } else if (outcome == Outcome.CAPPED) {
            capped[0]++;
          }
        }
      }
      outbox.markProcessed(event.id(), OutboxOutcome.DELIVERED, now);
    });
    fired.forEach(metrics::fired);
    for (int i = 0; i < capped[0]; i++) {
      metrics.capped();
    }
    if (!fired.isEmpty() || capped[0] > 0) {
      LOG.info(
          "OFFERS_CHANGED {} evaluated: {} notification(s), {} capped",
          event.id(),
          fired.size(),
          capped[0]);
    }
    return List.copyOf(fired);
  }

  private enum Outcome {
    SKIPPED,
    FIRED,
    CAPPED
  }

  /**
   * Whether an event of {@code catalogVersion} describes a change after the subscription's state:
   * its version must be greater than the boundary. The boundary is the committed version read when
   * the state was taken, and an import's events carry the version that import commits with, so an
   * event of the boundary version itself is a change the state already includes.
   */
  static boolean appliesTo(long catalogVersion, WatchlistRepository.AlertRow subscription) {
    return catalogVersion > subscription.catalogVersionBoundary();
  }

  private Outcome evaluate(
      UUID eventId,
      long catalogVersion,
      WatchlistRepository.AlertRow candidate,
      WatchlistRepository.ProductLabel label,
      String currency,
      OfferState before,
      OfferState after,
      Instant now) {
    if (!appliesTo(catalogVersion, candidate)) {
      return Outcome.SKIPPED;
    }
    if (users.findByIdForUpdate(candidate.userId()).isEmpty()) {
      return Outcome.SKIPPED;
    }
    Optional<WatchlistRepository.AlertRow> locked = watchlist.lockActiveAlert(candidate.id());
    if (locked.isEmpty() || !appliesTo(catalogVersion, locked.get())) {
      return Outcome.SKIPPED;
    }
    WatchlistRepository.AlertRow subscription = locked.get();
    Decision decision =
        decide(
            subscription.trigger(),
            subscription.baselinePrice(),
            subscription.thresholdPercent(),
            before,
            after);
    if (decision == Decision.STORE_BASELINE) {
      watchlist.updateBaseline(subscription.id(), after.effectivePrice(), catalogVersion);
      return Outcome.SKIPPED;
    }
    if (decision == Decision.NONE) {
      return Outcome.SKIPPED;
    }
    if (!watchlist.insertDelivery(subscription.id(), eventId, now)) {
      return Outcome.SKIPPED;
    }
    UUID userId = subscription.userId();
    NotificationAllowance allowance = entitlements.consumeNotification(userId);
    if (!allowance.allowed()) {
      createCapNotice(userId, allowance, now);
      return Outcome.CAPPED;
    }
    boolean priceDrop = subscription.trigger() == AlertTrigger.PRICE_DROP;
    NotificationPayloads.AlertNotificationPayload payload =
        new NotificationPayloads.AlertNotificationPayload(
            subscription.id(),
            subscription.trigger().name(),
            label.id(),
            label.code(),
            label.brand(),
            label.name(),
            subscription.countryCode(),
            currency,
            priceDrop ? subscription.thresholdPercent() : null,
            priceDrop ? subscription.baselinePrice() : null,
            before,
            after);
    UUID notificationId =
        notifications.insert(
            userId,
            subscription.trigger().notificationType(),
            OffersChangedEmitter.JSON.writeValueAsString(payload),
            now);
    watchlist.linkDelivery(subscription.id(), eventId, notificationId);
    watchlist.markTriggered(
        subscription.id(), now, priceDrop ? after.effectivePrice() : null, catalogVersion);
    queueEmail(notificationId, now);
    return Outcome.FIRED;
  }

  /**
   * Creates the month's single cap notice unless it exists and queues its e-mail. Joins the
   * caller's transaction.
   */
  void createCapNotice(UUID userId, NotificationAllowance allowance, Instant now) {
    int limit = allowance.limit() == null ? 0 : allowance.limit();
    NotificationPayloads.CapReachedPayload payload =
        new NotificationPayloads.CapReachedPayload(allowance.periodMonth(), limit);
    notifications
        .insertCapNotice(
            userId,
            allowance.periodMonth(),
            OffersChangedEmitter.JSON.writeValueAsString(payload),
            now)
        .ifPresent(id -> queueEmail(id, now));
  }

  private void queueEmail(UUID notificationId, Instant now) {
    outbox.insert(
        OutboxEventType.NOTIFICATION_CREATED,
        OutboxRepository.NOTIFICATION,
        notificationId.toString(),
        OffersChangedEmitter.JSON.writeValueAsString(
            new NotificationPayloads.NotificationCreated(notificationId)),
        now);
  }
}
