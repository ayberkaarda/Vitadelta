package com.vitadelta.api.adversarial;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.adversarial.AttackClient.Resp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Section 15.7 case 2 for the Phase 5 routes: user A attacks every caller-scoped {@code {id}} route
 * of saved products, alert subscriptions and notifications with B's ids, random ids, malformed ids
 * and ids of rows that were deleted. Every case must answer exactly like an unknown id (status,
 * problem body apart from {@code instance}/{@code requestId}, header set) and leave B's rows
 * untouched; list and counter endpoints never show or change another user's rows; products that are
 * not in market cannot be saved, alerted or re-activated (contract {@code saveProduct}, {@code
 * createAlert}, {@code updateAlert}).
 */
class WatchlistIdorAdversarialTest extends AlertsAdversarialTest {

  private TestUser a;
  private TestUser b;
  private String tokenA;
  private String tokenB;
  private List<UUID> products;
  private UUID bPriceDrop;
  private UUID bBackInStock;
  private UUID bNotification;
  private UUID deletedAlertOfA;
  private UUID deletedNotificationOfA;

  @BeforeEach
  void arrangeTwoUsers() {
    a = user();
    b = user();
    tokenA = token(a);
    tokenB = token(b);
    products = availableIn("TR", 4);
    assertThat(saveProduct(tokenB, products.get(0), "TR").status()).isEqualTo(201);
    bPriceDrop = alertId(createAlert(tokenB, products.get(0), "TR", "PRICE_DROP"));
    bBackInStock = alertId(createAlert(tokenB, products.get(1), "TR", "BACK_IN_STOCK"));
    bNotification = insertNotification(b);

    assertThat(alertId(createAlert(tokenA, products.get(2), "TR", "PRICE_CHANGE"))).isNotNull();
    deletedAlertOfA = alertId(createAlert(tokenA, products.get(3), "TR", "PRICE_CHANGE"));
    assertThat(send("DELETE", API + "/me/alerts/" + deletedAlertOfA, tokenA).status())
        .isEqualTo(204);
    insertNotification(a);
    deletedNotificationOfA = insertNotification(a);
    jdbc.sql("DELETE FROM notification WHERE id = ?").param(deletedNotificationOfA).update();
  }

  private List<Map<String, Object>> rowsOf(TestUser user) {
    List<Map<String, Object>> all = new ArrayList<>();
    all.addAll(rows("SELECT * FROM alert_subscription WHERE user_id = ? ORDER BY id", user.id()));
    all.addAll(
        rows(
            "SELECT * FROM saved_product WHERE user_id = ? ORDER BY product_id, country_code",
            user.id()));
    all.addAll(rows("SELECT * FROM notification WHERE user_id = ? ORDER BY id", user.id()));
    all.addAll(rows("SELECT * FROM usage_counter WHERE user_id = ?", user.id()));
    return all;
  }

  /** Ids that must all be answered like an unknown id, as raw path segments. */
  private List<String> hostileIds(UUID foreign, UUID deleted) {
    return List.of(
        foreign.toString(),
        foreign.toString().toUpperCase(Locale.ROOT),
        deleted.toString(),
        "00000000-0000-0000-0000-000000000000",
        "not-a-uuid",
        "12345",
        "%27%20OR%20%271%27%3D%271",
        foreign + "0",
        foreign.toString().replace("-", ""),
        "x".repeat(1500));
  }

  private static void assertSameAs(Resp reference, Resp attempt, String label) {
    assertThat(attempt.status()).as("%s -> %s", label, attempt).isEqualTo(reference.status());
    assertThat(comparableProblem(attempt)).as(label).isEqualTo(comparableProblem(reference));
    assertThat(headerNames(attempt)).as(label).isEqualTo(headerNames(reference));
  }

