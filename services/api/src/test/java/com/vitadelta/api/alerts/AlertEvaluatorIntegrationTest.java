package com.vitadelta.api.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.entitlement.NotificationAllowance;
import com.vitadelta.api.entitlement.PlanCode;
import com.vitadelta.api.entitlement.SubscriptionSource;
import com.vitadelta.api.identity.ContractSchemas;
import com.vitadelta.api.identity.UserDeletingEvent;
import com.vitadelta.api.identity.UserStatus;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.JsonNode;

/**
 * The alert evaluator, the monthly cap, e-mail eligibility, the deletion cascade and the plan
 * notifications (sections 5.9, 8.3, 8.4 and 8.6), plus the content of the delivered alert e-mails
 * (section 8.7).
 */
class AlertEvaluatorIntegrationTest extends AlertsIntegrationTest {

  private static final String HOSTILE_CODE = "ALERT-XSS-1";

  @Autowired private AlertEvaluator evaluator;
  @Autowired private ApplicationEventPublisher events;
  @Autowired private MeterRegistry meters;

  @AfterEach
  void removeHostileProduct() {
    jdbc.sql("DELETE FROM product WHERE code = ?").param(HOSTILE_CODE).update();
  }

  private UUID goldA() {
    return productId("GOLD-A");
  }

  private JsonNode payload(Map<String, Object> notification) {
    return JSON.readTree((String) notification.get("payload"));
  }

  private long mailEvents() {
    return events().stream()
        .filter(e -> e.get("event_type").equals("NOTIFICATION_CREATED"))
        .count();
  }

  @Test
  void priceDropFiresOnceTheCumulativeDropFromTheBaselineReachesTheThreshold() {
    TestUser user = user();
    UUID sub =
        subscription(
            user,
            goldA(),
            "TR",
            AlertTrigger.PRICE_DROP,
            new BigDecimal("5.00"),
            new BigDecimal("100.00"));
    double firedBefore = meters.counter(AlertMetrics.FIRED, "trigger", "PRICE_DROP").count();

    offersChanged(goldA(), "TR", state("100.00", true), state("97.00", true));
    drainOutbox();
    assertThat(notifications(user)).isEmpty();
    assertThat(subscriptionRow(sub).get("baseline_price")).isEqualTo(new BigDecimal("100.00"));

    offersChanged(goldA(), "TR", state("97.00", true), state("94.09", true));
    drainOutbox();
    List<Map<String, Object>> created = notifications(user);
    assertThat(created).hasSize(1);
    assertThat(created.getFirst().get("type")).isEqualTo("ALERT_PRICE_DROP");
    JsonNode payload = payload(created.getFirst());
    assertThat(payload.get("baselinePrice").decimalValue()).isEqualByComparingTo("100.00");
    assertThat(payload.get("thresholdPercent").decimalValue()).isEqualByComparingTo("5");
    assertThat(payload.get("before").get("effectivePrice").decimalValue())
        .isEqualByComparingTo("97.00");
    assertThat(payload.get("after").get("effectivePrice").decimalValue())
        .isEqualByComparingTo("94.09");
    assertThat(payload.get("currency").asString()).isEqualTo("TRY");
    assertThat(payload.get("productCode").asString()).isEqualTo("GOLD-A");
    assertThat(payload.get("subscriptionId").asString()).isEqualTo(sub.toString());
    ContractSchemas.assertConforms("AlertNotificationPayload", payload.toString());
    Map<String, Object> row = subscriptionRow(sub);
    assertThat(row.get("baseline_price")).isEqualTo(new BigDecimal("94.09"));
    assertThat(row.get("last_triggered_at")).isNotNull();
    assertThat(meters.counter(AlertMetrics.FIRED, "trigger", "PRICE_DROP").count())
        .isEqualTo(firedBefore + 1);

    // The baseline was reset: a further 3 % drop does not fire.
    offersChanged(goldA(), "TR", state("94.09", true), state("91.27", true));
    drainOutbox();
    assertThat(notifications(user)).hasSize(1);
  }

  @Test
  void priceDropWithoutBaselineStoresTheFirstPriceAndDoesNotFire() {
    TestUser user = user();
    UUID sub =
        subscription(user, goldA(), "TR", AlertTrigger.PRICE_DROP, new BigDecimal("5.00"), null);
    offersChanged(goldA(), "TR", null, null);
    drainOutbox();
    assertThat(subscriptionRow(sub).get("baseline_price")).isNull();
    offersChanged(goldA(), "TR", null, state("800.00", true));
    drainOutbox();
    assertThat(notifications(user)).isEmpty();
    assertThat(subscriptionRow(sub).get("baseline_price")).isEqualTo(new BigDecimal("800.00"));
    offersChanged(goldA(), "TR", state("800.00", true), state("700.00", true));
    drainOutbox();
    assertThat(notifications(user)).hasSize(1);
  }

