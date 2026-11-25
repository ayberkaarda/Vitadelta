package com.vitadelta.api.entitlement;

import com.vitadelta.api.identity.AccountPlanSummary.SubscriptionView;
import java.time.Instant;
import java.util.UUID;

/**
 * One {@code subscription} row.
 *
 * @param currentPeriodEnd {@code null} for the open-ended FREE subscription
 * @param externalRef the payment provider's subscription id for source {@code PROVIDER}, otherwise
 *     {@code null}; never part of a response
 */
public record Subscription(
    UUID id,
    UUID userId,
    PlanCode planCode,
    SubscriptionStatus status,
    SubscriptionSource source,
    Instant startedAt,
    Instant currentPeriodEnd,
    Instant canceledAt,
    String externalRef) {

  /** A row without an external reference (FREE, promo and manual subscriptions). */
  public Subscription(
      UUID id,
      UUID userId,
      PlanCode planCode,
      SubscriptionStatus status,
      SubscriptionSource source,
      Instant startedAt,
      Instant currentPeriodEnd,
      Instant canceledAt) {
    this(id, userId, planCode, status, source, startedAt, currentPeriodEnd, canceledAt, null);
  }

  /** The row with another period end. */
  public Subscription withPeriodEnd(Instant periodEnd) {
    return new Subscription(
        id, userId, planCode, status, source, startedAt, periodEnd, canceledAt, externalRef);
  }

  /** Whether this is the PRO row of the provider subscription {@code ref}. */
  public boolean isProviderSubscription(String ref) {
    return planCode == PlanCode.PRO
        && source == SubscriptionSource.PROVIDER
        && externalRef != null
        && externalRef.equals(ref);
  }

  /** The contract schema {@code Subscription}. */
  public SubscriptionView view() {
    return new SubscriptionView(
        id, planCode.name(), status.name(), source.name(), startedAt, currentPeriodEnd, canceledAt);
  }
}
