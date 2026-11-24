package com.vitadelta.api.entitlement;

import static com.vitadelta.api.entitlement.SqlTime.instant;
import static com.vitadelta.api.entitlement.SqlTime.ts;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Data access of {@code subscription}. Writes are only issued by {@link SubscriptionService} inside
 * a transaction that holds the user's row lock (section 8.3).
 */
@Repository
public class SubscriptionRepository {

  private static final String COLUMNS =
      "id, user_id, plan_code, status, source, started_at, current_period_end, canceled_at";

  private final JdbcClient jdbc;

  public SubscriptionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** The ACTIVE subscription of the user, if any. */
  public Optional<Subscription> findActive(UUID userId) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM subscription WHERE user_id = ? AND status = 'ACTIVE'")
        .param(userId)
        .query(SubscriptionRepository::map)
        .optional();
  }

  /** Every subscription of the user, oldest first. */
  public List<Subscription> findAll(UUID userId) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM subscription WHERE user_id = ? ORDER BY started_at, id")
        .param(userId)
        .query(SubscriptionRepository::map)
        .list();
  }

  /**
   * Inserts the row.
   *
   * @throws org.springframework.dao.DuplicateKeyException when the user already has an ACTIVE
   *     subscription (partial unique index {@code subscription_one_active_per_user})
   */
  public void insert(Subscription subscription) {
    jdbc.sql(
            "INSERT INTO subscription (id, user_id, plan_code, status, source, started_at,"
                + " current_period_end, canceled_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
        .params(
            subscription.id(),
            subscription.userId(),
            subscription.planCode().name(),
            subscription.status().name(),
            subscription.source().name(),
            ts(subscription.startedAt()),
            ts(subscription.currentPeriodEnd()),
            ts(subscription.canceledAt()))
        .update();
  }

  /** Sets an ACTIVE row to {@code CANCELED}. */
  public void cancel(UUID id, Instant now) {
    jdbc.sql(
            "UPDATE subscription SET status = 'CANCELED', canceled_at = ?"
                + " WHERE id = ? AND status = 'ACTIVE'")
        .params(ts(now), id)
        .update();
  }

  /** Sets an ACTIVE row to {@code EXPIRED}. */
  public void expire(UUID id) {
    jdbc.sql("UPDATE subscription SET status = 'EXPIRED' WHERE id = ? AND status = 'ACTIVE'")
        .param(id)
        .update();
  }

  /** Moves the period end of an ACTIVE row. */
  public void extend(UUID id, Instant periodEnd) {
    jdbc.sql("UPDATE subscription SET current_period_end = ? WHERE id = ? AND status = 'ACTIVE'")
        .params(ts(periodEnd), id)
        .update();
  }

  /**
   * Users whose ACTIVE PRO subscription ended before {@code now}, ordered by user id, after {@code
   * afterUserId} (keyset paging; {@code null} starts at the beginning).
   */
  public List<UUID> expiredCandidates(Instant now, UUID afterUserId, int limit) {
    String due =
        "SELECT user_id FROM subscription WHERE status = 'ACTIVE' AND plan_code = 'PRO'"
            + " AND current_period_end < ?";
    if (afterUserId == null) {
      return jdbc.sql(due + " ORDER BY user_id LIMIT ?")
          .params(ts(now), limit)
          .query(UUID.class)
          .list();
    }
    return jdbc.sql(due + " AND user_id > ? ORDER BY user_id LIMIT ?")
        .params(ts(now), afterUserId, limit)
        .query(UUID.class)
        .list();
  }

  private static Subscription map(ResultSet rs, int row) throws SQLException {
    return new Subscription(
        rs.getObject("id", UUID.class),
        rs.getObject("user_id", UUID.class),
        PlanCode.valueOf(rs.getString("plan_code")),
        SubscriptionStatus.valueOf(rs.getString("status")),
        SubscriptionSource.valueOf(rs.getString("source")),
        instant(rs, "started_at"),
        instant(rs, "current_period_end"),
        instant(rs, "canceled_at"));
  }
}