  @Test
  void everyAlertIdRouteAnswersForeignDeletedAndMalformedIdsLikeAnUnknownId() {
    List<Map<String, Object>> before = rowsOf(b);
    record Route(String method, String body) {}
    List<Route> routes =
        List.of(
            new Route("PATCH", "{\"active\":true}"),
            new Route("PATCH", "{\"active\":false}"),
            new Route("PATCH", "{\"thresholdPercent\":1}"),
            new Route("PATCH", "{\"active\":true,\"thresholdPercent\":0.01}"),
            new Route("PATCH", "{}"),
            new Route("PATCH", "{\"thresholdPercent\":null}"),
            new Route("DELETE", null));
    for (Route route : routes) {
      Resp reference = alertRoute(route.method(), UUID.randomUUID().toString(), route.body());
      boolean validBody =
          route.body() == null || (!route.body().equals("{}") && !route.body().contains("null"));
      if (validBody) {
        assertBlankNotFound(reference);
      }
      for (UUID foreign : List.of(bPriceDrop, bBackInStock)) {
        for (String id : hostileIds(foreign, deletedAlertOfA)) {
          Resp attempt = alertRoute(route.method(), id, route.body());
          assertSameAs(
              reference, attempt, route + " " + id.substring(0, Math.min(40, id.length())));
        }
      }
    }
    assertThat(rowsOf(b)).as("B's rows after the attack").isEqualTo(before);
  }

  private Resp alertRoute(String method, String id, String body) {
    String path = API + "/me/alerts/" + id;
    return body == null ? send(method, path, tokenA) : sendJson(method, path, tokenA, body);
  }

  @Test
  void aThresholdOnAForeignNonPriceDropAlertIsNotAnExistenceOracle() {
    Resp foreign = patchAlert(tokenA, bBackInStock.toString(), "{\"thresholdPercent\":3}");
    Resp unknown = patchAlert(tokenA, UUID.randomUUID().toString(), "{\"thresholdPercent\":3}");
    assertBlankNotFound(foreign);
    assertThat(comparableProblem(foreign)).isEqualTo(comparableProblem(unknown));
    Resp own = patchAlert(tokenB, bBackInStock.toString(), "{\"thresholdPercent\":3}");
    assertThat(own.status()).as("the owner learns the trigger rule: %s", own).isEqualTo(422);
  }

  @Test
  void notificationReadRouteAnswersForeignDeletedAndMalformedIdsLikeAnUnknownId() {
    List<Map<String, Object>> before = rowsOf(b);
    Resp reference = send("POST", API + "/me/notifications/" + UUID.randomUUID() + "/read", tokenA);
    assertBlankNotFound(reference);
    for (String id : hostileIds(bNotification, deletedNotificationOfA)) {
      Resp attempt = send("POST", API + "/me/notifications/" + id + "/read", tokenA);
      assertSameAs(reference, attempt, "read " + id.substring(0, Math.min(40, id.length())));
    }
    assertThat(rowsOf(b)).isEqualTo(before);
    assertThat(
            count(
                "SELECT count(*) FROM notification WHERE id = ? AND read_at IS NULL",
                bNotification))
        .isEqualTo(1);
  }

  @Test
  void removingAForeignSavedProductIsIdempotentAndLeavesTheOwnerUntouched() {
    List<Map<String, Object>> before = rowsOf(b);
    for (String id : hostileIds(products.get(0), UUID.randomUUID())) {
      Resp removed = send("DELETE", API + "/me/saved-products/" + id + "?country=TR", tokenA);
      assertThat(removed.status()).as(removed.toString()).isEqualTo(204);
      assertThat(removed.body()).isEmpty();
    }
    assertThat(rowsOf(b)).as("B's saved row and active alert stay").isEqualTo(before);
  }

  @Test
  void listsAndCountersNeverShowOrChangeAnotherUsersRows() {
    for (String path :
        List.of(
            "/me/alerts",
            "/me/saved-products?country=TR",
            "/me/notifications?pageSize=100",
            "/me/notifications?unreadOnly=true&pageSize=100")) {
      Resp list = send("GET", API + path, tokenA);
      assertThat(list.status()).as(list.toString()).isEqualTo(200);
      assertThat(list.body())
          .as(path)
          .doesNotContain(bPriceDrop.toString())
          .doesNotContain(bBackInStock.toString())
          .doesNotContain(bNotification.toString())
          .doesNotContain(b.id().toString())
          .doesNotContain(products.get(0).toString());
      AttackClient.assertApiSecurityHeaders(list);
    }
    JsonNode alerts = send("GET", API + "/me/alerts", tokenA).json().get("items");
    assertThat(alerts.size()).isEqualTo(1);

    assertThat(unread(tokenA)).isEqualTo(1);
    assertThat(unread(tokenB)).isEqualTo(1);
    assertThat(send("POST", API + "/me/notifications/read-all", tokenA).status()).isEqualTo(204);
    assertThat(unread(tokenA)).isZero();
    assertThat(unread(tokenB)).as("read-all of A must not touch B").isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM notification WHERE user_id = ? AND read_at IS NULL", b.id()))
        .isEqualTo(1);
  }

