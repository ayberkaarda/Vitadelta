package com.vitadelta.api.identity;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Default {@link SubscriptionProvisioning}: inserts the {@code FREE} subscription row directly
 * (status {@code ACTIVE}, source {@code MANUAL}, no period end).
 */
public class JdbcSubscriptionProvisioning implements SubscriptionProvisioning {

  private final JdbcClient jdbc;

  public JdbcSubscriptionProvisioning(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void provisionFreeSubscription(UUID userId, Instant now) {
    jdbc.sql(
            "INSERT INTO subscription (id, user_id, plan_code, status, source, started_at)"
                + " VALUES (?, ?, 'FREE', 'ACTIVE', 'MANUAL', ?)")
        .params(UUID.randomUUID(), userId, UserRepository.ts(now))
        .update();
  }
}
