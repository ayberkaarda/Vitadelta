package com.vitadelta.api.entitlement;

import com.vitadelta.api.identity.AccountPlanSummary.SubscriptionView;
import java.time.Instant;
import java.util.UUID;

/**
 * One {@code subscription} row.
 *
 * @param currentPeriodEnd {@code null} for the open-ended FREE subscription
 */
public record Subscription(
    UUID id,
    UUID userId,
    PlanCode planCode,
    SubscriptionStatus status,
    SubscriptionSource source,
    Instant startedAt,
    Instant currentPeriodEnd,
    Instant canceledAt) {

  /** The row with another period end. */
  public Subscription withPeriodEnd(Instant periodEnd) {
    return new Subscription(id, userId, planCode, status, source, startedAt, periodEnd, canceledAt);
  }

  /** The contract schema {@code Subscription}. */
  public SubscriptionView view() {
    return new SubscriptionView(
        id, planCode.name(), status.name(), source.name(), startedAt, currentPeriodEnd, canceledAt);
  }
}
