package com.vitadelta.api.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.identity.UserDeletingEvent;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Lock order of the e-mail completion against the account deletion (sections 5.9 and 8.6). The
 * deletion deletes the user's pending {@code NOTIFICATION_CREATED} events and then, through the
 * cascade, the notifications; the completion after a successful send locks the event before it
 * updates the notification, so the two never wait for each other in a cycle. SMTP stays outside
 * every transaction.
 */
class EmailCompletionLockOrderIntegrationTest extends AlertsIntegrationTest {

  @Autowired private OutboxRepository outbox;
  @Autowired private AlertEmailDispatcher dispatcher;
  @Autowired private ApplicationEventPublisher events;

  /** A user with one alert notification and its pending, claimed NOTIFICATION_CREATED event. */
  private OutboxRepository.ClaimedEvent claimedMail(TestUser user) {
    UUID product = productId("GOLD-B");
    subscription(
        user,
        product,
        "TR",
        AlertTrigger.PRICE_DROP,
        new BigDecimal("5.00"),
        new BigDecimal("900.00"));
    offersChanged(product, "TR", state("900.00", true), state("846.00", true));
    processor.processDue();
    Instant now = clock.instant();
    List<OutboxRepository.ClaimedEvent> claimed =
        transactions.execute(tx -> outbox.claim(now, now.plus(Duration.ofMinutes(2)), 10));
    assertThat(claimed).hasSize(1);
    assertThat(claimed.getFirst().type()).isEqualTo(OutboxEventType.NOTIFICATION_CREATED);
    return claimed.getFirst();
  }

  private static void execute(Connection connection, String sql, UUID id) throws Exception {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, id);
      statement.execute();
    }
  }

  private long waitingOnLocks(int exceptPid) {
    return jdbc.sql(
            "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                + " AND pid <> ? AND datname = current_database()")
        .param(exceptPid)
        .query(Long.class)
        .single();
  }

  /**
   * The deletion holds the event (first step of its transaction) while the completion runs, then
   * cascades to the notification. With the completion locking the notification first this is a
   * deadlock; with the shared order the completion waits, finds the event gone and ends.
   */
  @Test
  void aDeletionThatHoldsTheEventWhileTheCompletionRunsDoesNotDeadlock() throws Exception {
    TestUser user = user();
    OutboxRepository.ClaimedEvent mail = claimedMail(user);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (Connection deletion = dataSource.getConnection()) {
      deletion.setAutoCommit(false);
      int pid;
      try (PreparedStatement statement = deletion.prepareStatement("SELECT pg_backend_pid()");
          ResultSet result = statement.executeQuery()) {
        result.next();
        pid = result.getInt(1);
      }
      execute(deletion, "SELECT id FROM app_user WHERE id = ? FOR UPDATE", user.id());
      execute(
          deletion,
          "DELETE FROM outbox_event WHERE processed_at IS NULL"
              + " AND event_type = 'NOTIFICATION_CREATED' AND aggregate_id IN ("
              + " SELECT CAST(id AS varchar) FROM notification WHERE user_id = ?)",
          user.id());

      Future<OutboxOutcome> completion = executor.submit(() -> dispatcher.dispatch(mail));
      Awaitility.await()
          .atMost(Duration.ofSeconds(30))
          .pollInterval(Duration.ofMillis(50))
          .until(() -> waitingOnLocks(pid) == 1);

      execute(deletion, "DELETE FROM app_user WHERE id = ?", user.id());
      deletion.commit();

      assertThat(completion.get(30, TimeUnit.SECONDS)).isEqualTo(OutboxOutcome.SKIPPED_DELETED);
    } finally {
      executor.shutdownNow();
    }
    assertThat(transport.calls).hasSize(1);
    assertThat(transport.calls.getFirst().transactionActive()).isFalse();
    assertThat(notifications(user)).isEmpty();
    assertThat(events().stream().filter(e -> e.get("event_type").equals("NOTIFICATION_CREATED")))
        .isEmpty();
  }

  @Test
  void repeatedDeletionsDuringTheDispatchNeverFail() throws Exception {
    ExecutorService executor = Executors.newCachedThreadPool();
    List<Throwable> failures = new CopyOnWriteArrayList<>();
    try {
      for (int round = 0; round < 10; round++) {
        TestUser user = user();
        claimedMailPending(user);
        List<Future<?>> deletions = new CopyOnWriteArrayList<>();
        transport.during(message ->
            deletions.add(
                executor.submit(() -> {
                  try {
                    transactions.executeWithoutResult(tx -> {
                      users.findByIdForUpdate(user.id());
                      events.publishEvent(new UserDeletingEvent(user.id()));
                      users.delete(user.id());
                    });
                  } catch (RuntimeException e) {
                    failures.add(e);
                  }
                })));
        OutboxProcessor.RunResult run = processor.processDue();
        for (Future<?> deletion : deletions) {
          deletion.get(30, TimeUnit.SECONDS);
        }
        assertThat(run.failed()).as("round %d", round).isZero();
        assertThat(notifications(user)).isEmpty();
        transport.during(message -> {});
      }
    } finally {
      executor.shutdownNow();
    }
    assertThat(failures).isEmpty();
    assertThat(
            events().stream()
                .filter(e -> e.get("event_type").equals("NOTIFICATION_CREATED"))
                .filter(e -> e.get("processed_at") == null))
        .isEmpty();
  }

  /** Like {@link #claimedMail} without claiming: the event stays due for the processor. */
  private void claimedMailPending(TestUser user) {
    UUID product = productId("GOLD-B");
    subscription(
        user,
        product,
        "TR",
        AlertTrigger.PRICE_DROP,
        new BigDecimal("5.00"),
        new BigDecimal("900.00"));
    offersChanged(product, "TR", state("900.00", true), state("846.00", true));
    processor.processDue();
    assertThat(notifications(user)).hasSize(1);
  }
}
