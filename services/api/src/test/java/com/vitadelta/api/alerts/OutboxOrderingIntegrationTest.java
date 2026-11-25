package com.vitadelta.api.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Per-pair ordering of {@code OFFERS_CHANGED} events (section 8.6, ADR-0008): an event is claimed
 * only when no older unprocessed event of the same (product, country) pair exists, so a failing
 * event delays only its own pair, its retry keeps the order, a dead letter releases the pair, and
 * the claim stays an index lookup on a large outbox.
 */
class OutboxOrderingIntegrationTest extends AlertsIntegrationTest {

  @Autowired private NotificationRepository notificationRepository;

  private UUID eventWithAfterPrice(String price) {
    return jdbc.sql(
            "SELECT id FROM outbox_event WHERE event_type = 'OFFERS_CHANGED'"
                + " AND (payload -> 'after' ->> 'effectivePrice')::numeric = ?::numeric")
        .param(price)
        .query(UUID.class)
        .single();
  }

  private void failOnce(UUID event, Duration backOff) {
    jdbc.sql(
            "UPDATE outbox_event SET attempts = 1, last_error = 'SimulatedFailure',"
                + " next_attempt_at = ? WHERE id = ?")
        .params(Timestamp.from(clock.instant().plus(backOff)), event)
        .update();
  }

  private Object processedAt(UUID event) {
    return jdbc.sql("SELECT processed_at FROM outbox_event WHERE id = ?")
        .param(event)
        .query()
        .singleRow()
        .get("processed_at");
  }

  private List<String> inboxAfterPrices(TestUser user) {
    return notificationRepository.page(user.id(), false, 0, 50).stream()
        .map(n -> money(JSON.readTree(n.payload()).get("after").get("effectivePrice")))
        .toList();
  }

  private static String money(JsonNode value) {
    return value.decimalValue().setScale(2, java.math.RoundingMode.HALF_EVEN).toPlainString();
  }

  @Test
  void aFailedEventHoldsBackOnlyTheLaterEventsOfItsOwnPair() {
    TestUser user = user(true, false, com.vitadelta.api.identity.UserStatus.ACTIVE, "en");
    UUID gold = productId("GOLD-A");
    UUID other = productId("GOLD-B");
    subscription(user, gold, "TR", AlertTrigger.PRICE_CHANGE, null, null);
    subscription(user, other, "TR", AlertTrigger.PRICE_CHANGE, null, null);
    offersChanged(gold, "TR", state("100.00", true), state("80.50", true));
    offersChanged(gold, "TR", state("80.50", true), state("90.50", true));
    offersChanged(other, "TR", state("300.00", true), state("310.00", true));
    UUID first = eventWithAfterPrice("80.50");
    UUID second = eventWithAfterPrice("90.50");
    UUID unrelated = eventWithAfterPrice("310.00");
    failOnce(first, Duration.ofMinutes(2));

    drainOutbox();

    assertThat(processedAt(unrelated)).as("another pair proceeds").isNotNull();
    assertThat(processedAt(second)).as("waits for the older event of its pair").isNull();
    assertThat(processedAt(first)).isNull();
    assertThat(inboxAfterPrices(user)).containsExactly("310.00");

    clock.advance(Duration.ofMinutes(3));
    drainOutbox();

    assertThat(processedAt(first)).isNotNull();
    assertThat(processedAt(second)).isNotNull();
    List<String> inbox = inboxAfterPrices(user);
    assertThat(inbox.getFirst())
        .as("the newest notification describes the current price")
        .isEqualTo("90.50");
    assertThat(inbox).containsExactly("90.50", "80.50", "310.00");
  }

  @Test
  void aBaselineIsNeverTakenFromALaterEventBeforeAnEarlierOne() {
    TestUser user = user();
    UUID gold = productId("GOLD-A");
    UUID drop =
        subscription(user, gold, "TR", AlertTrigger.PRICE_DROP, new BigDecimal("5.00"), null);
    offersChanged(gold, "TR", null, state("100.00", true));
    offersChanged(gold, "TR", state("100.00", true), state("95.00", true));
    failOnce(eventWithAfterPrice("100.00"), Duration.ofMinutes(2));

    drainOutbox();
    assertThat(subscriptionRow(drop).get("baseline_price")).isNull();
    clock.advance(Duration.ofMinutes(3));
    drainOutbox();

    assertThat(subscriptionRow(drop).get("baseline_price")).isEqualTo(new BigDecimal("95.00"));
    assertThat(notifications(user)).as("the 5 % drop from the first price").hasSize(1);
  }

