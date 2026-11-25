package com.vitadelta.api.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.common.jobs.JobLockKeys;
import com.vitadelta.api.common.jobs.JobRunRegistry;
import com.vitadelta.api.identity.ContractSchemas;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;

/**
 * The outbox mechanics of section 8.6 and ADR-0008: claim with {@code SKIP LOCKED}, lease expiry,
 * SMTP outside transactions, back-off and dead letters, the advisory lock of the processor and the
 * housekeeping job (section 8.11), and the backlog counts.
 */
class OutboxProcessorIntegrationTest extends AlertsIntegrationTest {

  @Autowired private OutboxRepository outbox;
  @Autowired private OutboxHousekeepingJob housekeeping;
  @Autowired private JobRunRegistry runs;
  @Autowired private MeterRegistry meters;

  /**
   * Writes {@code count} OFFERS_CHANGED events, each for its own pair that nobody watches (events
   * of one pair are claimed one at a time, see {@code OutboxOrderingIntegrationTest}).
   */
  private void quietEvents(int count) {
    for (int i = 0; i < count; i++) {
      offersChanged(UUID.randomUUID(), "TR", state("240.00", true), state("230.00", true));
    }
  }

  /** A price-drop notification of {@code user} with its pending NOTIFICATION_CREATED event. */
  private UUID pendingAlertMail(TestUser user) {
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
    List<Map<String, Object>> created = notifications(user);
    assertThat(created).hasSize(1);
    return (UUID) created.getFirst().get("id");
  }

  private Map<String, Object> mailEvent() {
    return events().stream()
        .filter(e -> e.get("event_type").equals("NOTIFICATION_CREATED"))
        .findFirst()
        .orElseThrow();
  }

  static Instant instant(Object value) {
    if (value instanceof java.sql.Timestamp timestamp) {
      return timestamp.toInstant();
    }
    return value == null ? null : ((OffsetDateTime) value).toInstant();
  }

