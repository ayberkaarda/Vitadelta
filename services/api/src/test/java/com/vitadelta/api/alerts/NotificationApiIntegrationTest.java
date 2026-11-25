package com.vitadelta.api.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.identity.ContractSchemas;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;

/** The notification inbox (section 8.6): paging, unread filter, read marks and the counter. */
class NotificationApiIntegrationTest extends AlertsIntegrationTest {

  private static final String INBOX = "/api/v1/me/notifications";

  /** One notification of every type (alert via the evaluator), oldest first. */
  private List<UUID> inbox(TestUser user) {
    UUID gold = productId("GOLD-A");
    subscription(
        user,
        gold,
        "TR",
        AlertTrigger.PRICE_DROP,
        new BigDecimal("5.00"),
        new BigDecimal("800.00"));
    transactions.executeWithoutResult(tx ->
        subscriptionService.activate(
            user.id(),
            com.vitadelta.api.entitlement.PlanCode.PRO,
            30,
            com.vitadelta.api.entitlement.SubscriptionSource.MANUAL));
    clock.advance(Duration.ofSeconds(1));
    offersChanged(gold, "TR", state("800.00", true), state("700.00", true));
    drainOutbox();
    clock.advance(Duration.ofSeconds(1));
    jdbc.sql(
            "INSERT INTO notification (id, user_id, type, payload, cap_period_month, created_at)"
                + " VALUES (?, ?, 'NOTIFICATION_CAP_REACHED',"
                + " '{\"periodMonth\":\"2026-10-01\",\"limit\":30}'::jsonb, '2026-10-01', ?)")
        .params(UUID.randomUUID(), user.id(), OutboxRepository.ts(clock.instant()))
        .update();
    clock.advance(Duration.ofSeconds(31L * 24 * 3600));
    subscriptionService.expireIfDue(user.id());
    List<UUID> ids = new ArrayList<>();
    notifications(user).forEach(n -> ids.add((UUID) n.get("id")));
    assertThat(ids).hasSize(4);
    return ids;
  }

  @Test
  void theInboxListsEveryTypeNewestFirstWithPagingAndTheUnreadFilter() {
    TestUser user = user();
    List<UUID> ids = inbox(user);
    String token = token(user);

    EntityExchangeResult<String> all = get(INBOX, token);
    assertStatus(all, 200);
    ContractSchemas.assertConforms("NotificationPage", all.getResponseBody());
    JsonNode body = json(all);
    assertThat(body.get("meta").toString()).isEqualTo("{\"page\":1,\"pageSize\":20,\"total\":4}");
    List<String> types = new ArrayList<>();
    body.get("items").forEach(n -> types.add(n.get("type").asString()));
    assertThat(types)
        .containsExactly(
            "PLAN_EXPIRED", "NOTIFICATION_CAP_REACHED", "ALERT_PRICE_DROP", "PLAN_ACTIVATED");
    JsonNode alert = body.get("items").get(2);
    assertThat(alert.get("payload").get("after").get("effectivePrice").decimalValue())
        .isEqualByComparingTo("700.00");
    assertThat(alert.get("readAt").isNull()).isTrue();

    EntityExchangeResult<String> paged = get(INBOX + "?page=2&pageSize=3", token);
    assertStatus(paged, 200);
    assertThat(json(paged).get("items")).hasSize(1);
    assertThat(json(paged).get("items").get(0).get("id").asString())
        .isEqualTo(ids.getFirst().toString());
    assertThat(json(get(INBOX + "?page=9", token)).get("items")).isEmpty();

    assertStatus(postEmpty(INBOX + "/" + ids.getFirst() + "/read", token), 204);
    String firstRead = notifications(user).getFirst().get("read_at").toString();
    clock.advance(Duration.ofMinutes(5));
    assertStatus(postEmpty(INBOX + "/" + ids.getFirst() + "/read", token), 204);
    assertThat(notifications(user).getFirst().get("read_at").toString()).isEqualTo(firstRead);

    EntityExchangeResult<String> unread = get(INBOX + "?unreadOnly=true", token);
    ContractSchemas.assertConforms("NotificationPage", unread.getResponseBody());
    assertThat(json(unread).get("meta").get("total").asInt()).isEqualTo(3);
    EntityExchangeResult<String> counter = get(INBOX + "/unread-count", token);
    assertStatus(counter, 200);
    ContractSchemas.assertConforms("UnreadCount", counter.getResponseBody());
    assertThat(json(counter).get("unreadCount").asInt()).isEqualTo(3);

    assertStatus(postEmpty(INBOX + "/read-all", token), 204);
    assertThat(json(get(INBOX + "/unread-count", token)).get("unreadCount").asInt()).isZero();
    assertThat(notifications(user).getFirst().get("read_at").toString()).isEqualTo(firstRead);
    assertStatus(postEmpty(INBOX + "/read-all", token), 204);
    assertThat(json(get(INBOX + "?unreadOnly=false", token)).get("meta").get("total").asInt())
        .isEqualTo(4);
  }

  @Test
  void invalidParametersAndMissingCredentialsAreRejected() {
    String token = token(user());
    for (String query :
        List.of("?unreadOnly=yes", "?page=0", "?pageSize=0", "?pageSize=101", "?page=x")) {
      assertProblem(get(INBOX + query, token), 422, "validation_failed");
    }
    assertProblem(get(INBOX, null), 401, "unauthorized");
    assertProblem(get(INBOX + "/unread-count", null), 401, "unauthorized");
    assertProblem(postEmpty(INBOX + "/read-all", null), 401, "unauthorized");
    EntityExchangeResult<String> empty = get(INBOX, token);
    assertStatus(empty, 200);
    assertThat(json(empty).get("items")).isEmpty();
    assertThat(json(get(INBOX + "/unread-count", token)).get("unreadCount").asInt()).isZero();
  }
}
