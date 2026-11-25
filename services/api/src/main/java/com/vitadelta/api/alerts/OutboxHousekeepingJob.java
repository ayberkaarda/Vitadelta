package com.vitadelta.api.alerts;

import com.vitadelta.api.common.jobs.JobLock;
import com.vitadelta.api.common.jobs.JobLockKeys;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The outbox part of {@code HousekeepingJob} (section 8.11, ADR-0011), daily at 03:15 UTC under the
 * advisory lock {@link JobLockKeys#HOUSEKEEPING_ALERTS}: outbox events processed more than {@code
 * processed-retention} (30 days) ago and dead letters processed more than {@code
 * dead-letter-retention} (90 days) ago are deleted with their {@code alert_delivery} rows, in
 * batches of {@code housekeeping-batch-size} (1000) rows, one short transaction per batch.
 * Unprocessed events are never touched. Counts are logged and added to {@value AlertMetrics#PURGED}
 * (tag {@code purge}).
 */
@Component
public class OutboxHousekeepingJob {

  private static final Logger LOG = LoggerFactory.getLogger(OutboxHousekeepingJob.class);

  /** Name of the job in log lines and in the job-run registry. */
  public static final String JOB_NAME = "HousekeepingJob(alerts)";

  private final OutboxRepository outbox;
  private final AlertMetrics metrics;
  private final AlertsProperties properties;
  private final JobLock jobLock;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public OutboxHousekeepingJob(
      OutboxRepository outbox,
      AlertMetrics metrics,
      AlertsProperties properties,
      JobLock jobLock,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.outbox = outbox;
    this.metrics = metrics;
    this.properties = properties;
    this.jobLock = jobLock;
    this.transactions = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  @Scheduled(cron = "0 15 3 * * *", zone = "UTC")
  void scheduled() {
    run();
  }

  /**
   * Runs every purge under the advisory lock.
   *
   * @return removed rows per purge ({@code outbox_event}, {@code outbox_event_dead_letter}, {@code
   *     alert_delivery}), empty when the run was skipped
   */
  public Map<String, Integer> run() {
    Map<String, Integer> counts = new LinkedHashMap<>();
    boolean ran =
        jobLock.runExclusive(
            JOB_NAME,
            JobLockKeys.HOUSEKEEPING_ALERTS,
            () -> {
              Instant now = clock.instant();
              OutboxRepository.PurgeCount processed =
                  purge(false, now.minus(properties.processedRetention()));
              OutboxRepository.PurgeCount dead =
                  purge(true, now.minus(properties.deadLetterRetention()));
              counts.put("outbox_event", processed.events());
              counts.put("outbox_event_dead_letter", dead.events());
              counts.put("alert_delivery", processed.deliveries() + dead.deliveries());
            });
    if (ran) {
      counts.forEach((purge, count) -> {
        LOG.info("{} purge {}: {} rows", JOB_NAME, purge, count);
        metrics.purged(purge, count);
      });
      metrics.backlog(outbox.stats());
    }
    return counts;
  }

  private OutboxRepository.PurgeCount purge(boolean deadLetters, Instant cutoff) {
    int size = properties.housekeepingBatchSize();
    int events = 0;
    int deliveries = 0;
    while (true) {
      OutboxRepository.PurgeCount batch =
          transactions.execute(status -> outbox.purgeProcessed(deadLetters, cutoff, size));
      if (batch == null) {
        return new OutboxRepository.PurgeCount(events, deliveries);
      }
      events += batch.events();
      deliveries += batch.deliveries();
      if (batch.events() < size) {
        return new OutboxRepository.PurgeCount(events, deliveries);
      }
    }
  }
}
