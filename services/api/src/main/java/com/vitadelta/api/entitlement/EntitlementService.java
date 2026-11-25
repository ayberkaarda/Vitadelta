package com.vitadelta.api.entitlement;

import com.vitadelta.api.common.problem.ProblemCode;
import com.vitadelta.api.common.problem.ProblemException;
import com.vitadelta.api.identity.AnonymousQuotaService;
import com.vitadelta.api.identity.UserAccount;
import com.vitadelta.api.identity.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.OptionalInt;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single decision point of every limited action (section 8.4, ADR-0007). Controllers never
 * inline limit logic and the web app only renders these decisions.
 *
 * <p><b>Atomic limit checks</b> ({@link #requireSavedProductSlot}, {@link #requireAlertSlot}) must
 * be called inside the transaction that then writes the row: they lock the user's row ({@code
 * SELECT ... FROM app_user WHERE id = ? FOR UPDATE}) and count, so the caller's following insert or
 * {@code active = true} update cannot race another request of the same user:
 *
 * <pre>{@code
 * transactions.executeWithoutResult(tx -> {
 *   if (!savedProducts.exists(userId, productId, country)) {   // duplicates are idempotent
 *     entitlements.requireSavedProductSlot(userId);           // 403 entitlement_exceeded
 *     savedProducts.insert(userId, productId, country);
 *   }
 * });
 * }</pre>
 *
 * Creating an alert calls {@link #requireAlertSlot} and, when its implicit saved-product row is
 * missing, {@link #requireSavedProductSlot} in the same transaction.
 *
 * <p>Every refusal is counted in {@value #REJECTED_METRIC} with the tags {@code action} ({@code
 * SAVE_PRODUCT}, {@code ALERT_SUBSCRIPTION}, {@code ANONYMOUS_QUERY}) and {@code reason} ({@code
 * limit_reached}, {@code email_not_verified}, {@code quota_exhausted}).
 */
@Service
public class EntitlementService {

  /** Counter of refused limited actions, tags {@code action} and {@code reason}. */
  public static final String REJECTED_METRIC = "vitadelta.entitlement.rejected";

  private final UserRepository users;
  private final SubscriptionRepository subscriptions;
  private final PlanService plans;
  private final UsageRepository usage;
  private final AnonymousQuotaService anonymousQuota;
  private final Clock clock;
  private final MeterRegistry meters;

  public EntitlementService(
      UserRepository users,
      SubscriptionRepository subscriptions,
      PlanService plans,
      UsageRepository usage,
      AnonymousQuotaService anonymousQuota,
      Clock clock,
      MeterRegistry meters) {
    this.users = users;
    this.subscriptions = subscriptions;
    this.plans = plans;
    this.usage = usage;
    this.anonymousQuota = anonymousQuota;
    this.clock = clock;
    this.meters = meters;
  }

  /**
   * The plan of the user's ACTIVE subscription. A subscription whose period has ended counts as
   * FREE right away, without waiting for the daily expiry job that rewrites the rows.
   *
   * @throws IllegalStateException when the user has no ACTIVE subscription
   */
  public Plan currentPlan(UUID userId) {
    return subscriptions
        .findActive(userId)
        .map(this::effectivePlan)
        .orElseThrow(() -> new IllegalStateException("user has no ACTIVE subscription"));
  }

  /** The plan whose limits apply to {@code subscription} at the current time. */
  Plan effectivePlan(Subscription subscription) {
    Instant end = subscription.currentPeriodEnd();
    if (end != null && !end.isAfter(clock.instant())) {
      return plans.get(PlanCode.FREE);
    }
    return plans.get(subscription.planCode());
  }

  /** First day of the current calendar month (UTC), the key of {@code usage_counter}. */
  public LocalDate currentPeriodMonth() {
    return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).withDayOfMonth(1);
  }

  /**
   * Locks the user's row and checks that one more saved product fits the plan. Call it only for a
   * product that is not saved yet (saving is idempotent).
   *
   * @throws ProblemException {@code 403 entitlement_exceeded} at the limit, {@code 401
   *     unauthorized} when the user no longer exists
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void requireSavedProductSlot(UUID userId) {
    lockUser(userId);
    Plan plan = currentPlan(userId);
    Integer limit = plan.savedProductsLimit();
    if (limit == null) {
      return;
    }
    long used = usage.savedProducts(userId);
    if (used >= limit) {
      rejected("SAVE_PRODUCT", "limit_reached");
      throw exceeded(
          plan,
          limit,
          used,
          "limits.saved_products_reached",
          "The " + plan.code() + " plan allows " + limit + " saved products.");
    }
  }

  /**
   * Locks the user's row and checks that one more alert subscription may be active: the e-mail must
   * be verified and the active subscriptions must stay within the plan. Call it before inserting an
   * alert subscription or setting one {@code active = true}.
   *
   * @throws ProblemException {@code 403 email_not_verified}, {@code 403 entitlement_exceeded} at
   *     the limit, {@code 401 unauthorized} when the user no longer exists
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void requireAlertSlot(UUID userId) {
    UserAccount user = lockUser(userId);
    if (!user.emailVerified()) {
      rejected("ALERT_SUBSCRIPTION", "email_not_verified");
      throw ProblemException.of(
          ProblemCode.EMAIL_NOT_VERIFIED, "Verify your e-mail address to create alerts.");
    }
    Plan plan = currentPlan(userId);
    Integer limit = plan.alertSubscriptionsLimit();
    if (limit == null) {
      return;
    }
    long used = usage.activeAlertSubscriptions(userId);
    if (used >= limit) {
      rejected("ALERT_SUBSCRIPTION", "limit_reached");
      throw exceeded(
          plan,
          limit,
          used,
          "limits.alert_subscriptions_reached",
          "The " + plan.code() + " plan allows " + limit + " active alerts.");
    }
  }

  /**
   * Counts one notification of the user in the current month through {@code usage_counter} with one
   * atomic statement that increments only while the counter is below the plan's limit (PRO: always
   * counted). Joins the caller's transaction (the alert evaluator's), or runs on its own.
   *
   * <p>The user's row is locked before the plan is read and stays locked until the transaction
   * ends, so a concurrent subscription change (downgrade, activation) runs strictly before or after
   * the reservation: a consumption can never apply the limit of a plan that has already been
   * replaced.
   *
   * @return {@code COUNTED}, or {@code CAP_REACHED} when the notification must not be created and
   *     the month's single cap notice is due unless already created
   * @throws IllegalStateException when the user does not exist
   */
  @Transactional
  public NotificationAllowance consumeNotification(UUID userId) {
    if (users.findByIdForUpdate(userId).isEmpty()) {
      throw new IllegalStateException("user does not exist");
    }
    Plan plan = currentPlan(userId);
    LocalDate month = currentPeriodMonth();
    Integer limit = plan.notificationsPerMonthLimit();
    OptionalInt counted = usage.incrementNotifications(userId, month, limit);
    if (counted.isPresent()) {
      return new NotificationAllowance(
          NotificationAllowance.Outcome.COUNTED, counted.getAsInt(), limit, month);
    }
    return new NotificationAllowance(
        NotificationAllowance.Outcome.CAP_REACHED,
        usage.notifications(userId, month),
        limit,
        month);
  }

  // Anonymous trial (section 8.4): the statements live in the identity package.

  /** Queries an anonymous visitor may run ({@code VITADELTA_ANON_QUERY_LIMIT}). */
  public int anonymousQueryLimit() {
    return anonymousQuota.queryLimit();
  }

  /** Result rows an anonymous visitor sees ({@code VITADELTA_ANON_VISIBLE_RESULTS}). */
  public int anonymousVisibleResults() {
    return anonymousQuota.visibleResults();
  }

  /** Queries the anonymous identity has used. */
  public int anonymousQueriesUsed(String idHash) {
    return anonymousQuota.queriesUsed(idHash);
  }

  /**
   * Reserves one anonymous query in its own short transaction (atomic conditional update).
   *
   * @return the number of used queries including this one
   * @throws ProblemException {@code 403 quota_exceeded} {@code {limit, used, action: SIGN_UP,
   *     upgradeHint}} when the trial is used up
   */
  public int reserveAnonymousQuery(String idHash) {
    OptionalInt used = anonymousQuota.reserve(idHash);
    if (used.isPresent()) {
      return used.getAsInt();
    }
    int limit = anonymousQuota.queryLimit();
    rejected("ANONYMOUS_QUERY", "quota_exhausted");
    throw ProblemException.of(
            ProblemCode.QUOTA_EXCEEDED,
            "The free trial queries are used up. Create an account to continue.")
        .with("limit", limit)
        .with("used", anonymousQuota.queriesUsed(idHash))
        .with("action", "SIGN_UP")
        .with("upgradeHint", UpgradeHint.SIGN_UP);
  }

  /**
   * Returns a reserved anonymous query after a failed scoring call (section 7.6).
   *
   * @return whether a query was returned
   */
  public boolean refundAnonymousQuery(String idHash) {
    return anonymousQuota.refund(idHash);
  }

  private void rejected(String action, String reason) {
    meters.counter(REJECTED_METRIC, "action", action, "reason", reason).increment();
  }

  private UserAccount lockUser(UUID userId) {
    return users
        .findByIdForUpdate(userId)
        .orElseThrow(
            () -> ProblemException.of(ProblemCode.UNAUTHORIZED, "Authentication is required."));
  }

  private static ProblemException exceeded(
      Plan plan, int limit, long used, String messageKey, String detail) {
    return ProblemException.of(ProblemCode.ENTITLEMENT_EXCEEDED, detail)
        .with("limit", limit)
        .with("used", used)
        .with("plan", plan.code().name())
        .with("upgradeTo", PlanCode.PRO.name())
        .with("upgradeHint", new UpgradeHint(messageKey, UpgradeHint.UPGRADE_PATH));
  }
}