  @Test
  void backInStockFiresOnlyFromOutOfStockToInStock() {
    TestUser user = user();
    subscription(user, goldA(), "TR", AlertTrigger.BACK_IN_STOCK, null, null);
    offersChanged(goldA(), "TR", null, state("800.00", true));
    offersChanged(goldA(), "TR", state("800.00", true), state("790.00", true));
    offersChanged(goldA(), "TR", state("790.00", true), state("790.00", false));
    drainOutbox();
    assertThat(notifications(user)).isEmpty();
    offersChanged(goldA(), "TR", state("790.00", false), state("790.00", true));
    drainOutbox();
    List<Map<String, Object>> created = notifications(user);
    assertThat(created).hasSize(1);
    assertThat(created.getFirst().get("type")).isEqualTo("ALERT_BACK_IN_STOCK");
    JsonNode payload = payload(created.getFirst());
    assertThat(payload.get("thresholdPercent").isNull()).isTrue();
    assertThat(payload.get("baselinePrice").isNull()).isTrue();
    assertThat(payload.get("before").get("inStock").asBoolean()).isFalse();
    assertThat(payload.get("after").get("inStock").asBoolean()).isTrue();
  }

  @Test
  void priceChangeFiresWhenBothPricesArePresentAndDiffer() {
    TestUser user = user();
    subscription(user, goldA(), "TR", AlertTrigger.PRICE_CHANGE, null, null);
    offersChanged(goldA(), "TR", null, state("800.00", true));
    offersChanged(goldA(), "TR", state("800.00", true), state("800.00", false));
    offersChanged(goldA(), "TR", state("800.00", true), null);
    drainOutbox();
    assertThat(notifications(user)).isEmpty();
    offersChanged(goldA(), "TR", state("800.00", true), state("820.50", true));
    drainOutbox();
    assertThat(notifications(user)).hasSize(1);
    assertThat(notifications(user).getFirst().get("type")).isEqualTo("ALERT_PRICE_CHANGE");
  }

  @Test
  void inactiveSubscriptionsAndOtherCountriesAreNotEvaluated() {
    TestUser user = user();
    UUID sub = subscription(user, goldA(), "TR", AlertTrigger.PRICE_CHANGE, null, null);
    jdbc.sql("UPDATE alert_subscription SET active = false WHERE id = ?").param(sub).update();
    TestUser other = user();
    subscription(other, goldA(), "DE", AlertTrigger.PRICE_CHANGE, null, null);
    offersChanged(goldA(), "TR", state("800.00", true), state("700.00", true));
    drainOutbox();
    assertThat(notifications(user)).isEmpty();
    assertThat(notifications(other)).isEmpty();
  }

  private long boundary(UUID subscription) {
    return jdbc.sql("SELECT catalog_version_boundary FROM alert_subscription WHERE id = ?")
        .param(subscription)
        .query(Long.class)
        .single();
  }

  private void redeliver(UUID event) {
    jdbc.sql("UPDATE outbox_event SET processed_at = NULL, outcome = NULL WHERE id = ?")
        .param(event)
        .update();
  }

  @Test
  void aRedeliveredEventIsRejectedByTheVersionBoundary() {
    TestUser user = user();
    UUID sub = subscription(user, goldA(), "TR", AlertTrigger.PRICE_CHANGE, null, null);
    long boundaryBefore = boundary(sub);
    offersChanged(goldA(), "TR", state("800.00", true), state("700.00", true));
    UUID event = (UUID) events().getFirst().get("id");
    processor.processDue();
    assertThat(notifications(user)).hasSize(1);
    // The firing moved the boundary to the event's catalog version.
    assertThat(boundary(sub)).isGreaterThan(boundaryBefore);
    // At-least-once: the same event is handed out again (for example after a lost finish).
    redeliver(event);
    processor.processDue();
    assertThat(notifications(user)).hasSize(1);
    assertThat(
            jdbc.sql("SELECT count(*) FROM alert_delivery WHERE subscription_id = ?")
                .param(sub)
                .query(Long.class)
                .single())
        .isEqualTo(1);
    assertThat(
            events().stream()
                .filter(e -> e.get("id").equals(event))
                .findFirst()
                .orElseThrow()
                .get("outcome"))
        .isEqualTo("DELIVERED");
  }

