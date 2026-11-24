package com.vitadelta.api.entitlement;

import com.vitadelta.api.common.problem.ProblemCode;
import com.vitadelta.api.common.problem.ProblemException;
import com.vitadelta.api.identity.SubscriptionProvisioning;
import com.vitadelta.api.identity.UserAccount;
import com.vitadelta.api.identity.UserRepository;
import com.vitadelta.api.mail.AuthMailDispatcher;
import com.vitadelta.api.mail.OutgoingMail;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Subscription lifecycle (section 8.3): the FREE subscription of a new account, PRO activation and
 * extension, and the downgrade of an expired PRO subscription.
 *
 * <p><b>Serialization:</b> every change runs in a transaction that first locks the user's row
 * ({@code SELECT ... FROM app_user WHERE id = ? FOR UPDATE}), then re-reads the ACTIVE subscription
 * and applies the change, so concurrent changes of one user run one after another and no extension
 * is lost. The partial unique index {@code subscription_one_active_per_user} is the backstop only;
 * a violation becomes {@code 409 conflict_retry}.
 *
 * <p>Mails ({@code plan-activated}, {@code plan-expired}) are handed to {@link AuthMailDispatcher}
 * after commit; {@link SubscriptionActivatedEvent} and {@link SubscriptionExpiredEvent} are
 * published inside the transaction.
 */
@Service
public class SubscriptionService implements SubscriptionProvisioning {

  private final SubscriptionRepository subscriptions;
  private final UserRepository users;
  private final PlanService plans;
  private final UsageRepository usage;
  private final AuthMailDispatcher mail;
  private final ApplicationEventPublisher events;
  private final Clock clock;
  private final TransactionTemplate transactions;

