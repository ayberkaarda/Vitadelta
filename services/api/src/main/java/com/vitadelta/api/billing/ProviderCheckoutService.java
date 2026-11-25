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
 * ({@code 403 email_not_verified}) and an ACTIVE plan other than PRO ({@code 409
 * plan_already_active}); for the portal an existing Stripe customer ({@code 409
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

  public ProviderCheckoutService(
      BillingProperties properties,
      StripeApi stripe,
      BillingAccountRepository accounts,
      UserRepository users,
      SubscriptionRepository subscriptions,
      RateLimiter rateLimiter,
      WebProperties web,
      MeterRegistry meters) {
    this.properties = properties;
    this.stripe = stripe;
    this.accounts = accounts;
    this.users = users;
    this.subscriptions = subscriptions;
    this.rateLimiter = rateLimiter;
    this.web = web;
    this.meters = meters;
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
    StripeApi.CheckoutRequest request =
        new StripeApi.CheckoutRequest(
            userId,
            properties.stripe().priceId(),
            accounts.customerOf(userId).orElse(null),
            web.link(SUCCESS_PATH),
            web.link(CANCEL_PATH),
            CHECKOUT_LOCALES.contains(user.locale()) ? user.locale() : "auto");
    String url = call("checkout", () -> stripe.createCheckoutSession(request));
    LOG.info("Checkout session created for user {}", userId);
    return new BillingRedirect(url);
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

  private interface StripeCall {
    String run();
  }

  private String call(String kind, StripeCall call) {
    try {
      String url = call.run();
      meters.counter("vitadelta.billing.session.created", "kind", kind).increment();
      return url;
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
