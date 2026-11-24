package com.vitadelta.api.entitlement;

import com.vitadelta.api.common.jobs.JobLock;
import com.vitadelta.api.common.jobs.JobLockKeys;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily downgrade of expired PRO subscriptions (section 8.3), at 00:05 UTC under the advisory lock
 * {@link JobLockKeys#SUBSCRIPTION_EXPIRY} (ADR-0011; skipped when another session holds it). Each
 * candidate is processed by {@link SubscriptionService#expireIfDue(UUID)} in its own transaction
 * with the user-row lock and a re-check; a failure of one user is logged and counted and does not
 * stop the run.
 *
 * <p>Metrics: {@code vitadelta.subscription.expiry.downgraded}, {@code
 * vitadelta.subscription.expiry.failed}.
 */
@Component
public class SubscriptionExpiryJob {

  /** Name of the job in log lines. */
  public static final String NAME = "SubscriptionExpiryJob";

  static final int BATCH_SIZE = 500;

  private static final Logger LOG = LoggerFactory.getLogger(SubscriptionExpiryJob.class);

  private final JobLock jobLock;
  private final SubscriptionRepository subscriptions;
  private final SubscriptionService service;
  private final Clock clock;
  private final Counter downgraded;
  private final Counter failed;

  public SubscriptionExpiryJob(
      JobLock jobLock,
      SubscriptionRepository subscriptions,
      SubscriptionService service,
      Clock clock,
      MeterRegistry meters) {
    this.jobLock = jobLock;
    this.subscriptions = subscriptions;
    this.service = service;
    this.clock = clock;
    this.downgraded = meters.counter("vitadelta.subscription.expiry.downgraded");
    this.failed = meters.counter("vitadelta.subscription.expiry.failed");
  }

  /** Counts of one run. */
  public record Result(int downgraded, int failed) {}

  @Scheduled(cron = "0 5 0 * * *", zone = "UTC")
  void scheduled() {
    run();
  }

  /**
   * Runs the job under its advisory lock.
   *
   * @return the counts, or empty when the run was skipped because the lock is held
   */
  public Optional<Result> run() {
    AtomicReference<Result> result = new AtomicReference<>();
    jobLock.runExclusive(NAME, JobLockKeys.SUBSCRIPTION_EXPIRY, () -> result.set(expireDue()));
    return Optional.ofNullable(result.get());
  }

  private Result expireDue() {
    Instant now = clock.instant();
    int done = 0;
    int errors = 0;
    UUID after = null;
    List<UUID> batch;
    do {
      batch = subscriptions.expiredCandidates(now, after, BATCH_SIZE);
      for (UUID userId : batch) {
        try {
          if (service.expireIfDue(userId).isPresent()) {
            done++;
            downgraded.increment();
          }
        } catch (RuntimeException e) {
          errors++;
          failed.increment();
          LOG.warn(
              "{}: downgrade of user {} failed ({})", NAME, userId, e.getClass().getSimpleName());
        }
      }
      if (!batch.isEmpty()) {
        after = batch.get(batch.size() - 1);
      }
    } while (batch.size() == BATCH_SIZE);
    LOG.info("{}: {} subscriptions downgraded, {} failed", NAME, done, errors);
    return new Result(done, errors);
  }
}
