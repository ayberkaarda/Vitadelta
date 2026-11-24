package com.vitadelta.api.identity;

import static com.vitadelta.api.identity.UserRepository.ts;

import com.vitadelta.api.common.jobs.JobLock;
import com.vitadelta.api.common.jobs.JobLockKeys;
import com.vitadelta.api.config.properties.OperationsProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The identity part of {@code HousekeepingJob} (section 8.11, ADR-0011): daily at 03:15 UTC under
 * the {@code HOUSEKEEPING_IDENTITY} advisory lock. Every purge deletes in batches of {@link
 * IdentityProperties#housekeepingBatchSize()} rows, one short transaction per batch:
 *
 * <ul>
 *   <li>{@code anonymous_identity} rows not seen for {@link
 *       IdentityProperties#anonymousRetention()};
 *   <li>{@code email_token} rows expired or used longer ago than {@link
 *       IdentityProperties#emailTokenRetention()};
 *   <li>{@code refresh_token} rows expired or revoked longer ago than {@link
 *       IdentityProperties#refreshTokenRetention()}, and every encrypted grace successor older than
 *       the grace window;
 *   <li>unverified USER accounts that never logged in and are older than {@code
 *       VITADELTA_UNVERIFIED_ACCOUNT_TTL_DAYS}, deleted with the cascade of section 5.9 ({@link
 *       UserDeletingEvent} is published in each deletion transaction).
 * </ul>
 *
 * Each purge logs its count and increments the counter {@value #METRIC} (tag {@code purge}).
 */
@Component
public class IdentityHousekeepingJob {

  private static final Logger LOG = LoggerFactory.getLogger(IdentityHousekeepingJob.class);

  /** Counter of purged rows. */
  public static final String METRIC = "vitadelta.housekeeping.identity.purged";

  /** Job name in log lines. */
  public static final String JOB_NAME = "HousekeepingJob(identity)";

  private final JdbcClient jdbc;
  private final IdentityProperties properties;
  private final OperationsProperties operations;
  private final ApplicationEventPublisher events;
  private final JobLock jobLock;
  private final MeterRegistry meters;
  private final Clock clock;
  private final TransactionTemplate transactions;

  public IdentityHousekeepingJob(
      JdbcClient jdbc,
      IdentityProperties properties,
      OperationsProperties operations,
      ApplicationEventPublisher events,
      JobLock jobLock,
      MeterRegistry meters,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.properties = properties;
    this.operations = operations;
    this.events = events;
    this.jobLock = jobLock;
    this.meters = meters;
    this.clock = clock;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @Scheduled(cron = "0 15 3 * * *", zone = "UTC")
  void scheduled() {
    run();
  }

  /**
   * Runs every purge under the advisory lock.
   *
   * @return purged rows per purge name, empty when the run was skipped
   */
  public Map<String, Integer> run() {
    Map<String, Integer> counts = new LinkedHashMap<>();
    boolean ran =
        jobLock.runExclusive(
            JOB_NAME,
            JobLockKeys.HOUSEKEEPING_IDENTITY,
            () -> {
              Instant now = clock.instant();
              counts.put("anonymous_identity", purgeAnonymous(now));
              counts.put("email_token", purgeEmailTokens(now));
              counts.put("refresh_token", purgeRefreshTokens(now));
              counts.put("refresh_token_grace", clearGraceSuccessors(now));
              counts.put("unverified_user", purgeUnverifiedUsers(now));
            });
    if (ran) {
      counts.forEach((purge, count) -> {
        LOG.info("{} purge {}: {} rows", JOB_NAME, purge, count);
        meters.counter(METRIC, "purge", purge).increment(count);
      });
    }
    return counts;
  }

  private int batches(IntSupplier batch) {
    int total = 0;
    int size = properties.housekeepingBatchSize();
    while (true) {
      Integer changed = transactions.execute(status -> batch.getAsInt());
      int count = changed == null ? 0 : changed;
      total += count;
      if (count < size) {
        return total;
      }
    }
  }

  int purgeAnonymous(Instant now) {
    Instant cutoff = now.minus(properties.anonymousRetention());
    return batches(() ->
        jdbc.sql(
                "DELETE FROM anonymous_identity WHERE id_hash IN (SELECT id_hash FROM"
                    + " anonymous_identity WHERE last_seen_at < ? LIMIT ?)")
            .params(ts(cutoff), properties.housekeepingBatchSize())
            .update());
  }

  int purgeEmailTokens(Instant now) {
    Instant cutoff = now.minus(properties.emailTokenRetention());
    return batches(() ->
        jdbc.sql(
                "DELETE FROM email_token WHERE id IN (SELECT id FROM email_token"
                    + " WHERE expires_at < ? OR used_at < ? LIMIT ?)")
            .params(ts(cutoff), ts(cutoff), properties.housekeepingBatchSize())
            .update());
  }

  int purgeRefreshTokens(Instant now) {
    Instant cutoff = now.minus(properties.refreshTokenRetention());
    return batches(() ->
        jdbc.sql(
                "DELETE FROM refresh_token WHERE id IN (SELECT id FROM refresh_token"
                    + " WHERE expires_at < ? OR revoked_at < ? LIMIT ?)")
            .params(ts(cutoff), ts(cutoff), properties.housekeepingBatchSize())
            .update());
  }

  int clearGraceSuccessors(Instant now) {
    Instant cutoff = now.minus(RefreshTokenService.GRACE_WINDOW);
    return batches(() ->
        jdbc.sql(
                "UPDATE refresh_token SET grace_successor_enc = NULL WHERE id IN (SELECT id FROM"
                    + " refresh_token WHERE grace_successor_enc IS NOT NULL AND revoked_at <= ?"
                    + " LIMIT ?)")
            .params(ts(cutoff), properties.housekeepingBatchSize())
            .update());
  }

  int purgeUnverifiedUsers(Instant now) {
    Instant cutoff = now.minus(Duration.ofDays(operations.unverifiedAccountTtlDays()));
    return batches(() -> {
      List<UUID> ids =
          jdbc.sql(
                  "SELECT id FROM app_user WHERE role = 'USER' AND email_verified_at IS NULL"
                      + " AND last_login_at IS NULL AND created_at < ? LIMIT ? FOR UPDATE"
                      + " SKIP LOCKED")
              .params(ts(cutoff), properties.housekeepingBatchSize())
              .query(UUID.class)
              .list();
      int deleted = 0;
      for (UUID id : ids) {
        events.publishEvent(new UserDeletingEvent(id));
        deleted += jdbc.sql("DELETE FROM app_user WHERE id = ?").param(id).update();
      }
      return deleted;
    });
  }
}