  @Test
  void twoClaimingProcessorsNeverTakeTheSameEvent() throws Exception {
    quietEvents(30);
    Instant now = clock.instant();
    CountDownLatch firstClaimed = new CountDownLatch(1);
    CountDownLatch secondDone = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<List<OutboxRepository.ClaimedEvent>> first =
          executor.submit(() ->
              transactions.execute(tx -> {
                List<OutboxRepository.ClaimedEvent> claimed =
                    outbox.claim(now, now.plus(Duration.ofMinutes(2)), 20);
                firstClaimed.countDown();
                await(secondDone);
                return claimed;
              }));
      firstClaimed.await(30, TimeUnit.SECONDS);
      Future<List<OutboxRepository.ClaimedEvent>> second =
          executor.submit(() -> {
            try {
              return transactions.execute(
                  tx -> outbox.claim(now, now.plus(Duration.ofMinutes(2)), 100));
            } finally {
              secondDone.countDown();
            }
          });
      List<OutboxRepository.ClaimedEvent> a = first.get(60, TimeUnit.SECONDS);
      List<OutboxRepository.ClaimedEvent> b = second.get(60, TimeUnit.SECONDS);
      Set<UUID> idsA = new HashSet<>(a.stream().map(OutboxRepository.ClaimedEvent::id).toList());
      Set<UUID> idsB = new HashSet<>(b.stream().map(OutboxRepository.ClaimedEvent::id).toList());
      assertThat(idsA).hasSize(20);
      assertThat(idsB).hasSize(10).doesNotContainAnyElementsOf(idsA);
      Set<UUID> all = new HashSet<>(idsA);
      all.addAll(idsB);
      assertThat(all).hasSize(30);
      // Both claims committed their lease: nothing is claimable until the leases expire.
      List<OutboxRepository.ClaimedEvent> none =
          transactions.execute(tx -> outbox.claim(now, now.plusSeconds(120), 100));
      assertThat(none).isEmpty();
    } finally {
      executor.shutdownNow();
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  @Test
  void anExpiredLeaseMakesTheEventClaimableAgain() {
    quietEvents(1);
    Instant now = clock.instant();
    List<OutboxRepository.ClaimedEvent> claimed =
        transactions.execute(tx -> outbox.claim(now, now.plus(Duration.ofMinutes(2)), 100));
    assertThat(claimed).hasSize(1);
    assertThat(instant(events().getFirst().get("lease_until")))
        .isEqualTo(now.plus(Duration.ofMinutes(2)));
    // A crash: the event is never finished. Within the lease nobody else takes it.
    clock.advance(Duration.ofMinutes(2));
    assertThat(processor.processDue().claimed()).isZero();
    clock.advance(Duration.ofSeconds(1));
    OutboxProcessor.RunResult again = processor.processDue();
    assertThat(again.claimed()).isEqualTo(1);
    Map<String, Object> event = events().getFirst();
    assertThat(event.get("id")).isEqualTo(claimed.getFirst().id());
    assertThat(event.get("outcome")).isEqualTo("DELIVERED");
    assertThat(event.get("lease_until")).isNull();
  }

  @Test
  void smtpIsCalledOutsideAnyTransactionAndWithoutRowLocks() throws Exception {
    TestUser user = user();
    UUID notification = pendingAlertMail(user);
    processor.processDue();
    assertThat(transport.calls).hasSize(1);
    RecordingTransport.Call call = transport.calls.getFirst();
    assertThat(call.transactionActive()).isFalse();
    assertThat(call.rowsLockable()).isTrue();
    assertThat(mailEvent().get("outcome")).isEqualTo("DELIVERED");
    assertThat(notifications(user).getFirst().get("email_sent_at")).isNotNull();
    assertThat(notifications(user).getFirst().get("id")).isEqualTo(notification);
    JsonNode message = awaitMessage(user.email());
    assertThat(message.path("Subject").asString()).contains("Gold Standard Daily B");
  }

  @Test
  void aFailedDeliveryBacksOffExponentiallyAndDeadLettersAfterFiveAttempts() {
    double deadBefore = meters.counter(AlertMetrics.DEAD_LETTER).count();
    TestUser user = user();
    UUID notification = pendingAlertMail(user);
    transport.failNext(10);
    long[] expectedDelays = {2, 4, 8, 16};
    for (int attempt = 1; attempt <= 4; attempt++) {
      Instant now = clock.instant();
      OutboxProcessor.RunResult result = processor.processDue();
      assertThat(result.failed()).isEqualTo(1);
      Map<String, Object> event = mailEvent();
      assertThat(event.get("attempts")).isEqualTo(attempt);
      assertThat(event.get("processed_at")).isNull();
      assertThat(event.get("lease_until")).isNull();
      assertThat((String) event.get("last_error")).contains("EmailDeliveryException");
      Instant next = instant(event.get("next_attempt_at"));
      assertThat(next).isEqualTo(now.plus(Duration.ofMinutes(expectedDelays[attempt - 1])));
      // Not due before the back-off has passed.
      clock.advance(Duration.ofMinutes(expectedDelays[attempt - 1]).minusSeconds(1));
      assertThat(processor.processDue().claimed()).isZero();
      clock.advance(Duration.ofSeconds(1));
    }
    OutboxProcessor.RunResult last = processor.processDue();
    assertThat(last.deadLettered()).isEqualTo(1);
    Map<String, Object> dead = mailEvent();
    assertThat(dead.get("outcome")).isEqualTo("DEAD_LETTER");
    assertThat(dead.get("attempts")).isEqualTo(5);
    assertThat(dead.get("processed_at")).isNotNull();
    assertThat(meters.counter(AlertMetrics.DEAD_LETTER).count()).isEqualTo(deadBefore + 1);
    assertThat(transport.calls).hasSize(5);
    Map<String, Object> row = notifications(user).getFirst();
    assertThat(row.get("id")).isEqualTo(notification);
    assertThat(row.get("email_sent_at")).isNull();
    assertThat((String) row.get("email_error")).contains("EmailDeliveryException");
    clock.advance(Duration.ofDays(1));
    assertThat(processor.processDue().claimed()).isZero();
  }

  @Test
  void aRetrySucceedsAfterTheBackOff() {
    TestUser user = user();
    pendingAlertMail(user);
    transport.failNext(1);
    processor.processDue();
    assertThat(mailEvent().get("attempts")).isEqualTo(1);
    clock.advance(Duration.ofMinutes(2));
    processor.processDue();
    Map<String, Object> event = mailEvent();
    assertThat(event.get("outcome")).isEqualTo("DELIVERED");
    assertThat(event.get("attempts")).isEqualTo(1);
    Map<String, Object> row = notifications(user).getFirst();
    assertThat(row.get("email_sent_at")).isNotNull();
    assertThat(row.get("email_error")).isNull();
  }

  @Test
  void theAdvisoryLockPreventsConcurrentRuns() throws Exception {
    quietEvents(1);
    Instant lastBefore =
        runs.lastRun(JobLockKeys.OUTBOX_PROCESSOR)
            .map(JobRunRegistry.JobRun::lastFinishedAt)
            .orElse(null);
    // The test holds the processor lock (as a concurrently running instance would).
    assertThat(processor.run().claimed()).isZero();
    assertThat(events().getFirst().get("processed_at")).isNull();
    assertThat(
            runs.lastRun(JobLockKeys.OUTBOX_PROCESSOR)
                .map(JobRunRegistry.JobRun::lastFinishedAt)
                .orElse(null))
        .isEqualTo(lastBefore);
    releaseProcessorLock();
    try {
      OutboxProcessor.RunResult result = processor.run();
      // Another cached context may take the free lock first; either way the event is handled once.
      assertThat(events().getFirst().get("outcome")).isEqualTo("DELIVERED");
      if (result.claimed() == 1) {
        assertThat(runs.lastRun(JobLockKeys.OUTBOX_PROCESSOR)).isPresent();
      }
    } finally {
      reacquireProcessorLock();
    }
    Connection housekeepingLock = holdLock(JobLockKeys.HOUSEKEEPING_ALERTS);
    try {
      assertThat(housekeeping.run()).isEmpty();
    } finally {
      release(housekeepingLock, JobLockKeys.HOUSEKEEPING_ALERTS);
    }
    assertThat(housekeeping.run()).containsKeys("outbox_event", "outbox_event_dead_letter");
  }

  private UUID insertProcessedEvent(String outcome, Instant processedAt) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO outbox_event (id, event_type, aggregate_type, aggregate_id, payload,"
                + " created_at, processed_at, outcome, attempts, next_attempt_at)"
                + " VALUES (?, 'OFFERS_CHANGED', 'PRODUCT_COUNTRY', 'x', '{}'::jsonb, ?, ?, ?, 0, ?)")
        .params(
            id,
            processedAt.atOffset(ZoneOffset.UTC),
            processedAt.atOffset(ZoneOffset.UTC),
            outcome,
            processedAt.atOffset(ZoneOffset.UTC))
        .update();
    return id;
  }