  @Test
  void aDeadLetteredEventReleasesItsPair() {
    TestUser user = user(true, false, com.vitadelta.api.identity.UserStatus.ACTIVE, "en");
    UUID gold = productId("GOLD-A");
    subscription(user, gold, "TR", AlertTrigger.PRICE_CHANGE, null, null);
    UUID poison = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO outbox_event (id, event_type, aggregate_type, aggregate_id, payload,"
                + " created_at, next_attempt_at)"
                + " VALUES (?, 'OFFERS_CHANGED', 'PRODUCT_COUNTRY', ?, ?::jsonb, ?, ?)")
        .params(
            poison,
            gold + ":TR",
            "{\"productId\":\"not-a-uuid\"}",
            Timestamp.from(clock.instant()),
            Timestamp.from(clock.instant()))
        .update();
    offersChanged(gold, "TR", state("100.00", true), state("90.00", true));
    UUID later = eventWithAfterPrice("90.00");

    for (int attempt = 1; attempt <= 4; attempt++) {
      drainOutbox();
      assertThat(processedAt(later)).as("held back after attempt %d", attempt).isNull();
      clock.advance(Duration.ofMinutes(40));
    }
    drainOutbox();

    Map<String, Object> dead =
        jdbc.sql("SELECT outcome, attempts FROM outbox_event WHERE id = ?")
            .param(poison)
            .query()
            .singleRow();
    assertThat(dead.get("outcome")).isEqualTo("DEAD_LETTER");
    assertThat(((Number) dead.get("attempts")).intValue()).isEqualTo(5);
    assertThat(processedAt(later)).isNotNull();
    assertThat(inboxAfterPrices(user)).containsExactly("90.00");
  }

  @Test
  void eventsOfOnePairAreNeverClaimedInTheSameBatch() {
    UUID product = UUID.randomUUID();
    for (int i = 0; i < 5; i++) {
      offersChanged(product, "TR", state("10.00", true), state("11.00", true));
    }
    Instant now = clock.instant();
    OutboxRepository outbox = new OutboxRepository(jdbc);
    for (int i = 0; i < 5; i++) {
      List<OutboxRepository.ClaimedEvent> claimed =
          transactions.execute(tx -> outbox.claim(now, now.plusSeconds(60), 100));
      assertThat(claimed).hasSize(1);
      outbox.markProcessed(claimed.getFirst().id(), OutboxOutcome.DELIVERED, now);
    }
    List<OutboxRepository.ClaimedEvent> none =
        transactions.execute(tx -> outbox.claim(now, now.plusSeconds(60), 100));
    assertThat(none).isEmpty();
  }

  @Test
  void theClaimUsesTheIndexesOnALargeOutbox() {
    Instant now = clock.instant();
    jdbc.sql(
            "INSERT INTO outbox_event (id, event_type, aggregate_type, aggregate_id, payload,"
                + " created_at, processed_at, outcome, attempts, next_attempt_at)"
                + " SELECT gen_random_uuid(), 'OFFERS_CHANGED', 'PRODUCT_COUNTRY', 'pair-' || (g % 500),"
                + " '{}'::jsonb, ?, ?, 'DELIVERED', 0, ? FROM generate_series(1, 100000) g")
        .params(
            Timestamp.from(now.minusSeconds(86_400)),
            Timestamp.from(now.minusSeconds(3_600)),
            Timestamp.from(now.minusSeconds(86_400)))
        .update();
    jdbc.sql(
            "INSERT INTO outbox_event (id, event_type, aggregate_type, aggregate_id, payload,"
                + " created_at, attempts, next_attempt_at)"
                + " SELECT gen_random_uuid(), CASE WHEN g % 4 = 0 THEN 'NOTIFICATION_CREATED'"
                + " ELSE 'OFFERS_CHANGED' END, CASE WHEN g % 4 = 0 THEN 'NOTIFICATION'"
                + " ELSE 'PRODUCT_COUNTRY' END, 'pair-' || (g % 100), '{}'::jsonb, ?, 0, ?"
                + " FROM generate_series(1, 400) g")
        .params(Timestamp.from(now.minusSeconds(60)), Timestamp.from(now.minusSeconds(60)))
        .update();
    jdbc.sql("ANALYZE outbox_event").update();

    String plan =
        String.join(
            "\n",
            jdbc.sql("EXPLAIN " + OutboxRepository.CLAIM_SQL)
                .param("leaseUntil", OutboxRepository.ts(now.plusSeconds(120)))
                .param("now", OutboxRepository.ts(now))
                .param("limit", 100)
                .query(String.class)
                .list());

    assertThat(plan).as(plan).doesNotContain("Seq Scan");
    assertThat(plan).contains("outbox_event_pending_pair_idx");
  }

  @Test
  void theNewestOfSeveralNotificationsCreatedAtTheSameInstantIsListedFirst() {
    TestUser user = user(true, false, com.vitadelta.api.identity.UserStatus.ACTIVE, "en");
    UUID gold = productId("GOLD-A");
    subscription(user, gold, "TR", AlertTrigger.PRICE_CHANGE, null, null);
    for (String price : List.of("91.00", "92.00", "93.00", "94.00", "95.00")) {
      offersChanged(gold, "TR", state("90.00", true), state(price, true));
    }
    drainOutbox();
    JsonNode items =
        JSON.readTree(
            client
                .get()
                .uri("/api/v1/me/notifications")
                .header("Authorization", "Bearer " + token(user))
                .exchange()
                .returnResult(String.class)
                .getResponseBody());
    assertThat(money(items.get("items").get(0).get("payload").get("after").get("effectivePrice")))
        .isEqualTo("95.00");
    assertThat(inboxAfterPrices(user)).containsExactly("95.00", "94.00", "93.00", "92.00", "91.00");
  }
}
