package com.vitadelta.api.entitlement;

import java.time.LocalDate;
import java.util.OptionalInt;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Counts and counter updates of the limited-resource tables {@code saved_product}, {@code
 * alert_subscription} and {@code usage_counter} (section 5.9).
 */
@Repository
public class UsageRepository {

  private final JdbcClient jdbc;

  public UsageRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Saved products of the user, over every country. */
  public long savedProducts(UUID userId) {
    return jdbc.sql("SELECT count(*) FROM saved_product WHERE user_id = ?")
        .param(userId)
        .query(Long.class)
        .single();
  }

  /** Active alert subscriptions of the user. */
  public long activeAlertSubscriptions(UUID userId) {
    return jdbc.sql("SELECT count(*) FROM alert_subscription WHERE user_id = ? AND active")
        .param(userId)
        .query(Long.class)
        .single();
  }

  /** Notifications counted for the user in {@code periodMonth}. */
  public long notifications(UUID userId, LocalDate periodMonth) {
    return jdbc.sql(
            "SELECT coalesce(sum(notifications_created), 0) FROM usage_counter"
                + " WHERE user_id = ? AND period_month = ?")
        .params(userId, periodMonth)
        .query(Long.class)
        .single();
  }

  /**
   * Counts one notification in {@code periodMonth} with one atomic statement, but only while the
   * counter is below {@code limit} ({@code null} = unlimited, always counted).
   *
   * @return the new counter value, or empty when the limit is reached
   */
  public OptionalInt incrementNotifications(UUID userId, LocalDate periodMonth, Integer limit) {
    if (limit == null) {
      return OptionalInt.of(
          jdbc.sql(
                  "INSERT INTO usage_counter (user_id, period_month, notifications_created)"
                      + " VALUES (?, ?, 1) ON CONFLICT (user_id, period_month) DO UPDATE SET"
                      + " notifications_created = usage_counter.notifications_created + 1"
                      + " RETURNING notifications_created")
              .params(userId, periodMonth)
              .query(Integer.class)
              .single());
    }
    if (limit <= 0) {
      return OptionalInt.empty();
    }
    return jdbc.sql(
            "INSERT INTO usage_counter (user_id, period_month, notifications_created)"
                + " VALUES (?, ?, 1) ON CONFLICT (user_id, period_month) DO UPDATE SET"
                + " notifications_created = usage_counter.notifications_created + 1"
                + " WHERE usage_counter.notifications_created < ?"
                + " RETURNING notifications_created")
        .params(userId, periodMonth, limit)
        .query(Integer.class)
        .optional()
        .map(OptionalInt::of)
        .orElseGet(OptionalInt::empty);
  }

  /**
   * Deactivates the active alert subscriptions of the user beyond the oldest {@code keep} ones, so
   * the newest go first (downgrade rule of section 8.3). Ties on {@code created_at} are broken by
   * id for a deterministic order.
   *
   * @return the number of deactivated subscriptions
   */
  public int deactivateAlertSubscriptionsBeyond(UUID userId, int keep) {
    return jdbc.sql(
            "UPDATE alert_subscription SET active = false WHERE id IN (SELECT id FROM"
                + " alert_subscription WHERE user_id = ? AND active"
                + " ORDER BY created_at, id OFFSET ?)")
        .params(userId, keep)
        .update();
  }
}
