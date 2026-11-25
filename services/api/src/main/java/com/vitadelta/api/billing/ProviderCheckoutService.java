package com.vitadelta.api.billing;

import com.vitadelta.api.billing.BillingApi.BillingRedirect;
import com.vitadelta.api.common.problem.ProblemCode;
import com.vitadelta.api.common.problem.ProblemException;
import com.vitadelta.api.common.ratelimit.RateLimitPolicy;
import com.vitadelta.api.common.ratelimit.RateLimiter;
import com.vitadelta.api.config.properties.WebProperties;
import com.vitadelta.api.entitlement.PlanCode;
import com.vitadelta.api.entitlement.SubscriptionRepository;
import com.vitadelta.api.identity.UserAccount;
import com.vitadelta.api.identity.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Starts the payment provider's hosted pages for the signed-in user (ADR-0014): the Checkout
 * Session for PRO ({@code POST /billing/checkout-session}) and the Billing Portal session ({@code
 * POST /billing/portal-session}). Neither changes a subscription; only verified webhooks do.
 *
 * <p>Checks, in order: a provider is configured ({@code 503 billing_unavailable}), the per-user
 * rate limit ({@code 429}), the account exists ({@code 401}); for checkout also a verified e-mail
 * ({@code 403 email_not_verified}), an ACTIVE plan other than PRO and no live provider subscription
 * ({@code 409 plan_already_active}; a past-due subscription is settled in the portal, not by a
 * second one). One checkout per user at a time: the creation is claimed in {@code
 * billing_checkout_session} (a second caller during the creation gets {@code 409 conflict_retry})
 * and the previous session, while still open, is expired at Stripe before a new one is created, so
 * at most one session of a user accepts payment (one that was just paid answers {@code 409
 * plan_already_active}). For the portal an existing Stripe customer is required ({@code 409
 * no_billing_account}). A Stripe failure is {@code 503 billing_unavailable}; its reason is logged.
 * Return URLs are built only from {@code VITADELTA_WEB_BASE_URL}.
 *
 * <p>Metrics: {@code vitadelta.billing.session.created{kind}}, {@code
 * vitadelta.billing.stripe.failed{operation,reason}}.
 */
@Service
public class ProviderCheckoutService {

  /** Where Stripe returns the browser after a completed checkout. */
  static final String SUCCESS_PATH = "/upgrade?checkout=success";

  /** Where Stripe returns the browser after an abandoned checkout. */
  static final String CANCEL_PATH = "/upgrade?checkout=canceled";

  /** Where the billing portal returns the browser. */
  static final String PORTAL_RETURN_PATH = "/account";

  /** How long one caller may take to create a checkout before another caller may start one. */
  static final Duration CLAIM_LEASE = Duration.ofMinutes(1);

  /** Lifetime assumed for a created session when Stripe does not report one (its default). */
  static final Duration DEFAULT_SESSION_LIFETIME = Duration.ofHours(24);

  /** Detail of every {@code billing_unavailable} problem. */
  static final String UNAVAILABLE_DETAIL = "Online payment is not available right now.";

  private static final Logger LOG = LoggerFactory.getLogger(ProviderCheckoutService.class);

  private static final Set<String> CHECKOUT_LOCALES = Set.of("tr", "en");

  private final BillingProperties properties;
  private final StripeApi stripe;
  private final BillingAccountRepository accounts;
  private final UserRepository users;
  private final SubscriptionRepository subscriptions;
  private final RateLimiter rateLimiter;
  private final WebProperties web;
  private final MeterRegistry meters;
  private final Clock clock;

  public ProviderCheckoutService(
      BillingProperties properties,
      StripeApi stripe,
      BillingAccountRepository accounts,
      UserRepository users,
      SubscriptionRepository subscriptions,
      RateLimiter rateLimiter,
      WebProperties web,
      MeterRegistry meters,
      Clock clock) {
    this.properties = properties;
    this.stripe = stripe;
    this.accounts = accounts;
    this.users = users;
    this.subscriptions = subscriptions;
    this.rateLimiter = rateLimiter;
    this.web = web;
    this.meters = meters;
    this.clock = clock;
  }

