package com.vitadelta.api.common.ratelimit;

import java.time.Duration;
import java.util.Objects;

/**
 * A fixed-window style limit of {@code capacity} requests per {@code period} (refilled greedily),
 * applied per key (client IP, normalized e-mail, user id).
 *
 * @param name unique policy name; buckets of different policies never share state
 */
public record RateLimitPolicy(String name, long capacity, Duration period) {

  /** Client IP: {@code POST /auth/login}, 10 per minute. */
  public static final RateLimitPolicy LOGIN_PER_IP = of("login-ip", 10, Duration.ofMinutes(1));

  /** Client IP: {@code POST /auth/register}, 5 per hour. */
  public static final RateLimitPolicy REGISTER_PER_IP = of("register-ip", 5, Duration.ofHours(1));

  /** Client IP: forgot-password and resend-verification together, 5 per hour. */
  public static final RateLimitPolicy FORGOT_RESEND_PER_IP =
      of("forgot-resend-ip", 5, Duration.ofHours(1));

  /** Client IP: verify-email and reset-password together, 10 per hour. */
  public static final RateLimitPolicy VERIFY_RESET_PER_IP =
      of("verify-reset-ip", 10, Duration.ofHours(1));

  /** Client IP: promo redemption, 10 per hour. */
  public static final RateLimitPolicy PROMO_PER_IP = of("promo-ip", 10, Duration.ofHours(1));

  /** User id: promo redemption, 5 per hour. */
  public static final RateLimitPolicy PROMO_PER_USER = of("promo-user", 5, Duration.ofHours(1));

  /** Client IP: anonymous {@code POST /recommendations/query}, 30 per day. */
  public static final RateLimitPolicy ANONYMOUS_QUERY_PER_IP =
      of("anonymous-query-ip", 30, Duration.ofDays(1));

  /**
   * Client IP: anonymous {@code /recommendations/compare} and {@code /products/**}, 120 per minute.
   */
  public static final RateLimitPolicy ANONYMOUS_BROWSE_PER_IP =
      of("anonymous-browse-ip", 120, Duration.ofMinutes(1));

  /** Client IP: {@code POST /auth/refresh}, 60 per minute. */
  public static final RateLimitPolicy REFRESH_PER_IP = of("refresh-ip", 60, Duration.ofMinutes(1));

  /** User id: {@code POST /billing/checkout-session}, 10 per hour. */
  public static final RateLimitPolicy CHECKOUT_PER_USER =
      of("checkout-user", 10, Duration.ofHours(1));

  /** User id: {@code POST /billing/portal-session}, 10 per hour. */
  public static final RateLimitPolicy PORTAL_PER_USER = of("portal-user", 10, Duration.ofHours(1));

  /**
   * Client IP: {@code POST /billing/webhooks/stripe}, 300 per minute. The webhook is exempt from
   * the other limits; this cap is checked before the signature.
   */
  public static final RateLimitPolicy STRIPE_WEBHOOK_PER_IP =
      of("stripe-webhook-ip", 300, Duration.ofMinutes(1));

  public RateLimitPolicy {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(period, "period");
    if (capacity < 1 || period.isNegative() || period.isZero()) {
      throw new IllegalArgumentException("invalid rate limit policy " + name);
    }
  }

  public static RateLimitPolicy of(String name, long capacity, Duration period) {
    return new RateLimitPolicy(name, capacity, period);
  }
}
