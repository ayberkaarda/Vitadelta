package com.vitadelta.api.common.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitadelta.api.common.jobs.JobRunRegistry.JobRun;
import com.vitadelta.api.common.jobs.JobRunRegistry.Outcome;
import com.vitadelta.api.entitlement.SubscriptionExpiryJob;
import com.vitadelta.api.support.PostgresIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The job-run registry fed by {@link JobLock}: start, end and outcome of every run that obtains its
 * lock; skipped runs are not recorded; scheduled jobs report without code of their own.
 */
@SpringBootTest
class JobRunRegistryTest extends PostgresIntegrationTest {

  private static final long KEY = 0x5644_4A42_0000_7E60L;

  @Autowired private JobLock jobLock;
  @Autowired private JobRunRegistry registry;
  @Autowired private SubscriptionExpiryJob subscriptionExpiry;

  @Test
  void aRunThatReturnsIsRecordedAsSucceeded() {
    Instant before = Instant.now();
    assertThat(registry.lastRun(KEY)).isEmpty();
    assertThat(jobLock.runExclusive("probe", KEY, () -> {})).isTrue();
    JobRun run = registry.lastRun(KEY).orElseThrow();
    assertThat(run.jobName()).isEqualTo("probe");
    assertThat(run.lastOutcome()).isEqualTo(Outcome.SUCCEEDED);
    assertThat(run.lastStartedAt()).isAfterOrEqualTo(before);
    assertThat(run.lastFinishedAt()).isAfterOrEqualTo(run.lastStartedAt());
  }

  @Test
  void aRunThatThrowsOrReportsAFailureIsRecordedAsFailed() {
    assertThatThrownBy(() ->
            jobLock.runExclusive(
                "throwing",
                KEY + 1,
                () -> {
                  throw new IllegalStateException("boom");
                }))
        .hasMessage("boom");
    assertThat(registry.lastRun(KEY + 1).orElseThrow().lastOutcome()).isEqualTo(Outcome.FAILED);

    jobLock.runExclusive("reporting", KEY + 2, JobLock::reportFailure);
    assertThat(registry.lastRun(KEY + 2).orElseThrow().lastOutcome()).isEqualTo(Outcome.FAILED);

    jobLock.runExclusive("recovered", KEY + 2, () -> {});
    assertThat(registry.lastRun(KEY + 2).orElseThrow().lastOutcome()).isEqualTo(Outcome.SUCCEEDED);
  }

  @Test
  void aFailureReportedOutsideARunOrInANestedRunStaysThere() {
    JobLock.reportFailure();
    jobLock.runExclusive(
        "outer", KEY + 3, () -> jobLock.runExclusive("inner", KEY + 4, JobLock::reportFailure));
    assertThat(registry.lastRun(KEY + 3).orElseThrow().lastOutcome()).isEqualTo(Outcome.SUCCEEDED);
    assertThat(registry.lastRun(KEY + 4).orElseThrow().lastOutcome()).isEqualTo(Outcome.FAILED);
  }

  @Test
  void aSkippedRunIsNotRecorded() throws Exception {
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      CountDownLatch holding = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      Future<Boolean> holder =
          pool.submit(() ->
              jobLock.runExclusive(
                  "holder",
                  KEY + 5,
                  () -> {
                    holding.countDown();
                    try {
                      release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                  }));
      assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();
      JobRun running = registry.lastRun(KEY + 5).orElseThrow();
      assertThat(running.lastFinishedAt()).isNull();
      assertThat(running.lastOutcome()).isNull();
      assertThat(jobLock.runExclusive("skipped", KEY + 5, JobLock::reportFailure)).isFalse();
      assertThat(registry.lastRun(KEY + 5).orElseThrow()).isEqualTo(running);
      release.countDown();
      assertThat(holder.get(30, TimeUnit.SECONDS)).isTrue();
      JobRun done = registry.lastRun(KEY + 5).orElseThrow();
      assertThat(done.jobName()).isEqualTo("holder");
      assertThat(done.lastStartedAt()).isEqualTo(running.lastStartedAt());
      assertThat(done.lastOutcome()).isEqualTo(Outcome.SUCCEEDED);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void scheduledJobsReportThroughTheLock() {
    Instant before = Instant.now();
    assertThat(subscriptionExpiry.run()).isPresent();
    JobRun run = registry.lastRun(JobLockKeys.SUBSCRIPTION_EXPIRY).orElseThrow();
    assertThat(run.lastOutcome()).isEqualTo(Outcome.SUCCEEDED);
    assertThat(run.lastFinishedAt()).isAfterOrEqualTo(before);
  }

  @Test
  void theRegistryIsThreadSafe() throws Exception {
    JobRunRegistry local =
        new JobRunRegistry(Clock.fixed(Instant.parse("2026-11-25T00:00:00Z"), ZoneOffset.UTC));
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Future<?>> done = new ArrayList<>();
      for (int t = 0; t < 8; t++) {
        int thread = t;
        done.add(
            pool.submit(() -> {
              for (int i = 0; i < 1000; i++) {
                long key = i % 4;
                local.started(key, "job" + key);
                local.finished(
                    key, "job" + key, thread % 2 == 0 ? Outcome.SUCCEEDED : Outcome.FAILED);
              }
            }));
      }
      for (Future<?> future : done) {
        future.get(30, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }
    for (long key = 0; key < 4; key++) {
      JobRun run = local.lastRun(key).orElseThrow();
      assertThat(run.jobName()).isEqualTo("job" + key);
      assertThat(run.lastFinishedAt()).isEqualTo(Instant.parse("2026-11-25T00:00:00Z"));
      assertThat(run.lastOutcome()).isNotNull();
    }
    assertThat(local.lastRun(4)).isEmpty();
  }
}
