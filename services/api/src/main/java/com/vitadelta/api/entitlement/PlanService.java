package com.vitadelta.api.entitlement;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The {@code plan} rows (section 8.3), read through a 60-second Caffeine cache: limit changes made
 * in the database take effect within a minute without a release. A {@code null} limit means
 * unlimited. The cache ticks with the application {@link Clock}.
 */
@Service
public class PlanService {

  /** How long loaded plan rows are served from the cache. */
  public static final Duration CACHE_TTL = Duration.ofSeconds(60);

  private static final String KEY = "plans";

  private final JdbcClient jdbc;
  private final Cache<String, Map<PlanCode, Plan>> cache;

  public PlanService(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    Ticker ticker =
        () -> {
          Instant now = clock.instant();
          return now.getEpochSecond() * 1_000_000_000L + now.getNano();
        };
    this.cache = Caffeine.newBuilder().expireAfterWrite(CACHE_TTL).ticker(ticker).build();
  }

  /** Every plan, in the order {@code FREE}, {@code PRO}. */
  public List<Plan> all() {
    return List.copyOf(plans().values());
  }

  /**
   * The plan with {@code code}.
   *
   * @throws IllegalStateException when the plan row is missing (the migration seeds both)
   */
  public Plan get(PlanCode code) {
    Plan plan = plans().get(code);
    if (plan == null) {
      throw new IllegalStateException("plan row missing: " + code);
    }
    return plan;
  }

  /** Drops the cached rows; the next read loads them again. */
  public void invalidate() {
    cache.invalidateAll();
  }

  private Map<PlanCode, Plan> plans() {
    return cache.get(KEY, key -> load());
  }

  private Map<PlanCode, Plan> load() {
    Map<PlanCode, Plan> plans = new EnumMap<>(PlanCode.class);
    jdbc.sql(
            "SELECT code, name, saved_products_limit, alert_subscriptions_limit,"
                + " notifications_per_month_limit, price_amount, price_currency, billing_period"
                + " FROM plan")
        .query(PlanService::map)
        .list()
        .forEach(plan -> plans.put(plan.code(), plan));
    return Collections.unmodifiableMap(plans);
  }

  private static Plan map(ResultSet rs, int row) throws SQLException {
    return new Plan(
        PlanCode.valueOf(rs.getString("code")),
        rs.getString("name"),
        rs.getObject("saved_products_limit", Integer.class),
        rs.getObject("alert_subscriptions_limit", Integer.class),
        rs.getObject("notifications_per_month_limit", Integer.class),
        rs.getBigDecimal("price_amount"),
        rs.getString("price_currency"),
        rs.getString("billing_period"));
  }
}
