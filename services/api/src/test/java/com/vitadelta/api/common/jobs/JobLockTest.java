package com.vitadelta.api.common.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitadelta.api.common.logging.LogContext;
import com.vitadelta.api.common.web.RequestIds;
import com.vitadelta.api.support.PostgresIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Advisory-lock job runs (ADR-0011): concurrent attempts run the task exactly once; runs are
 * measured and logged with the job context.
 */
@SpringBootTest
class JobLockTest extends PostgresIntegrationTest {

  private static final long TEST_KEY = 0x5644_4A42_0000_7E57L;

  @Autowired private JobLock jobLock;
  @Autowired private DataSource dataSource;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private MeterRegistry meters;

  private long runs(String job, String outcome) {
    Timer timer = meters.find(JobLock.RUNS_METRIC).tag("job", job).tag("outcome", outcome).timer();
    return timer == null ? 0 : timer.count();
  }

  @Test
  void runsAreTimedByOutcomeAndCarryTheJobLogContext() {
    String job = "metricProbe";
    long succeeded = runs(job, "SUCCEEDED");
    long failed = runs(job, "FAILED");
    AtomicReference<String> jobKey = new AtomicReference<>();
    AtomicReference<String> requestId = new AtomicReference<>();
    jobLock.runExclusive(
        job,
        TEST_KEY + 3,
        () -> {
          jobKey.set(MDC.get(LogContext.JOB));
          requestId.set(MDC.get(RequestIds.KEY));
        });
    jobLock.runExclusive(job, TEST_KEY + 3, JobLock::reportFailure);
    assertThat(runs(job, "SUCCEEDED")).isEqualTo(succeeded + 1);
    assertThat(runs(job, "FAILED")).isEqualTo(failed + 1);
    assertThat(jobKey.get()).isEqualTo(job);
    assertThat(requestId.get()).matches(RequestIds.FORMAT);
    assertThat(MDC.get(LogContext.JOB)).isNull();
    assertThat(MDC.get(RequestIds.KEY)).isNull();

    // A run triggered by a request keeps the request's id.
    MDC.put(RequestIds.KEY, "request-1");
    try {
      jobLock.runExclusive(job, TEST_KEY + 3, () -> requestId.set(MDC.get(RequestIds.KEY)));
      assertThat(requestId.get()).isEqualTo("request-1");
      assertThat(MDC.get(RequestIds.KEY)).isEqualTo("request-1");
    } finally {
      MDC.remove(RequestIds.KEY);
    }
  }

  @Test
  void aSkippedRunIsCounted() throws Exception {
    double skippedBefore = skipped("skipProbe");
    try (Connection other = dataSource.getConnection();
        PreparedStatement lock = other.prepareStatement("SELECT pg_advisory_lock(?)")) {
      lock.setLong(1, TEST_KEY + 4);
      lock.execute();
      try {
        assertThat(jobLock.runExclusive("skipProbe", TEST_KEY + 4, () -> {})).isFalse();
      } finally {
        try (PreparedStatement unlock = other.prepareStatement("SELECT pg_advisory_unlock(?)")) {
          unlock.setLong(1, TEST_KEY + 4);
          unlock.execute();
        }
      }
    }
    assertThat(skipped("skipProbe")).isEqualTo(skippedBefore + 1);
  }

  private double skipped(String job) {
    var counter = meters.find(JobLock.SKIPPED_METRIC).tag("job", job).counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  void secondConcurrentAttemptSkipsWithoutBlocking() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch running = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      AtomicInteger runs = new AtomicInteger();
      Future<Boolean> first =
          pool.submit(() ->
              jobLock.runExclusive(
                  "first",
                  TEST_KEY,
                  () -> {
                    runs.incrementAndGet();
                    running.countDown();
                    try {
                      release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                  }));
      assertThat(running.await(30, TimeUnit.SECONDS)).isTrue();
      Future<Boolean> second =
          pool.submit(() -> jobLock.runExclusive("second", TEST_KEY, runs::incrementAndGet));
      assertThat(second.get(5, TimeUnit.SECONDS)).isFalse();
      release.countDown();
      assertThat(first.get(30, TimeUnit.SECONDS)).isTrue();
      assertThat(runs.get()).isEqualTo(1);
      assertThat(jobLock.runExclusive("third", TEST_KEY, runs::incrementAndGet)).isTrue();
      assertThat(runs.get()).isEqualTo(2);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void lockIsReleasedWhenTheTaskFails() {
    assertThatThrownBy(() ->
            jobLock.runExclusive(
                "failing",
                TEST_KEY + 1,
                () -> {
                  throw new IllegalStateException("task failed");
                }))
        .hasMessage("task failed");
    AtomicInteger runs = new AtomicInteger();
    assertThat(jobLock.runExclusive("after", TEST_KEY + 1, runs::incrementAndGet)).isTrue();
    assertThat(runs.get()).isEqualTo(1);
  }

  @Test
  void refusesToRunInsideATransaction() {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status ->
            assertThatThrownBy(() -> jobLock.runExclusive("tx", TEST_KEY + 2, () -> {}))
                .isInstanceOf(IllegalStateException.class));
  }

  @Test
  void keysAreDistinct() throws IllegalAccessException {
    Set<Long> keys = new HashSet<>();
    int count = 0;
    for (Field field : JobLockKeys.class.getDeclaredFields()) {
      if (Modifier.isPublic(field.getModifiers()) && field.getType() == long.class) {
        keys.add(field.getLong(null));
        count++;
      }
    }
    assertThat(count).isEqualTo(7);
    assertThat(keys).hasSize(count);
    assertThat(keys).allSatisfy(k -> assertThat(k >>> 32).isEqualTo(0x5644_4A42L));
  }
}
