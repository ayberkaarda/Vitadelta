package com.vitadelta.api.alerts;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the alerts module (sections 8.6 and 8.11, ADR-0008, ADR-0011). Every value has the
 * specified default; the block {@code vitadelta.alerts} may override them.
 *
 * @param pollInterval fixed delay between two outbox processor runs (10 s)
 * @param claimBatchSize events claimed by one run (100)
 * @param lease claim lease of an event; an expired lease makes it claimable again (2 minutes)
 * @param maxAttempts failed attempts after which an event becomes {@code DEAD_LETTER} (5)
 * @param processedRetention processed events older than this are purged (30 days)
 * @param deadLetterRetention dead letters older than this are purged (90 days)
 * @param housekeepingBatchSize rows deleted per housekeeping transaction (1000)
 */
@ConfigurationProperties(prefix = "vitadelta.alerts")
public record AlertsProperties(
    @DefaultValue("10s") Duration pollInterval,
    @DefaultValue("100") int claimBatchSize,
    @DefaultValue("2m") Duration lease,
    @DefaultValue("5") int maxAttempts,
    @DefaultValue("30d") Duration processedRetention,
    @DefaultValue("90d") Duration deadLetterRetention,
    @DefaultValue("1000") int housekeepingBatchSize) {

  public AlertsProperties {
    requirePositive(pollInterval, "vitadelta.alerts.poll-interval");
    requirePositive(lease, "vitadelta.alerts.lease");
    requirePositive(processedRetention, "vitadelta.alerts.processed-retention");
    requirePositive(deadLetterRetention, "vitadelta.alerts.dead-letter-retention");
    if (claimBatchSize < 1 || claimBatchSize > 1000) {
      throw new IllegalArgumentException(
          "vitadelta.alerts.claim-batch-size must be between 1 and 1000: " + claimBatchSize);
    }
    if (maxAttempts < 1 || maxAttempts > 20) {
      throw new IllegalArgumentException(
          "vitadelta.alerts.max-attempts must be between 1 and 20: " + maxAttempts);
    }
    if (housekeepingBatchSize < 1) {
      throw new IllegalArgumentException(
          "vitadelta.alerts.housekeeping-batch-size must be positive: " + housekeepingBatchSize);
    }
  }

  private static void requirePositive(Duration value, String name) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be a positive duration");
    }
  }
}
