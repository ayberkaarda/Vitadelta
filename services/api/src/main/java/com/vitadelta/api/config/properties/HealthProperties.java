package com.vitadelta.api.config.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Health reporting ({@code GET /health}, {@code GET /admin/health}) and the scoring-engine
 * parameters of {@code GET /weights/defaults}.
 *
 * @param publicCacheTtl how long the public {@code GET /health} reuses a computed overall status
 *     (10 s), so that public polling cannot multiply calls to the scoring service or the database
 * @param outboxMaxPendingAge the outbox is {@code DEGRADED} when its oldest pending event is older
 *     than this (5 min)
 * @param engineRetryInterval while no engine parameters were ever obtained from the scoring
 *     service, a request for them tries again at most this often (30 s)
 */
@ConfigurationProperties(prefix = "vitadelta.health")
public record HealthProperties(
    @DefaultValue("10s") Duration publicCacheTtl,
    @DefaultValue("5m") Duration outboxMaxPendingAge,
    @DefaultValue("30s") Duration engineRetryInterval) {

  public HealthProperties {
    requirePositive(publicCacheTtl, "vitadelta.health.public-cache-ttl");
    requirePositive(outboxMaxPendingAge, "vitadelta.health.outbox-max-pending-age");
    requirePositive(engineRetryInterval, "vitadelta.health.engine-retry-interval");
  }

  private static void requirePositive(Duration value, String name) {
    if (value == null || value.isNegative() || value.isZero()) {
      throw new IllegalArgumentException(name + " must be positive: " + value);
    }
  }
}
