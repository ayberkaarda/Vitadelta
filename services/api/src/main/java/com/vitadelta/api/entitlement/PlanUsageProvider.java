package com.vitadelta.api.entitlement;

import com.vitadelta.api.identity.AccountPlanSummary;
import com.vitadelta.api.identity.AccountPlanSummary.MonthlyUsageMeter;
import com.vitadelta.api.identity.AccountPlanSummary.Usage;
import com.vitadelta.api.identity.AccountPlanSummary.UsageMeter;
import com.vitadelta.api.identity.AccountUsageProvider;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The plan, ACTIVE subscription and usage meters of {@code GET /me} and {@code GET /session/quota}:
 * limits come from the cached {@link PlanService}, the meters count the real rows of {@code
 * saved_product}, active {@code alert_subscription} and this month's (UTC) {@code usage_counter}.
 * Replaces the identity package's default {@link AccountUsageProvider}.
 */
@Component
public class PlanUsageProvider implements AccountUsageProvider {

  private final SubscriptionRepository subscriptions;
  private final PlanService plans;
  private final UsageRepository usage;
  private final EntitlementService entitlements;

  public PlanUsageProvider(
      SubscriptionRepository subscriptions,
      PlanService plans,
      UsageRepository usage,
      EntitlementService entitlements) {
    this.subscriptions = subscriptions;
    this.plans = plans;
    this.usage = usage;
    this.entitlements = entitlements;
  }

  @Override
  public AccountPlanSummary summary(UUID userId) {
    Subscription active =
        subscriptions
            .findActive(userId)
            .orElseThrow(() -> new IllegalStateException("user has no ACTIVE subscription"));
    Plan plan = entitlements.effectivePlan(active);
    LocalDate month = entitlements.currentPeriodMonth();
    return new AccountPlanSummary(
        plan.limits(),
        active.view(),
        new Usage(
            new UsageMeter(usage.savedProducts(userId), plan.savedProductsLimit()),
            new UsageMeter(usage.activeAlertSubscriptions(userId), plan.alertSubscriptionsLimit()),
            new MonthlyUsageMeter(
                usage.notifications(userId, month), plan.notificationsPerMonthLimit(), month)));
  }
}
