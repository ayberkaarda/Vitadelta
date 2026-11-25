package com.vitadelta.api.adversarial;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.adversarial.AttackClient.Resp;
import com.vitadelta.api.alerts.AlertTrigger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Section 15.7 case 10 for the Phase 5 tables (section 5.9 cascade): after {@code DELETE /me} no
 * saved product, alert subscription, notification, alert delivery, usage counter or pending outbox
 * event of the user remains; an event that races the deletion ends {@code SKIPPED_DELETED} (or is
 * purged), never as a failure; and the deletion never fails or deadlocks while the outbox processor
 * evaluates or mails for the same user.
 */
class AlertAccountDeletionAdversarialTest extends AlertsAdversarialTest {

  private Resp deleteAccount(String token) {
    return sendJson("DELETE", API + "/me", token, Map.of("password", PASSWORD));
  }

  private void assertNothingLeft(TestUser user, List<UUID> notificationIds, List<UUID> alertIds) {
    for (String table :
        List.of(
            "saved_product",
            "alert_subscription",
            "notification",
            "usage_counter",
            "subscription",
            "app_user")) {
      String column = table.equals("app_user") ? "id" : "user_id";
      assertThat(count("SELECT count(*) FROM " + table + " WHERE " + column + " = ?", user.id()))
          .as("%s rows of the deleted user", table)
          .isZero();
    }
    for (UUID alert : alertIds) {
      assertThat(count("SELECT count(*) FROM alert_delivery WHERE subscription_id = ?", alert))
          .isZero();
    }
    for (UUID notification : notificationIds) {
      assertThat(
              count(
                  "SELECT count(*) FROM outbox_event WHERE aggregate_id = ?"
                      + " AND processed_at IS NULL",
                  notification.toString()))
          .as("pending event of notification %s", notification)
          .isZero();
      assertThat(
              count("SELECT count(*) FROM alert_delivery WHERE notification_id = ?", notification))
          .isZero();
    }
  }

  private List<UUID> notificationIds(TestUser user) {
    return jdbc.sql("SELECT id FROM notification WHERE user_id = ?")
        .param(user.id())
        .query(UUID.class)
        .list();
  }

  @Test
  void deletionRemovesEveryPhaseFiveRowOfTheUser() {
    TestUser user = withPassword(user());
    String token = token(user);
    List<UUID> products = availableIn("TR", 3);
    UUID synthetic = syntheticProduct("AVAILABLE", "100.00");
    assertThat(saveProduct(token, products.get(0), "TR").status()).isEqualTo(201);
    List<UUID> alerts = new ArrayList<>();
    alerts.add(alertId(createAlert(token, synthetic, "TR", "PRICE_CHANGE")));
    alerts.add(alertId(createAlert(token, products.get(1), "TR", "BACK_IN_STOCK")));
    insertNotification(user);
    offersChanged(synthetic, "TR", state("100.00", true), state("95.00", true));
    processor.processDue();
    offersChanged(synthetic, "TR", state("95.00", true), state("90.00", true));
    processor.processDue();
    List<UUID> notifications = notificationIds(user);
    assertThat(notifications).hasSizeGreaterThanOrEqualTo(3);
    assertThat(
            count("SELECT count(*) FROM alert_delivery WHERE subscription_id = ?", alerts.get(0)))
        .isGreaterThan(0);
    assertThat(count("SELECT count(*) FROM usage_counter WHERE user_id = ?", user.id()))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM outbox_event WHERE event_type = 'NOTIFICATION_CREATED'"
                    + " AND processed_at IS NULL"))
        .as("a pending mail event exists before the deletion")
        .isGreaterThan(0);

    int mailsBefore = transport.calls.size();
    Resp deleted = deleteAccount(token);
    assertThat(deleted.status()).as(deleted.toString()).isEqualTo(204);
    assertNothingLeft(user, notifications, alerts);
    Resp stale = send("GET", API + "/me/alerts", token);
    assertThat(stale.status()).as(stale.toString()).isIn(200, 401);
    assertThat(stale.body()).doesNotContain(alerts.get(0).toString());
    AttackClient.assertProblem(saveProduct(token, products.get(2), "TR"), 401, "unauthorized");
    assertThat(createAlert(token, products.get(2), "TR", "PRICE_CHANGE").status()).isEqualTo(401);
    drainOutbox();
    assertThat(transport.calls).as("no mail after the deletion").hasSize(mailsBefore);
  }

  @Test
  void aDeletionDuringTheMailDispatchSucceedsAndTheEventEndsSkippedOrPurged() {
    for (int round = 0; round < 6; round++) {
      transport.reset();
      clearOutbox();
      TestUser user = withPassword(user());
      String token = token(user);
      UUID product = syntheticProduct("AVAILABLE", "100.00");
      UUID alert = alertId(createAlert(token, product, "TR", "PRICE_CHANGE"));
      offersChanged(product, "TR", state("100.00", true), state("95.00", true));
      processor.processDue();
      List<UUID> notifications = notificationIds(user);
      AtomicReference<Resp> deletion = new AtomicReference<>();
      transport.during(message -> deletion.set(deleteAccount(token)));
      processor.processDue();
      assertThat(deletion.get()).as("round %d: deletion ran during SMTP", round).isNotNull();
      assertThat(deletion.get().status()).as(deletion.get().toString()).isEqualTo(204);
      List<String> mailOutcomes =
          jdbc.sql(
                  "SELECT coalesce(outcome, 'PENDING') FROM outbox_event"
                      + " WHERE event_type = 'NOTIFICATION_CREATED'")
              .query(String.class)
              .list();
      assertThat(mailOutcomes).as("round %d", round).allMatch(o -> o.equals("SKIPPED_DELETED"));
      assertNothingLeft(user, notifications, List.of(alert));
      transport.during(message -> {});
    }
  }

  @Test
  void deletionsRacingTheProcessorNeverFailOrDeadlock() throws Exception {
    for (int round = 0; round < 12; round++) {
      clearOutbox();
      transport.reset();
      TestUser user = withPassword(user());
      String token = token(user);
      UUID product = syntheticProduct("AVAILABLE", "100.00");
      UUID first = alertId(createAlert(token, product, "TR", "PRICE_CHANGE"));
      UUID second = subscription(user, product, "TR", AlertTrigger.BACK_IN_STOCK, null, null);
      offersChanged(product, "TR", state("100.00", false), state("95.00", true));
      if (round % 2 == 0) {
        processor.processDue();
      }
      List<UUID> notifications = notificationIds(user);
      List<Callable<Object>> tasks = new ArrayList<>();
      tasks.add(() -> deleteAccount(token));
      tasks.add(() -> {
        drainOutbox();
        return "drained";
      });
      tasks.add(() -> {
        offersChanged(product, "TR", state("95.00", true), state("90.00", true));
        return processor.processDue();
      });
      List<Object> results = parallel(tasks);
      Resp deleted = (Resp) results.getFirst();
      assertThat(deleted.status()).as("round %d: %s", round, deleted).isEqualTo(204);
      drainOutbox();
      assertThat(outcomes()).as("round %d", round).doesNotContain("PENDING", "DEAD_LETTER");
      List<UUID> all = new ArrayList<>(notifications);
      all.addAll(notificationIds(user));
      assertNothingLeft(user, all, List.of(first, second));
    }
  }
}
