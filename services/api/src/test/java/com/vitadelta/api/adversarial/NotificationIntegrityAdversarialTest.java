package com.vitadelta.api.adversarial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitadelta.api.adversarial.AttackClient.Resp;
import com.vitadelta.api.alerts.AlertTrigger;
import com.vitadelta.api.identity.UserStatus;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Notification integrity under attack (sections 8.4 and 8.6, ADR-0008): baseline resets used to
 * farm notifications past the monthly cap, the single cap notice under concurrent events, alerts
 * that fire for a change older than the subscription, out-of-order and replayed events, e-mail to
 * users who must not get it, poison events, and an SMTP outage with recovery.
 */
class NotificationIntegrityAdversarialTest extends AlertsAdversarialTest {

  private static final int FREE_MONTHLY = 30;

  private long alertNotifications(TestUser user) {
    return count(
        "SELECT count(*) FROM notification WHERE user_id = ? AND type LIKE 'ALERT_%'", user.id());
  }

  private long capNotices(TestUser user) {
    return count(
        "SELECT count(*) FROM notification WHERE user_id = ? AND type = 'NOTIFICATION_CAP_REACHED'",
        user.id());
  }

  private int mailCount(TestUser user) throws Exception {
    return messagesTo(user.email()).size();
  }

  private LocalDate month() {
    return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).withDayOfMonth(1);
  }

  @Test
  void baselineResetsCannotFarmNotificationsPastTheMonthlyCap() {
    TestUser user = user(true, false, UserStatus.ACTIVE, "en");
    String token = token(user);
    UUID product = syntheticProduct("AVAILABLE", "100.00");
    String alert = alertId(createPriceDrop(token, product, "TR", 0.01)).toString();
    for (int i = 0; i < FREE_MONTHLY + 15; i++) {
      assertThat(patchAlert(token, alert, "{\"active\":false}").status()).isEqualTo(200);
      Resp reset = patchAlert(token, alert, "{\"active\":true}");
      assertThat(reset.status()).as(reset.toString()).isEqualTo(200);
      assertThat(reset.json().get("baselinePrice").decimalValue()).isEqualByComparingTo("100");
      offersChanged(product, "TR", state("100.00", true), state("99.98", true));
      drainOutbox();
    }
    assertThat(alertNotifications(user)).isEqualTo(FREE_MONTHLY);
    assertThat(capNotices(user)).as("exactly one cap notice this month").isEqualTo(1);
    assertThat(
            count(
                "SELECT notifications_created FROM usage_counter WHERE user_id = ?"
                    + " AND period_month = ?",
                user.id(),
                month()))
        .isEqualTo(FREE_MONTHLY);
    Resp unread = send("GET", API + "/me/notifications/unread-count", token);
    assertThat(unread.json().get("unreadCount").asInt()).isEqualTo(FREE_MONTHLY + 1);
  }

  @Test
  void fiftyConcurrentEventsAtTheCapCreateOneNotificationAndOneCapNotice() throws Exception {
    TestUser user = user(true, false, UserStatus.ACTIVE, "en");
    jdbc.sql(
            "INSERT INTO usage_counter (user_id, period_month, notifications_created)"
                + " VALUES (?, ?, ?)")
        .params(user.id(), month(), FREE_MONTHLY - 1)
        .update();
    List<UUID> products = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      UUID product = syntheticProduct("AVAILABLE", "100.00");
      products.add(product);
      subscription(user, product, "TR", AlertTrigger.PRICE_CHANGE, null, null);
    }
    List<Callable<Integer>> tasks = new ArrayList<>();
    for (UUID product : products) {
      tasks.add(() -> {
        offersChanged(product, "TR", state("100.00", true), state("90.00", true));
        return processor.processDue().claimed();
      });
    }
    parallel(tasks);
    drainOutbox();
    assertThat(alertNotifications(user)).isEqualTo(1);
    assertThat(capNotices(user)).isEqualTo(1);
    assertThat(
            count(
                "SELECT notifications_created FROM usage_counter WHERE user_id = ?"
                    + " AND period_month = ?",
                user.id(),
                month()))
        .isEqualTo(FREE_MONTHLY);
    assertThat(outcomes()).doesNotContain("PENDING", "DEAD_LETTER");
    assertThat(
            count(
                "SELECT count(*) FROM alert_subscription WHERE user_id = ?"
                    + " AND last_triggered_at IS NOT NULL",
                user.id()))
        .as("a capped firing sets no last_triggered_at")
        .isEqualTo(1);
  }

  @Test
  void anAlertCreatedAfterAnImportedChangeDoesNotFireForThatChange() throws Exception {
    String admin = adminToken();
    UUID dropped = syntheticProduct("AVAILABLE", "100.00");
    UUID restocked = syntheticProduct("AVAILABLE", null);
    setOffer(restocked, "100.00", false);
    JsonNode job =
        awaitJob(
            importOffersCsv(
                CatalogAdversarialTest.CSV_HEADER
                    + offerRow(code(dropped), "80.00", true)
                    + offerRow(code(restocked), "100.00", true),
                admin,
                Map.of()),
            admin);
    assertThat(job.get("status").asString()).as(job.toString()).isEqualTo("SUCCEEDED");
    assertThat(events()).as("the import queued its events").hasSize(2);

    TestUser user = user();
    String token = token(user);
    alertId(createAlert(token, dropped, "TR", "PRICE_CHANGE"));
    alertId(createPriceDrop(token, dropped, "TR", 5));
    alertId(createAlert(token, restocked, "TR", "BACK_IN_STOCK"));
    drainOutbox();

    assertThat(notifications(user))
        .as("subscriptions created after the change must not fire for it")
        .isEmpty();
    assertThat(mailCount(user)).isZero();
  }

  @Test
  void replayedImportsAndReprocessedEventsNeverNotifyTwice() throws Exception {
    String admin = adminToken();
    UUID product = syntheticProduct("AVAILABLE", "100.00");
    TestUser user = user();
    String token = token(user);
    alertId(createAlert(token, product, "TR", "PRICE_CHANGE"));
    String csv = CatalogAdversarialTest.CSV_HEADER + offerRow(code(product), "90.00", true);
    awaitJob(importOffersCsv(csv, admin, Map.of()), admin);
    drainOutbox();
    assertThat(alertNotifications(user)).isEqualTo(1);
    awaitMessage(user.email());

    JsonNode again = awaitJob(importOffersCsv(csv, admin, Map.of()), admin);
    assertThat(again.get("summary").get("changedPairs").asInt()).isZero();
    drainOutbox();

    // A crash between the evaluation and the finish makes an event claimable again.
    jdbc.sql("UPDATE outbox_event SET processed_at = NULL, outcome = NULL, lease_until = NULL")
        .update();
    drainOutbox();
    drainOutbox();
    assertThat(alertNotifications(user)).as("deduplicated through alert_delivery").isEqualTo(1);
    Thread.sleep(1000);
    assertThat(mailCount(user)).as("email_sent_at is re-checked").isEqualTo(1);
  }

  @Test
  void eventsProcessedOutOfOrderAfterAFailedAttemptReportTheCurrentPrice() {
    TestUser user = user(true, false, UserStatus.ACTIVE, "en");
    String token = token(user);
    UUID product = syntheticProduct("AVAILABLE", "100.00");
    alertId(createAlert(token, product, "TR", "PRICE_CHANGE"));
    offersChanged(product, "TR", state("100.00", true), state("80.50", true));
    offersChanged(product, "TR", state("80.50", true), state("90.50", true));
    setOffer(product, "90.50", true);
    UUID older =
        jdbc.sql(
                "SELECT id FROM outbox_event"
                    + " WHERE (payload -> 'after' ->> 'effectivePrice')::numeric = 80.5")
            .query(UUID.class)
            .single();
    // The older event failed once and waits for its back-off.
    jdbc.sql(
            "UPDATE outbox_event SET attempts = 1, last_error = 'SimulatedFailure',"
                + " next_attempt_at = ? WHERE id = ?")
        .params(Timestamp.from(clock.instant().plus(Duration.ofMinutes(2))), older)
        .update();
    drainOutbox();
    clock.advance(Duration.ofMinutes(3));
    drainOutbox();
    List<Map<String, Object>> rows = notifications(user);
    assertThat(rows).hasSizeLessThanOrEqualTo(2);
    assertThat(outcomes()).containsOnly("DELIVERED", "SKIPPED_INELIGIBLE");
    Resp inbox = send("GET", API + "/me/notifications", token);
    JsonNode newest = inbox.json().get("items").get(0);
    assertThat(newest.get("payload").get("after").get("effectivePrice").decimalValue())
        .as("the newest notification must not announce a price older than the current one")
        .isEqualByComparingTo("90.5");
  }

  @Test
  void usersWhoMustNotGetAlertMailGetOnlyTheInAppNotification() throws Exception {
    TestUser optedOut = user(true, false, UserStatus.ACTIVE, "en");
    TestUser unverified = user(false, true, UserStatus.ACTIVE, "en");
    TestUser disabled = user(true, true, UserStatus.DISABLED, "en");
    TestUser lateOptOut = user(true, true, UserStatus.ACTIVE, "tr");
    UUID product = syntheticProduct("AVAILABLE", "100.00");
    for (TestUser u : List.of(optedOut, unverified, disabled, lateOptOut)) {
      subscription(u, product, "TR", AlertTrigger.PRICE_CHANGE, null, null);
    }
    offersChanged(product, "TR", state("100.00", true), state("95.00", true));
    assertThat(processor.processDue().claimed()).isEqualTo(1);
    Resp optOut =
        sendJson("PATCH", API + "/me", token(lateOptOut), "{\"emailAlertsEnabled\":false}");
    assertThat(optOut.status()).as(optOut.toString()).isEqualTo(200);
    drainOutbox();
    for (TestUser u : List.of(optedOut, unverified, disabled, lateOptOut)) {
      assertThat(notifications(u)).as("in-app notification of %s", u.email()).hasSize(1);
      assertThat(notifications(u).getFirst().get("email_sent_at")).isNull();
    }
    assertThat(transport.calls).as("no alert e-mail reached the transport").isEmpty();
    Thread.sleep(1000);
    for (TestUser u : List.of(optedOut, unverified, disabled, lateOptOut)) {
      assertThat(mailCount(u)).isZero();
    }
    assertThat(
            count(
                "SELECT count(*) FROM outbox_event WHERE event_type = 'NOTIFICATION_CREATED'"
                    + " AND outcome = 'SKIPPED_INELIGIBLE'"))
        .isEqualTo(4);
  }

  @Test
  void eventsOfAUserDeletedBehindTheApiEndWithoutMailOrError() throws Exception {
    TestUser user = user();
    UUID product = syntheticProduct("AVAILABLE", "100.00");
    subscription(user, product, "TR", AlertTrigger.PRICE_CHANGE, null, null);
    offersChanged(product, "TR", state("100.00", true), state("95.00", true));
    processor.processDue();
    assertThat(
            count(
                "SELECT count(*) FROM outbox_event WHERE event_type = 'NOTIFICATION_CREATED'"
                    + " AND processed_at IS NULL"))
        .isEqualTo(1);
    jdbc.sql("DELETE FROM app_user WHERE id = ?").param(user.id()).update();
    offersChanged(product, "TR", state("95.00", true), state("90.00", true));
    drainOutbox();
    assertThat(outcomes()).containsExactly("DELIVERED", "DELIVERED", "SKIPPED_DELETED");
    assertThat(transport.calls).isEmpty();
    Thread.sleep(500);
    assertThat(mailCount(user)).isZero();
  }

  private UUID insertEvent(String type, String aggregateType, String payload) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO outbox_event (id, event_type, aggregate_type, aggregate_id, payload,"
                + " created_at, next_attempt_at) VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?)")
        .params(
            id,
            type,
            aggregateType,
            "adversarial-" + id.toString().substring(0, 8),
            payload,
            Timestamp.from(clock.instant()),
            Timestamp.from(clock.instant()))
        .update();
    return id;
  }

  @Test
  void poisonEventsDeadLetterWithoutBlockingOthersAndShowInTheStats() throws Exception {
    TestUser user = user(true, false, UserStatus.ACTIVE, "en");
    UUID product = syntheticProduct("AVAILABLE", "100.00");
    subscription(user, product, "TR", AlertTrigger.PRICE_CHANGE, null, null);
    UUID gone = UUID.randomUUID();
    List<UUID> poison =
        List.of(
            insertEvent("OFFERS_CHANGED", "PRODUCT_COUNTRY", "{\"productId\":\"not-a-uuid\"}"),
            insertEvent(
                "OFFERS_CHANGED",
                "PRODUCT_COUNTRY",
                "{\"productId\":\""
                    + product
                    + "\",\"countryCode\":\"TR\","
                    + "\"before\":{\"effectivePrice\":\"abc\"},\"after\":[],\"catalogVersion\":1}"),
            insertEvent("NOTIFICATION_CREATED", "NOTIFICATION", "{\"notificationId\":12}"),
            insertEvent(
                "NOTIFICATION_CREATED",
                "NOTIFICATION",
                "{\"notificationId\":\"" + "<script>".repeat(20) + "\"}"));
    UUID deletedProduct =
        insertEvent(
            "OFFERS_CHANGED",
            "PRODUCT_COUNTRY",
            "{\"productId\":\""
                + gone
                + "\",\"countryCode\":\"TR\","
                + "\"before\":{\"effectivePrice\":100,\"inStock\":true,\"retailer\":\"R\","
                + "\"url\":\"https://shop.example.com/x\"},\"after\":{\"effectivePrice\":50,"
                + "\"inStock\":true,\"retailer\":\"R\",\"url\":\"https://shop.example.com/x\"},"
                + "\"catalogVersion\":1}");
    UUID empty = insertEvent("NOTIFICATION_CREATED", "NOTIFICATION", "{}");
    offersChanged(product, "TR", state("100.00", true), state("95.00", true));

    processor.processDue();
    assertThat(alertNotifications(user)).as("the good event is not blocked").isEqualTo(1);
    drainWithRetries(8);

    for (UUID id : poison) {
      Map<String, Object> row =
          jdbc.sql("SELECT outcome, attempts, last_error FROM outbox_event WHERE id = ?")
              .param(id)
              .query()
              .singleRow();
      assertThat(row.get("outcome")).as("poison event %s", id).isEqualTo("DEAD_LETTER");
      assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(5);
      assertThat(String.valueOf(row.get("last_error"))).doesNotContain("<script>", "not-a-uuid");
    }
    for (UUID id : List.of(deletedProduct, empty)) {
      assertThat(
              jdbc.sql("SELECT processed_at FROM outbox_event WHERE id = ?")
                  .param(id)
                  .query()
                  .singleRow()
                  .get("processed_at"))
          .as("event %s ends", id)
          .isNotNull();
    }
    assertThat(alertNotifications(user)).isEqualTo(1);

    Resp stats = send("GET", API + "/admin/outbox/stats", adminToken());
    assertThat(stats.status()).as(stats.toString()).isEqualTo(200);
    com.vitadelta.api.identity.ContractSchemas.assertConforms("OutboxStats", stats.body());
    assertThat(stats.json().get("deadLetter").asLong())
        .isEqualTo(count("SELECT count(*) FROM outbox_event WHERE outcome = 'DEAD_LETTER'"));
    assertThat(stats.json().get("deadLetter").asLong()).isGreaterThanOrEqualTo(poison.size());
    assertThat(stats.json().get("pending").asLong()).isZero();
    assertThat(stats.body()).doesNotContain("script", "not-a-uuid", "Exception");

    assertThatThrownBy(() -> insertEvent("SEND_ALL_MAIL", "NOTIFICATION", "{}"))
        .as("an unknown event type cannot even be stored");
    assertThatThrownBy(() -> insertEvent("OFFERS_CHANGED", "PRODUCT_COUNTRY", "[1,2]"))
        .as("a non-object payload cannot be stored");
  }

  @Test
  void anSmtpOutageIsRetriedWithBackOffAndDeliversExactlyOneMail() throws Exception {
    TestUser user = user();
    UUID product = syntheticProduct("AVAILABLE", "100.00");
    subscription(user, product, "TR", AlertTrigger.PRICE_CHANGE, null, null);
    transport.failNext(3);
    offersChanged(product, "TR", state("100.00", true), state("95.00", true));
    processor.processDue();
    for (int attempt = 1; attempt <= 3; attempt++) {
      processor.processDue();
      Map<String, Object> row =
          jdbc.sql(
                  "SELECT attempts, next_attempt_at, processed_at, last_error FROM outbox_event"
                      + " WHERE event_type = 'NOTIFICATION_CREATED'")
              .query()
              .singleRow();
      assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(attempt);
      assertThat(row.get("processed_at")).isNull();
      Instant next = ((Timestamp) row.get("next_attempt_at")).toInstant();
      Duration backOff = Duration.between(clock.instant(), next);
      assertThat(backOff.toSeconds())
          .as("back-off after attempt %d", attempt)
          .isBetween((1L << attempt) * 60 - 2, (1L << attempt) * 60 + 2);
      assertThat(String.valueOf(row.get("last_error"))).doesNotContain(user.email());
      clock.advance(backOff.minusSeconds(5));
      assertThat(processor.processDue().claimed()).as("not claimable before its back-off").isZero();
      clock.advance(Duration.ofSeconds(10));
    }
    processor.processDue();
    drainWithRetries(2);
    awaitMessage(user.email());
    Thread.sleep(1000);
    assertThat(mailCount(user)).as("exactly one mail after the recovery").isEqualTo(1);
    assertThat(notifications(user).getFirst().get("email_sent_at")).isNotNull();
    assertThat(transport.calls).hasSize(4);
    assertThat(outcomes()).containsOnly("DELIVERED");
  }

  @Test
  void aPermanentOutageDeadLettersTheMailButKeepsTheInAppNotification() throws Exception {
    TestUser user = user();
    UUID product = syntheticProduct("AVAILABLE", "100.00");
    subscription(user, product, "TR", AlertTrigger.PRICE_CHANGE, null, null);
    transport.failNext(50);
    offersChanged(product, "TR", state("100.00", true), state("95.00", true));
    drainWithRetries(8);
    assertThat(
            count(
                "SELECT count(*) FROM outbox_event WHERE event_type = 'NOTIFICATION_CREATED'"
                    + " AND outcome = 'DEAD_LETTER' AND attempts = 5"))
        .isEqualTo(1);
    assertThat(transport.calls).as("no attempt beyond the fifth").hasSize(5);
    assertThat(notifications(user)).hasSize(1);
    assertThat(notifications(user).getFirst().get("email_error")).isNotNull();
    assertThat(String.valueOf(notifications(user).getFirst().get("email_error")))
        .doesNotContain(user.email());
    assertThat(mailCount(user)).isZero();
    transport.failNext(0);
    drainWithRetries(2);
    assertThat(mailCount(user)).as("a dead letter is never retried").isZero();
  }
}
