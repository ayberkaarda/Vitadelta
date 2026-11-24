package com.vitadelta.api.billing;

import com.vitadelta.api.common.problem.ProblemCode;
import com.vitadelta.api.common.problem.ProblemException;
import com.vitadelta.api.entitlement.PlanCode;
import com.vitadelta.api.entitlement.SubscriptionService;
import com.vitadelta.api.entitlement.SubscriptionService.Activation;
import com.vitadelta.api.entitlement.SubscriptionSource;
import com.vitadelta.api.identity.UserAccount;
import com.vitadelta.api.identity.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Promo-code adapter of the billing port (section 8.8). One transaction:
 *
 * <ol>
 *   <li>lock the user's row (section 8.3 serialization); the e-mail must be verified ({@code 403
 *       email_not_verified});
 *   <li>insert {@code promo_redemption} (unique per code and user);
 *   <li>{@code UPDATE promo_code SET redeemed_count = redeemed_count + 1 WHERE code = ? AND
 *       redeemed_count < max_redemptions AND (expires_at IS NULL OR expires_at > now) RETURNING
 *       ...}; zero rows roll the transaction back;
 *   <li>activate or extend PRO for {@code duration_days} (source {@code PROMO}).
 * </ol>
 *
 * A value that is not a well-formed code (checked after the verified e-mail, before any lookup) and
 * every other failure (unknown, expired, exhausted, already redeemed) is the same {@code 400
 * promo_invalid} with the same body; the reason is only logged (without the code) and counted in
 * {@code vitadelta.promo.rejected{reason}}. Successes are counted in {@code
 * vitadelta.promo.redeemed}. The rate limits are applied by the endpoint before this adapter runs.
 */
@Component
public class PromoCodeBillingProvider implements BillingProvider {

  /** Detail of every {@code promo_invalid} response. */
  public static final String INVALID_DETAIL = "The promo code cannot be redeemed.";

  /** Why a redemption was rejected (logged and counted only). */
  enum Rejection {
    MALFORMED,
    UNKNOWN,
    EXPIRED,
    EXHAUSTED,
    ALREADY_REDEEMED,
    UNSUPPORTED_PLAN;

    String tag() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  private static final Logger LOG = LoggerFactory.getLogger(PromoCodeBillingProvider.class);

  /** Format of every generated code; any other value is rejected before a lookup. */
  private static final Pattern CODE_FORMAT = Pattern.compile(PromoCodeGenerator.CODE_REGEX);

  private final UserRepository users;
  private final PromoCodeRepository promoCodes;
  private final SubscriptionService subscriptions;
  private final MeterRegistry meters;
  private final Clock clock;
  private final TransactionTemplate transactions;

  public PromoCodeBillingProvider(
      UserRepository users,
      PromoCodeRepository promoCodes,
      SubscriptionService subscriptions,
      MeterRegistry meters,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.users = users;
    this.promoCodes = promoCodes;
    this.subscriptions = subscriptions;
    this.meters = meters;
    this.clock = clock;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /**
   * Redeems the code of an {@link ActivationRequest.PromoCode} request.
   *
   * @throws ProblemException {@code 400 promo_invalid}, {@code 403 email_not_verified}, {@code 401
   *     unauthorized} (user deleted), {@code 409 conflict_retry}
   */
  @Override
  public ActivationResult activate(ActivationRequest request) {
    if (!(request instanceof ActivationRequest.PromoCode promo)) {
      throw new IllegalArgumentException("not a promo-code request: " + request);
    }
    ActivationResult result = transactions.execute(status -> redeem(promo.userId(), promo.code()));
    meters.counter("vitadelta.promo.redeemed").increment();
    return result;
  }

  private ActivationResult redeem(UUID userId, String code) {
    UserAccount user =
        users
            .findByIdForUpdate(userId)
            .orElseThrow(
                () -> ProblemException.of(ProblemCode.UNAUTHORIZED, "Authentication is required."));
    if (!user.emailVerified()) {
      throw ProblemException.of(
          ProblemCode.EMAIL_NOT_VERIFIED, "Verify your e-mail address to redeem a promo code.");
    }
    if (code == null || !CODE_FORMAT.matcher(code).matches()) {
      throw reject(userId, Rejection.MALFORMED);
    }
    Instant now = clock.instant();
    if (!promoCodes.insertRedemption(code, userId, now)) {
      throw reject(
          userId,
          promoCodes.find(code).isPresent() ? Rejection.ALREADY_REDEEMED : Rejection.UNKNOWN);
    }
    Optional<PromoCodeRepository.Grant> grant = promoCodes.consume(code, now);
    if (grant.isEmpty()) {
      throw reject(
          userId,
          promoCodes
              .find(code)
              .map(found -> found.expired(now) ? Rejection.EXPIRED : Rejection.EXHAUSTED)
              .orElse(Rejection.UNKNOWN));
    }
    if (grant.get().planCode() != PlanCode.PRO) {
      throw reject(userId, Rejection.UNSUPPORTED_PLAN);
    }
    Activation activation =
        subscriptions.activate(
            userId, PlanCode.PRO, grant.get().durationDays(), SubscriptionSource.PROMO);
    return new ActivationResult(PlanCode.PRO, activation.subscription(), activation.extended());
  }

  private ProblemException reject(UUID userId, Rejection reason) {
    meters.counter("vitadelta.promo.rejected", "reason", reason.tag()).increment();
    LOG.info("Promo redemption rejected (reason={}, user={})", reason.tag(), userId);
    return invalid();
  }

  /** The single {@code promo_invalid} problem of every failure. */
  static ProblemException invalid() {
    return ProblemException.of(ProblemCode.PROMO_INVALID, INVALID_DETAIL);
  }
}