  @Test
  void aRedeliveredEventIsDedupedThroughAlertDelivery() {
    TestUser user = user();
    UUID sub = subscription(user, goldA(), "TR", AlertTrigger.PRICE_CHANGE, null, null);
    long boundaryBefore = boundary(sub);
    offersChanged(goldA(), "TR", state("800.00", true), state("700.00", true));
    UUID event = (UUID) events().getFirst().get("id");
    processor.processDue();
    assertThat(notifications(user)).hasSize(1);
    UUID first = (UUID) notifications(user).getFirst().get("id");
    // The redelivery must pass the version boundary so that only the alert_delivery row can stop
    // the second firing: the boundary is put back to its value before the event.
    jdbc.sql("UPDATE alert_subscription SET catalog_version_boundary = ? WHERE id = ?")
        .params(boundaryBefore, sub)
        .update();
    redeliver(event);
    processor.processDue();
    assertThat(notifications(user)).hasSize(1);
    assertThat(notifications(user).getFirst().get("id")).isEqualTo(first);
    assertThat(
            jdbc.sql("SELECT count(*) FROM alert_delivery WHERE subscription_id = ?")
                .param(sub)
                .query(Long.class)
                .single())
        .isEqualTo(1);
    assertThat(
            jdbc.sql("SELECT notification_id FROM alert_delivery WHERE subscription_id = ?")
                .param(sub)
                .query(UUID.class)
                .single())
        .isEqualTo(notifications(user).getFirst().get("id"));
    assertThat(
            events().stream()
                .filter(e -> e.get("id").equals(event))
                .findFirst()
                .orElseThrow()
                .get("outcome"))
        .isEqualTo("DELIVERED");
  }