  public SubscriptionService(
      SubscriptionRepository subscriptions,
      UserRepository users,
      PlanService plans,
      UsageRepository usage,
      AuthMailDispatcher mail,
      ApplicationEventPublisher events,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.subscriptions = subscriptions;
    this.users = users;
    this.plans = plans;
    this.usage = usage;
    this.mail = mail;
    this.events = events;
    this.clock = clock;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /** The outcome of {@link #activate}. */
  public record Activation(Subscription subscription, boolean extended) {}

  /**
   * Inserts the ACTIVE, open-ended FREE subscription (source {@code MANUAL}) of a new account,
   * inside the registration or admin-bootstrap transaction.
   */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void provisionFreeSubscription(UUID userId, Instant now) {
    insertActive(
        new Subscription(
            UUID.randomUUID(),
            userId,
            PlanCode.FREE,
            SubscriptionStatus.ACTIVE,
            SubscriptionSource.MANUAL,
            now,
            null,
            null));
  }

  /**
   * Activates PRO for {@code durationDays} or extends the current PRO period, inside the caller's
   * transaction (which should already hold the user's row lock; it is taken again here, which is a
   * no-op for the lock holder).
   *
   * <ul>
   *   <li>ACTIVE PRO: {@code current_period_end = max(current_period_end, now) + duration} (a
   *       period that ended but was not yet downgraded by the expiry job restarts from now);
   *   <li>otherwise: the ACTIVE row becomes {@code CANCELED} and an ACTIVE PRO row with {@code
   *       current_period_end = now + duration} is inserted.
   * </ul>
   *
   * @throws ProblemException {@code 409 conflict_retry} when the unique index rejects the insert
   * @throws IllegalArgumentException for another plan than PRO or a non-positive duration
   * @throws IllegalStateException when the user does not exist
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Activation activate(
      UUID userId, PlanCode planCode, int durationDays, SubscriptionSource source) {
    if (planCode != PlanCode.PRO) {
      throw new IllegalArgumentException("only PRO can be activated: " + planCode);
    }
    if (durationDays < 1) {
      throw new IllegalArgumentException("duration must be at least one day");
    }
    Objects.requireNonNull(source, "source");
    UserAccount user =
        users
            .findByIdForUpdate(userId)
            .orElseThrow(() -> new IllegalStateException("user does not exist"));
    Instant now = clock.instant();
    Duration duration = Duration.ofDays(durationDays);
    Optional<Subscription> active = subscriptions.findActive(userId);
    Activation activation;
    if (active.isPresent()
        && active.get().planCode() == PlanCode.PRO
        && active.get().currentPeriodEnd() != null) {
      Instant end = active.get().currentPeriodEnd();
      Instant extendedEnd = (end.isAfter(now) ? end : now).plus(duration);
      subscriptions.extend(active.get().id(), extendedEnd);
      activation = new Activation(active.get().withPeriodEnd(extendedEnd), true);
    } else {
      active.ifPresent(current -> subscriptions.cancel(current.id(), now));
      Subscription pro =
          new Subscription(
              UUID.randomUUID(),
              userId,
              PlanCode.PRO,
              SubscriptionStatus.ACTIVE,
              source,
              now,
              now.plus(duration),
              null);
      insertActive(pro);
      activation = new Activation(pro, false);
    }
    Subscription result = activation.subscription();
    mail.dispatchAfterCommit(
        OutgoingMail.planActivated(
            user.email(), locale(user), plans.get(PlanCode.PRO).name(), result.currentPeriodEnd()));
    events.publishEvent(
        new SubscriptionActivatedEvent(
            userId,
            result.id(),
            result.planCode(),
            result.source(),
            result.currentPeriodEnd(),
            activation.extended()));
    return activation;
  }

  /**
   * Downgrades the user when the ACTIVE PRO subscription has ended, in its own transaction with the
   * user's row lock: the condition {@code current_period_end < now} is re-checked after locking (a
   * concurrent extension wins). The PRO row becomes {@code EXPIRED}, an ACTIVE FREE row (source
   * {@code MANUAL}) is inserted, active alert subscriptions beyond the FREE limit are deactivated
   * starting from the newest, nothing is deleted. The {@code plan-expired} mail is queued after
   * commit.
   *
   * @return the published event, or empty when nothing was due (or the user no longer exists)
   */
  public Optional<SubscriptionExpiredEvent> expireIfDue(UUID userId) {
    return transactions.execute(status -> downgradeIfDue(userId));
  }

  private Optional<SubscriptionExpiredEvent> downgradeIfDue(UUID userId) {
    Optional<UserAccount> user = users.findByIdForUpdate(userId);
    if (user.isEmpty()) {
      return Optional.empty();
    }
    Instant now = clock.instant();
    Optional<Subscription> active = subscriptions.findActive(userId);
    if (active.isEmpty()
        || active.get().planCode() != PlanCode.PRO
        || active.get().currentPeriodEnd() == null
        || !active.get().currentPeriodEnd().isBefore(now)) {
      return Optional.empty();
    }
    subscriptions.expire(active.get().id());
    Subscription free =
        new Subscription(
            UUID.randomUUID(),
            userId,
            PlanCode.FREE,
            SubscriptionStatus.ACTIVE,
            SubscriptionSource.MANUAL,
            now,
            null,
            null);
    insertActive(free);
    Integer alertLimit = plans.get(PlanCode.FREE).alertSubscriptionsLimit();
    int deactivated =
        alertLimit == null ? 0 : usage.deactivateAlertSubscriptionsBeyond(userId, alertLimit);
    mail.dispatchAfterCommit(
        OutgoingMail.planExpired(
            user.get().email(), locale(user.get()), plans.get(PlanCode.PRO).name()));
    SubscriptionExpiredEvent event =
        new SubscriptionExpiredEvent(userId, active.get().id(), free.id(), deactivated);
    events.publishEvent(event);
    return Optional.of(event);
  }

  private void insertActive(Subscription subscription) {
    try {
      subscriptions.insert(subscription);
    } catch (DuplicateKeyException e) {
      throw ProblemException.of(
          ProblemCode.CONFLICT_RETRY,
          "A concurrent subscription change took place. Retry the request.");
    }
  }

  private static Locale locale(UserAccount user) {
    return Locale.forLanguageTag(user.locale());
  }
}