  private long unread(String token) {
    Resp count = send("GET", API + "/me/notifications/unread-count", token);
    assertThat(count.status()).as(count.toString()).isEqualTo(200);
    assertThat(count.json().size()).isEqualTo(1);
    return count.json().get("unreadCount").asLong();
  }

  @Test
  void theProductDetailNeverShowsAnotherUsersSavedStateOrAlerts() {
    Resp detail = send("GET", API + "/products/" + products.get(0) + "?country=TR", tokenA);
    assertThat(detail.status()).as(detail.toString()).isEqualTo(200);
    assertThat(detail.json().get("product").get("saved").asBoolean()).isFalse();
    assertThat(detail.json().get("product").get("alerts").toString()).isEqualTo("[]");
    assertThat(detail.body()).doesNotContain(bPriceDrop.toString());
    Resp owner = send("GET", API + "/products/" + products.get(0) + "?country=TR", tokenB);
    assertThat(owner.json().get("product").get("saved").asBoolean()).isTrue();
    assertThat(owner.body()).contains(bPriceDrop.toString());
  }

  @Test
  void productsThatAreNotInMarketCannotBeSavedOrAlerted() {
    UUID notSold = syntheticProduct("NOT_SOLD", "100.00");
    UUID discontinued = syntheticProduct("DISCONTINUED", "100.00");
    UUID noRow = syntheticProduct(null, "100.00");
    for (UUID product : List.of(notSold, discontinued, noRow)) {
      AttackClient.assertProblem(
          saveProduct(tokenA, product, "TR"), 404, "product_not_available_in_country");
      for (String trigger : List.of("PRICE_DROP", "BACK_IN_STOCK", "PRICE_CHANGE")) {
        AttackClient.assertProblem(
            createAlert(tokenA, product, "TR", trigger), 404, "product_not_available_in_country");
      }
    }
    UUID available = syntheticProduct("AVAILABLE", "100.00");
    AttackClient.assertProblem(
        saveProduct(tokenA, available, "DE"), 404, "product_not_available_in_country");
    AttackClient.assertProblem(
        saveProduct(tokenA, UUID.randomUUID(), "TR"), 404, "product_not_found");
    AttackClient.assertProblem(saveProduct(tokenA, available, "XQ"), 422, "country_not_found");
    for (String country : List.of("tr", "TUR", "T", "' OR 1=1", "")) {
      Resp invalid = saveProduct(tokenA, available, country);
      AttackClient.assertProblem(invalid, 422, "validation_failed");
    }
    assertThat(
            count(
                "SELECT count(*) FROM saved_product WHERE user_id = ? AND product_id IN (?, ?, ?)",
                a.id(),
                notSold,
                discontinued,
                noRow))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM alert_subscription WHERE user_id = ? AND product_id IN"
                    + " (?, ?, ?)",
                a.id(),
                notSold,
                discontinued,
                noRow))
        .isZero();
  }

  @Test
  void anAlertCannotBeReactivatedAfterItsProductLeftTheMarket() {
    UUID product = syntheticProduct("AVAILABLE", "100.00");
    UUID alert = alertId(createAlert(tokenA, product, "TR", "PRICE_CHANGE"));
    assertThat(patchAlert(tokenA, alert.toString(), "{\"active\":false}").status()).isEqualTo(200);
    setAvailability(product, "NOT_SOLD");
    AttackClient.assertProblem(
        patchAlert(tokenA, alert.toString(), "{\"active\":true}"),
        404,
        "product_not_available_in_country");
    assertThat(count("SELECT count(*) FROM alert_subscription WHERE id = ? AND active", alert))
        .isZero();
    Resp foreign = patchAlert(tokenB, alert.toString(), "{\"active\":true}");
    assertBlankNotFound(foreign);
  }
}
