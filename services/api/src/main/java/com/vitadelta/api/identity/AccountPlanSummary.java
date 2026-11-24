package com.vitadelta.api.identity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Plan, ACTIVE subscription and usage meters of one user (contract schemas {@code PlanLimits},
 * {@code Subscription} and {@code Usage}).
 */
public record AccountPlanSummary(
    AccountPlanSummary.PlanLimits plan,
    AccountPlanSummary.SubscriptionView subscription,
    AccountPlanSummary.Usage usage) {

  /** Contract schema {@code PlanLimits}; a {@code null} limit means unlimited. */
  public record PlanLimits(
      String code,
      String name,
      Integer savedProductsLimit,
      Integer alertSubscriptionsLimit,
      Integer notificationsPerMonthLimit) {}

  /** Contract schema {@code Subscription}. */
  public record SubscriptionView(
      UUID id,
      String planCode,
      String status,
      String source,
      Instant startedAt,
      Instant currentPeriodEnd,
      Instant canceledAt) {}

  /** Contract schema {@code UsageMeter}; {@code limit == null} means unlimited. */
  public record UsageMeter(long used, Integer limit) {}

  /** Contract schema {@code MonthlyUsageMeter}. */
  public record MonthlyUsageMeter(long used, Integer limit, LocalDate periodMonth) {}

  /** Contract schema {@code Usage}; {@code alertSubscriptions} counts active subscriptions. */
  public record Usage(
      UsageMeter savedProducts,
      UsageMeter alertSubscriptions,
      MonthlyUsageMeter notificationsThisMonth) {}
}
