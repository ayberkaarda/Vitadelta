package com.vitadelta.api.identity;

import com.vitadelta.api.identity.AccountPlanSummary.MonthlyUsageMeter;
import com.vitadelta.api.identity.AccountPlanSummary.PlanLimits;
import com.vitadelta.api.identity.AccountPlanSummary.SubscriptionView;
import com.vitadelta.api.identity.AccountPlanSummary.Usage;
import com.vitadelta.api.identity.AccountPlanSummary.UsageMeter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Default {@link AccountUsageProvider}: reads the ACTIVE subscription with its plan and counts the
 * rows of {@code saved_product}, active {@code alert_subscription} rows and this month's (UTC)
 * {@code usage_counter}.
 */
public class JdbcAccountUsageProvider implements AccountUsageProvider {

  private final JdbcClient jdbc;
  private final Clock clock;

  public JdbcAccountUsageProvider(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  private record PlanAndSubscription(PlanLimits plan, SubscriptionView subscription) {}

  @Override
  public AccountPlanSummary summary(UUID userId) {
    PlanAndSubscription active =
        jdbc.sql(
                "SELECT s.id, s.plan_code, s.status, s.source, s.started_at, s.current_period_end,"
                    + " s.canceled_at, p.name, p.saved_products_limit, p.alert_subscriptions_limit,"
                    + " p.notifications_per_month_limit"
                    + " FROM subscription s JOIN plan p ON p.code = s.plan_code"
                    + " WHERE s.user_id = ? AND s.status = 'ACTIVE'")
            .param(userId)
            .query(JdbcAccountUsageProvider::map)
            .optional()
            .orElseThrow(() -> new IllegalStateException("user has no ACTIVE subscription"));
    LocalDate periodMonth = LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1);
    long saved =
        jdbc.sql("SELECT count(*) FROM saved_product WHERE user_id = ?")
            .param(userId)
            .query(Long.class)
            .single();
    long alerts =
        jdbc.sql("SELECT count(*) FROM alert_subscription WHERE user_id = ? AND active")
            .param(userId)
            .query(Long.class)
            .single();
    long notifications =
        jdbc.sql(
                "SELECT coalesce(sum(notifications_created), 0) FROM usage_counter"
                    + " WHERE user_id = ? AND period_month = ?")
            .params(userId, periodMonth)
            .query(Long.class)
            .single();
    PlanLimits plan = active.plan();
    return new AccountPlanSummary(
        plan,
        active.subscription(),
        new Usage(
            new UsageMeter(saved, plan.savedProductsLimit()),
            new UsageMeter(alerts, plan.alertSubscriptionsLimit()),
            new MonthlyUsageMeter(notifications, plan.notificationsPerMonthLimit(), periodMonth)));
  }

  private static PlanAndSubscription map(ResultSet rs, int row) throws SQLException {
    String planCode = rs.getString("plan_code");
    PlanLimits plan =
        new PlanLimits(
            planCode,
            rs.getString("name"),
            rs.getObject("saved_products_limit", Integer.class),
            rs.getObject("alert_subscriptions_limit", Integer.class),
            rs.getObject("notifications_per_month_limit", Integer.class));
    SubscriptionView subscription =
        new SubscriptionView(
            rs.getObject("id", UUID.class),
            planCode,
            rs.getString("status"),
            rs.getString("source"),
            UserRepository.instant(rs, "started_at"),
            UserRepository.instant(rs, "current_period_end"),
            UserRepository.instant(rs, "canceled_at"));
    return new PlanAndSubscription(plan, subscription);
  }
}
