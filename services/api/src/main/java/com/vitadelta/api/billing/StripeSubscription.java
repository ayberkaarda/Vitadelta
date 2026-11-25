package com.vitadelta.api.billing;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;

/**
 * The fields of a Stripe subscription that the API uses (API version {@value
 * StripeApi#API_VERSION}).
 *
 * @param id {@code sub_...}
 * @param customer the owning customer ({@code cus_...})
 * @param status Stripe's status ({@code active}, {@code trialing}, {@code past_due}, {@code
 *     canceled}, ...)
 * @param livemode whether the subscription exists in live mode
 * @param userId the value of {@code metadata.user_id}, set by this API at checkout; {@code null}
 *     when absent
 * @param priceIds the prices of the subscription items
 * @param currentPeriodEnd the latest {@code current_period_end} of the items; {@code null} when no
 *     item carries one
 * @param latestInvoiceStatus status of the expanded {@code latest_invoice} ({@code paid}, {@code
 *     open}, ...), {@code null} when the subscription has no invoice
 * @param endedAt when the subscription ended, {@code null} while it has not
 */
public record StripeSubscription(
    String id,
    String customer,
    String status,
    boolean livemode,
    String userId,
    Set<String> priceIds,
    Instant currentPeriodEnd,
    String latestInvoiceStatus,
    Instant endedAt) {

  /** Stripe's terminal statuses: the subscription never becomes active again. */
  private static final Set<String> TERMINAL = Set.of("canceled", "incomplete_expired");

  /** Statuses in which the current period is granted (once its invoice is paid). */
  private static final Set<String> GRANTING = Set.of("active", "trialing");

  public StripeSubscription {
    status = status.toLowerCase(Locale.ROOT);
    priceIds = Set.copyOf(priceIds);
  }

  /** Whether the subscription has ended for good. */
  public boolean terminal() {
    return TERMINAL.contains(status) || endedAt != null;
  }

  /**
   * Whether the current period is paid for: a granting status, and the latest invoice is paid (or
   * there is none). A renewal whose invoice is still open or failed grants nothing until Stripe
   * reports it paid.
   */
  public boolean currentPeriodPaid() {
    return GRANTING.contains(status)
        && (latestInvoiceStatus == null || latestInvoiceStatus.equals("paid"));
  }
}