  /** A Checkout Session for the monthly PRO price, bound to {@code userId}. */
  public BillingRedirect checkout(UUID userId) {
    requireEnabled();
    rateLimiter.check(RateLimitPolicy.CHECKOUT_PER_USER, userId.toString());
    UserAccount user = users.findById(userId).orElseThrow(ProviderCheckoutService::unauthorized);
    if (!user.emailVerified()) {
      throw ProblemException.of(
          ProblemCode.EMAIL_NOT_VERIFIED, "Verify your e-mail address to subscribe.");
    }
    boolean pro =
        subscriptions
            .findActive(userId)
            .map(active -> active.planCode() == PlanCode.PRO)
            .orElse(false);
    if (pro) {
      throw ProblemException.of(ProblemCode.PLAN_ALREADY_ACTIVE, "The PRO plan is already active.");
    }
    if (!accounts.liveSubscriptions(userId).isEmpty()) {
      // A past-due or unpaid subscription is settled in the billing portal, not by a second one.
      throw ProblemException.of(
          ProblemCode.PLAN_ALREADY_ACTIVE,
          "A subscription already exists; manage it in the billing portal.");
    }
    Instant now = clock.instant();
    BillingAccountRepository.CheckoutClaim claim =
        accounts
            .claimCheckout(userId, now.plus(CLAIM_LEASE), now)
            .orElseThrow(() ->
                ProblemException.of(
                    ProblemCode.CONFLICT_RETRY,
                    "A checkout is being prepared. Retry the request."));
    StripeApi.CheckoutSession session;
    try {
      claim.openSession(now).ifPresent(previous -> expirePrevious(userId, previous));
      StripeApi.CheckoutRequest request =
          new StripeApi.CheckoutRequest(
              userId,
              properties.stripe().priceId(),
              accounts.customerOf(userId).orElse(null),
              web.link(SUCCESS_PATH),
              web.link(CANCEL_PATH),
              CHECKOUT_LOCALES.contains(user.locale()) ? user.locale() : "auto");
      session = call("checkout", () -> stripe.createCheckoutSession(request));
    } catch (RuntimeException e) {
      accounts.releaseCheckout(userId);
      throw e;
    }
    Instant expiresAt =
        session.expiresAt() != null ? session.expiresAt() : now.plus(DEFAULT_SESSION_LIFETIME);
    accounts.openCheckout(userId, session.id(), session.url(), expiresAt);
    LOG.info("Checkout session created for user {}", userId);
    return new BillingRedirect(session.url());
  }

  /**
   * Expires the user's previous, still open Checkout Session at Stripe before a new one is created,
   * so that at most one session of the user accepts payment. A session Stripe no longer knows
   * ({@code 404}) is gone already. Any other refusal means the session is no longer open, typically
   * because it was just paid and its webhook has not arrived: {@code 409 plan_already_active}
   * instead of a second payable session. A Stripe outage is {@code 503}.
   */
  private void expirePrevious(UUID userId, String previous) {
    try {
      stripe.expireCheckoutSession(previous);
      LOG.info("Previous checkout session of user {} expired", userId);
    } catch (StripeApiException e) {
      if (e.notFound()) {
        return;
      }
      if (e.reason() == StripeApiException.Reason.HTTP_STATUS && e.status() < 500) {
        LOG.info(
            "Previous checkout session of user {} is no longer open: {}", userId, e.getMessage());
        throw ProblemException.of(
            ProblemCode.PLAN_ALREADY_ACTIVE,
            "A checkout was completed; the plan is activated once the payment is confirmed.");
      }
      meters
          .counter(
              "vitadelta.billing.stripe.failed",
              "operation",
              "expire_checkout",
              "reason",
              e.reason().tag())
          .increment();
      LOG.warn(
          "Previous checkout session of user {} could not be expired: {}", userId, e.getMessage());
      throw unavailable();
    }
  }

  /** A Billing Portal session for the user's Stripe customer. */
  public BillingRedirect portal(UUID userId) {
    requireEnabled();
    rateLimiter.check(RateLimitPolicy.PORTAL_PER_USER, userId.toString());
    if (users.findById(userId).isEmpty()) {
      throw unauthorized();
    }
    String customer =
        accounts
            .customerOf(userId)
            .orElseThrow(() ->
                ProblemException.of(
                    ProblemCode.NO_BILLING_ACCOUNT,
                    "There is no payment account for this user yet."));
    String url =
        call("portal", () -> stripe.createPortalSession(customer, web.link(PORTAL_RETURN_PATH)));
    LOG.info("Billing portal session created for user {}", userId);
    return new BillingRedirect(url);
  }

  private interface StripeCall<T> {
    T run();
  }

  private <T> T call(String kind, StripeCall<T> call) {
    try {
      T result = call.run();
      meters.counter("vitadelta.billing.session.created", "kind", kind).increment();
      return result;
    } catch (StripeApiException e) {
      meters
          .counter("vitadelta.billing.stripe.failed", "operation", kind, "reason", e.reason().tag())
          .increment();
      LOG.warn("Stripe {} session failed: {}", kind, e.getMessage());
      throw unavailable();
    }
  }

  private void requireEnabled() {
    if (!properties.enabled()) {
      throw unavailable();
    }
  }

  private static ProblemException unauthorized() {
    return ProblemException.of(ProblemCode.UNAUTHORIZED, "Authentication is required.");
  }

  static ProblemException unavailable() {
    return ProblemException.of(ProblemCode.BILLING_UNAVAILABLE, UNAVAILABLE_DETAIL);
  }
}
