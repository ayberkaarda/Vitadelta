package com.vitadelta.api.alerts;

import com.vitadelta.api.common.jobs.JobLock;
import com.vitadelta.api.common.jobs.JobLockKeys;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The outbox processor (section 8.6, ADR-0008): a fixed-delay task ({@code
 * vitadelta.alerts.poll-interval}, 10 s) under the advisory lock {@link
 * JobLockKeys#OUTBOX_PROCESSOR} (ADR-0011). Scheduling configurers are consulted only when
 * scheduling is enabled ({@code VITADELTA_JOBS_ENABLED}).
 *
 * <ol>
 *   <li><b>Claim</b> in one short transaction: up to {@code claim-batch-size} (100) due events
 *       ({@code processed_at IS NULL AND next_attempt_at <= now AND (lease_until IS NULL OR
 *       lease_until < now)}) with {@code FOR UPDATE SKIP LOCKED}, leased until {@code now + lease}
 *       (2 minutes). An expired lease (a crash mid-processing) makes an event claimable again.
 *   <li><b>Process</b> each claimed event on its own: {@code OFFERS_CHANGED} through the {@link
 *       AlertEvaluator} (one short transaction that also marks it processed), {@code
 *       NOTIFICATION_CREATED} through the {@link AlertEmailDispatcher} (SMTP outside any
 *       transaction).
 *   <li><b>Finish</b> a failure in a short statement: {@code attempts + 1}, {@code last_error},
 *       {@code next_attempt_at = now + 2^attempts minutes}, lease cleared; the attempt that reaches
 *       {@code max-attempts} (5) marks the event processed with {@code DEAD_LETTER} and increments
 *       {@code outbox_dead_letter_total}.
 * </ol>
 *
 * Delivery is at-least-once: the evaluator is idempotent through {@code alert_delivery} and the
 * dispatcher re-checks {@code email_sent_at}.
 */
@Component
public class OutboxProcessor implements SchedulingConfigurer {

  private static final Logger LOG = LoggerFactory.getLogger(OutboxProcessor.class);

  /** Name of the job in log lines and in the job-run registry. */
  public static final String JOB_NAME = "OutboxProcessor";

  private final OutboxRepository outbox;
  private final AlertEvaluator evaluator;
  private final AlertEmailDispatcher dispatcher;
  private final AlertMetrics metrics;
  private final AlertsProperties properties;
  private final JobLock jobLock;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public OutboxProcessor(
      OutboxRepository outbox,
      AlertEvaluator evaluator,
      AlertEmailDispatcher dispatcher,
      AlertMetrics metrics,
      AlertsProperties properties,
      JobLock jobLock,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.outbox = outbox;
    this.evaluator = evaluator;
    this.dispatcher = dispatcher;
    this.metrics = metrics;
    this.properties = properties;
    this.jobLock = jobLock;
    this.transactions = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  /**
   * Counts of one run.
   *
   * @param claimed events claimed
   * @param succeeded events that ended processed without error
   * @param failed events whose attempt failed (retried later or dead-lettered)
   * @param deadLettered events that became {@code DEAD_LETTER} in this run
   */
  public record RunResult(int claimed, int succeeded, int failed, int deadLettered) {

    /** Nothing claimed. */
    public static final RunResult EMPTY = new RunResult(0, 0, 0, 0);
  }

  @Override
  public void configureTasks(ScheduledTaskRegistrar registrar) {
    registrar.addFixedDelayTask(this::scheduledRun, properties.pollInterval());
  }

  /** One scheduled execution; failures are logged and never stop the schedule. */
  void scheduledRun() {
    try {
      run();
    } catch (RuntimeException e) {
      LOG.error("{} run failed: {}", JOB_NAME, e.getClass().getSimpleName(), e);
    }
  }

  /**
   * Runs one batch under the advisory lock.
   *
   * @return the counts, {@link RunResult#EMPTY} also when the run was skipped
   */
  public RunResult run() {
    RunResult[] result = {RunResult.EMPTY};
    jobLock.runExclusive(JOB_NAME, JobLockKeys.OUTBOX_PROCESSOR, () -> result[0] = processDue());
    return result[0];
  }

  /**
   * Claims and processes one batch of due events. Callers hold the processor's advisory lock (the
   * scheduled run takes it); it must not be called inside a transaction.
   */
  public RunResult processDue() {
    Instant now = clock.instant();
    List<OutboxRepository.ClaimedEvent> claimed =
        transactions.execute(
            status -> outbox.claim(now, now.plus(properties.lease()), properties.claimBatchSize()));
    if (claimed == null || claimed.isEmpty()) {
      metrics.backlog(outbox.stats());
      return RunResult.EMPTY;
    }
    int succeeded = 0;
    int failed = 0;
    int deadLettered = 0;
    for (OutboxRepository.ClaimedEvent event : claimed) {
      try {
        process(event);
        succeeded++;
      } catch (RuntimeException e) {
        failed++;
        if (recordFailure(event, e)) {
          deadLettered++;
        }
      }
    }
    if (failed > 0) {
      JobLock.reportFailure();
    }
    metrics.backlog(outbox.stats());
    LOG.info(
        "{}: {} claimed, {} processed, {} failed, {} dead-lettered",
        JOB_NAME,
        claimed.size(),
        succeeded,
        failed,
        deadLettered);
    return new RunResult(claimed.size(), succeeded, failed, deadLettered);
  }

  private void process(OutboxRepository.ClaimedEvent event) {
    switch (event.type()) {
      case OFFERS_CHANGED -> evaluator.evaluate(event);
      case NOTIFICATION_CREATED -> dispatcher.dispatch(event);
    }
  }

  /** Records a failed attempt; returns whether the event became a dead letter. */
  private boolean recordFailure(OutboxRepository.ClaimedEvent event, RuntimeException error) {
    String description = AlertEmailDispatcher.describe(error);
    OutboxRepository.FailureResult result =
        outbox.recordFailure(
            event.id(), event.attempts(), description, clock.instant(), properties.maxAttempts());
    switch (result) {
      case DEAD_LETTER -> {
        metrics.deadLetter();
        LOG.error(
            "Outbox event {} ({}) dead-lettered after {} attempts: {}",
            event.id(),
            event.type(),
            event.attempts() + 1,
            description);
        return true;
      }
      case RETRY ->
          LOG.warn(
              "Outbox event {} ({}) attempt {} failed: {}",
              event.id(),
              event.type(),
              event.attempts() + 1,
              description);
      case GONE ->
          LOG.info(
              "Outbox event {} ({}) failed but is no longer pending: {}",
              event.id(),
              event.type(),
              description);
    }
    return false;
  }
}
