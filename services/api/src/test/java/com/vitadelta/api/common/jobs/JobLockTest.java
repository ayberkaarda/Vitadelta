package com.vitadelta.api.common.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitadelta.api.support.PostgresIntegrationTest;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Advisory-lock job runs (ADR-0011): concurrent attempts run the task exactly once. */
@SpringBootTest
class JobLockTest extends PostgresIntegrationTest {

  private static final long TEST_KEY = 0x5644_4A42_0000_7E57L;

  @Autowired private JobLock jobLock;
  @Autowired private PlatformTransactionManager transactionManager;

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