  private void usage(TestUser user, int notifications) {
    LocalDate month = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).withDayOfMonth(1);
    jdbc.sql(
            "INSERT INTO usage_counter (user_id, period_month, notifications_created)"
                + " VALUES (?, ?, ?) ON CONFLICT (user_id, period_month)"
                + " DO UPDATE SET notifications_created = EXCLUDED.notifications_created")
        .params(user.id(), month, notifications)
        .update();
  }

  @Test
  void theMonthlyCapCreatesExactlyOneCapNoticeAndSkipsTheFiring() throws Exception {
    TestUser user = user();
    UUID sub = subscription(user, goldA(), "TR", AlertTrigger.PRICE_CHANGE, null, null);
    usage(user, 29);
    offersChanged(goldA(), "TR", state("800.00", true), state("790.00", true));
    processor.processDue();
    assertThat(notifications(user)).hasSize(1);
    Object triggered = subscriptionRow(sub).get("last_triggered_at");
    clock.advance(Duration.ofSeconds(1));

    offersChanged(goldA(), "TR", state("790.00", true), state("780.00", true));
    offersChanged(goldA(), "TR", state("780.00", true), state("770.00", true));
    drainOutbox();
    List<Map<String, Object>> rows = notifications(user);
    assertThat(rows).hasSize(2);
    Map<String, Object> cap = rows.get(1);
    assertThat(cap.get("type")).isEqualTo("NOTIFICATION_CAP_REACHED");
    JsonNode payload = payload(cap);
    ContractSchemas.assertConforms("CapReachedPayload", payload.toString());
    assertThat(payload.get("limit").asInt()).isEqualTo(30);
    assertThat(payload.get("periodMonth").asString())
        .isEqualTo(
            LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).withDayOfMonth(1).toString());
    assertThat(subscriptionRow(sub).get("last_triggered_at")).isEqualTo(triggered);
    assertThat(
            jdbc.sql("SELECT notifications_created FROM usage_counter WHERE user_id = ?")
                .param(user.id())
                .query(Integer.class)
                .single())
        .isEqualTo(30);
    // The cap notice is e-mailed once.
    assertThat(transport.calls)
        .filteredOn(c -> c.message().subject().contains("notification limit"))
        .hasSize(1);

    // Next month: counted again, and a new cap notice is possible.
    clock.advance(Duration.ofDays(32));
    offersChanged(goldA(), "TR", state("770.00", true), state("760.00", true));
    drainOutbox();
    assertThat(notifications(user)).hasSize(3);
    assertThat(notifications(user).get(2).get("type")).isEqualTo("ALERT_PRICE_CHANGE");
  }

  @Test
  void concurrentCapNoticesOfOneMonthAreCreatedOnce() throws Exception {
    TestUser user = user();
    LocalDate month = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).withDayOfMonth(1);
    NotificationAllowance capped =
        new NotificationAllowance(NotificationAllowance.Outcome.CAP_REACHED, 30, 30, month);
    List<Callable<Boolean>> tasks = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      tasks.add(() -> {
        transactions.executeWithoutResult(
            tx -> evaluator.createCapNotice(user.id(), capped, clock.instant()));
        return true;
      });
    }
    parallel(tasks);
    assertThat(notifications(user)).hasSize(1);
    assertThat(mailEvents()).isEqualTo(1);
  }

  @Test
  void unverifiedDisabledAndOptedOutUsersGetTheInAppNotificationOnly() {
    TestUser unverified = user(false, true, UserStatus.ACTIVE, "en");
    TestUser optedOut = user(true, false, UserStatus.ACTIVE, "en");
    TestUser disabled = user(true, true, UserStatus.DISABLED, "en");
    for (TestUser user : List.of(unverified, optedOut, disabled)) {
      subscription(user, goldA(), "TR", AlertTrigger.PRICE_CHANGE, null, null);
    }
    offersChanged(goldA(), "TR", state("800.00", true), state("700.00", true));
    drainOutbox();
    for (TestUser user : List.of(unverified, optedOut, disabled)) {
      assertThat(notifications(user)).hasSize(1);
      assertThat(notifications(user).getFirst().get("email_sent_at")).isNull();
    }
    assertThat(transport.calls).isEmpty();
    assertThat(
            events().stream()
                .filter(e -> e.get("event_type").equals("NOTIFICATION_CREATED"))
                .map(e -> e.get("outcome")))
        .hasSize(3)
        .containsOnly("SKIPPED_INELIGIBLE");
  }

  @Test
  void accountDeletionPurgesThePendingEventsOfTheUsersNotifications() {
    TestUser leaving = user();
    TestUser staying = user();
    subscription(leaving, goldA(), "TR", AlertTrigger.PRICE_CHANGE, null, null);
    subscription(staying, goldA(), "TR", AlertTrigger.PRICE_CHANGE, null, null);
    offersChanged(goldA(), "TR", state("800.00", true), state("700.00", true));
    UUID offersEvent = (UUID) events().getFirst().get("id");
    transport.failNext(10);
    processor.processDue();
    assertThat(mailEvents()).isEqualTo(2);

    transactions.executeWithoutResult(tx -> {
      events.publishEvent(new UserDeletingEvent(leaving.id()));
      users.delete(leaving.id());
    });

    List<Map<String, Object>> left = events();
    assertThat(left.stream().filter(e -> e.get("event_type").equals("NOTIFICATION_CREATED")))
        .hasSize(1)
        .allSatisfy(e ->
            assertThat(e.get("aggregate_id"))
                .isEqualTo(notifications(staying).getFirst().get("id").toString()));
    assertThat(left.stream().map(e -> e.get("id"))).contains(offersEvent);
    assertThat(notifications(leaving)).isEmpty();
  }

  @Test
  void anEventWhoseNotificationOrUserVanishedEndsSkippedDeleted() {
    TestUser racing = user();
    TestUser gone = user();
    subscription(racing, goldA(), "TR", AlertTrigger.PRICE_CHANGE, null, null);
    subscription(gone, goldA(), "TR", AlertTrigger.PRICE_CHANGE, null, null);
    offersChanged(goldA(), "TR", state("800.00", true), state("700.00", true));
    transport.failNext(10);
    processor.processDue();
    // A notification deleted under a pending event, and a user deleted without the listener.
    jdbc.sql("DELETE FROM notification WHERE user_id = ?").param(racing.id()).update();
    jdbc.sql("DELETE FROM app_user WHERE id = ?").param(gone.id()).update();
    transport.reset();
    clock.advance(Duration.ofMinutes(2));
    processor.processDue();
    assertThat(
            events().stream()
                .filter(e -> e.get("event_type").equals("NOTIFICATION_CREATED"))
                .map(e -> e.get("outcome")))
        .hasSize(2)
        .containsOnly("SKIPPED_DELETED");
    assertThat(transport.calls).isEmpty();
  }

  @Test
  void aSubscriptionOfAUserDeletedBeforeEvaluationIsSkipped() {
    TestUser user = user();
    subscription(user, goldA(), "TR", AlertTrigger.PRICE_CHANGE, null, null);
    offersChanged(goldA(), "TR", state("800.00", true), state("700.00", true));
    jdbc.sql("DELETE FROM app_user WHERE id = ?").param(user.id()).update();
    processor.processDue();
    assertThat(events().getFirst().get("outcome")).isEqualTo("DELIVERED");
    assertThat(mailEvents()).isZero();
  }

  @Test
  void planActivationAndExpiryCreateUncountedPlanNotifications() {
    TestUser user = user();
    transactions.executeWithoutResult(
        tx -> subscriptionService.activate(user.id(), PlanCode.PRO, 30, SubscriptionSource.PROMO));
    List<Map<String, Object>> rows = notifications(user);
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().get("type")).isEqualTo("PLAN_ACTIVATED");
    JsonNode activated = payload(rows.getFirst());
    ContractSchemas.assertConforms("PlanActivatedPayload", activated.toString());
    assertThat(activated.get("planCode").asString()).isEqualTo("PRO");
    assertThat(activated.get("source").asString()).isEqualTo("PROMO");
    assertThat(activated.get("extended").asBoolean()).isFalse();

    clock.advance(Duration.ofDays(31));
    assertThat(subscriptionService.expireIfDue(user.id())).isPresent();
    rows = notifications(user);
    assertThat(rows).hasSize(2);
    assertThat(rows.get(1).get("type")).isEqualTo("PLAN_EXPIRED");
    JsonNode expired = payload(rows.get(1));
    ContractSchemas.assertConforms("PlanExpiredPayload", expired.toString());
    assertThat(expired.get("expiredPlanCode").asString()).isEqualTo("PRO");
    assertThat(expired.get("planCode").asString()).isEqualTo("FREE");
    assertThat(
            jdbc.sql("SELECT count(*) FROM usage_counter WHERE user_id = ?")
                .param(user.id())
                .query(Long.class)
                .single())
        .isZero();
    assertThat(mailEvents()).isZero();
  }

  @Test
  void theAlertMailCarriesThePreferenceLinkPrivacyLinkListUnsubscribeAndEscapedNames()
      throws Exception {
    UUID hostile = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO product (id, code, brand, name, dosage_form, units_per_container,"
                + " units_per_serving, servings_per_day, synthetic, created_at, updated_at)"
                + " VALUES (?, ?, 'Synthetic <b>Labs</b>', 'Daily <script>alert(1)</script>',"
                + " 'TABLET', 60, 1, 1, true, now(), now())")
        .params(hostile, HOSTILE_CODE)
        .update();
    TestUser user = user();
    subscription(
        user,
        hostile,
        "TR",
        AlertTrigger.PRICE_DROP,
        new BigDecimal("5.00"),
        new BigDecimal("900.00"));
    offersChanged(hostile, "TR", state("900.00", true), state("846.00", true));
    drainOutbox();
    assertThat(transport.calls).hasSize(1);
    assertThat(transport.calls.getFirst().unsubscribeUrl())
        .isEqualTo("https://vitadelta.test/account");

    JsonNode message = awaitMessage(user.email());
    String html = message.path("HTML").asString();
    String text = message.path("Text").asString();
    assertThat(html).contains("href=\"https://vitadelta.test/account\"");
    assertThat(html).contains("href=\"https://vitadelta.test/privacy\"");
    assertThat(html).doesNotContain("<script>").contains("&lt;script&gt;alert(1)&lt;/script&gt;");
    assertThat(html).doesNotContain("<b>Labs</b>");
    assertThat(text)
        .contains("https://vitadelta.test/account")
        .contains("https://vitadelta.test/privacy");
    assertThat(text).contains("6 %").contains("5 %");
    JsonNode headers = mailpit("/api/v1/message/" + message.path("ID").asString() + "/headers");
    assertThat(headers.path("List-Unsubscribe").get(0).asString())
        .isEqualTo("<https://vitadelta.test/account>");
    assertThat(message.path("Subject").asString()).startsWith("Price drop:");
  }

  @Test
  void theAlertMailIsWrittenInTheUsersLanguage() {
    TestUser user = user(true, true, UserStatus.ACTIVE, "tr");
    subscription(user, goldA(), "TR", AlertTrigger.BACK_IN_STOCK, null, null);
    offersChanged(goldA(), "TR", state("800.00", false), state("800.00", true));
    drainOutbox();
    JsonNode message = awaitMessage(user.email());
    assertThat(message.path("Subject").asString()).startsWith("Yeniden stokta:");
    assertThat(message.path("Text").asString())
        .contains("Bildirim tercihlerinizi değiştirin: https://vitadelta.test/account");
    Instant sent = AlertsTestTimes.instant(notifications(user).getFirst().get("email_sent_at"));
    assertThat(sent).isEqualTo(clock.instant());
  }
}