  @Test
  void housekeepingPurgesProcessedEventsAfterThirtyDaysAndDeadLettersAfterNinety() {
    Instant now = clock.instant();
    TestUser user = user();
    UUID subscription =
        subscription(user, productId("ZN40"), "TR", AlertTrigger.PRICE_CHANGE, null, null);
    UUID oldDelivered = insertProcessedEvent("DELIVERED", now.minus(Duration.ofDays(31)));
    UUID oldSkipped = insertProcessedEvent("SKIPPED_INELIGIBLE", now.minus(Duration.ofDays(40)));
    UUID recent = insertProcessedEvent("DELIVERED", now.minus(Duration.ofDays(29)));
    UUID deadRecent = insertProcessedEvent("DEAD_LETTER", now.minus(Duration.ofDays(31)));
    UUID deadOld = insertProcessedEvent("DEAD_LETTER", now.minus(Duration.ofDays(91)));
    jdbc.sql(
            "INSERT INTO alert_delivery (subscription_id, outbox_event_id, created_at)"
                + " VALUES (?, ?, now()), (?, ?, now())")
        .params(subscription, oldDelivered, subscription, recent)
        .update();
    quietEvents(1);
    jdbc.sql("UPDATE outbox_event SET created_at = ? WHERE processed_at IS NULL")
        .param(now.minus(Duration.ofDays(200)).atOffset(ZoneOffset.UTC))
        .update();

    Map<String, Integer> counts = housekeeping.run();

    assertThat(counts)
        .containsEntry("outbox_event", 2)
        .containsEntry("outbox_event_dead_letter", 1)
        .containsEntry("alert_delivery", 1);
    List<Object> left = new ArrayList<>();
    events().forEach(e -> left.add(e.get("id")));
    assertThat(left).contains(recent, deadRecent).doesNotContain(oldDelivered, oldSkipped, deadOld);
    assertThat(events().stream().filter(e -> e.get("processed_at") == null)).hasSize(1);
    assertThat(
            jdbc.sql("SELECT count(*) FROM alert_delivery WHERE subscription_id = ?")
                .param(subscription)
                .query(Long.class)
                .single())
        .isEqualTo(1);
    assertThat(meters.counter(AlertMetrics.PURGED, "purge", "outbox_event").count())
        .isGreaterThanOrEqualTo(2);
  }

  @Test
  void theBacklogIsReportedByTheAdminEndpointAndTheGauges() {
    String admin = adminToken();
    EntityExchangeResult<String> empty = get("/api/v1/admin/outbox/stats", admin);
    assertStatus(empty, 200);
    ContractSchemas.assertConforms("OutboxStats", empty.getResponseBody());
    assertThat(json(empty).get("pending").asLong()).isZero();
    assertThat(json(empty).get("oldestPendingAt").isNull()).isTrue();

    quietEvents(2);
    TestUser user = user();
    pendingAlertMail(user);
    transport.failNext(1);
    processor.processDue();
    insertProcessedEvent("DEAD_LETTER", clock.instant());
    quietEvents(1);

    EntityExchangeResult<String> stats = get("/api/v1/admin/outbox/stats", admin);
    assertStatus(stats, 200);
    ContractSchemas.assertConforms("OutboxStats", stats.getResponseBody());
    JsonNode body = json(stats);
    assertThat(body.get("pending").asLong()).isEqualTo(2);
    assertThat(body.get("failed").asLong()).isEqualTo(1);
    assertThat(body.get("deadLetter").asLong()).isEqualTo(1);
    assertThat(body.get("oldestPendingAt").asString()).isNotBlank();
    assertThat(meters.get(AlertMetrics.PENDING).gauge().value()).isEqualTo(1.0);
    assertThat(meters.get(AlertMetrics.FAILED).gauge().value()).isEqualTo(1.0);

    EntityExchangeResult<String> health = get("/api/v1/admin/health", admin);
    assertStatus(health, 200);
    JsonNode outboxHealth = json(health).get("components").get("outbox");
    assertThat(outboxHealth.get("status").asString()).isEqualTo("DEGRADED");
    assertThat(outboxHealth.get("failed").asLong()).isEqualTo(1);
  }
}
