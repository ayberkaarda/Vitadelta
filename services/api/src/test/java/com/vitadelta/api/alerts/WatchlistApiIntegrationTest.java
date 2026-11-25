package com.vitadelta.api.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.identity.ContractSchemas;
import com.vitadelta.api.identity.UserStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code /me/saved-products} and {@code /me/alerts} through the real endpoints (sections 8.4 to
 * 8.6): contract shapes, the order of checks, idempotency, plan limits under concurrency, the
 * baseline rules and caller scoping of every {@code {id}} route.
 */
class WatchlistApiIntegrationTest extends AlertsIntegrationTest {

  private static final String SAVED = "/api/v1/me/saved-products";
  private static final String ALERTS = "/api/v1/me/alerts";

  private List<UUID> availableInTr(int count) {
    return jdbc.sql(
            "SELECT product_id FROM product_availability WHERE country_code = 'TR'"
                + " AND status = 'AVAILABLE' ORDER BY product_id LIMIT ?")
        .param(count)
        .query(UUID.class)
        .list();
  }

  private EntityExchangeResult<String> save(String token, UUID product, String country) {
    return post(
        SAVED, body(Map.of("productId", product.toString(), "countryCode", country)), token);
  }

  private EntityExchangeResult<String> createAlert(
      String token, UUID product, String trigger, Object threshold) {
    Map<String, Object> fields = new java.util.LinkedHashMap<>();
    fields.put("productId", product.toString());
    fields.put("countryCode", "TR");
    fields.put("trigger", trigger);
    if (threshold != null) {
      fields.put("thresholdPercent", threshold);
    }
    return post(ALERTS, body(fields), token);
  }

  private long count(String sql, UUID user) {
    return jdbc.sql(sql).param(user).query(Long.class).single();
  }

  private long saved(TestUser user) {
    return count("SELECT count(*) FROM saved_product WHERE user_id = ?", user.id());
  }

  private long alerts(TestUser user) {
    return count("SELECT count(*) FROM alert_subscription WHERE user_id = ?", user.id());
  }

  @Test
  void savingIsIdempotentAndFollowsTheOrderOfChecks() {
    TestUser user = user();
    String token = token(user);
    UUID gold = productId("GOLD-A");
    EntityExchangeResult<String> created = save(token, gold, "TR");
    assertStatus(created, 201);
    ContractSchemas.assertConforms("SavedProductRef", created.getResponseBody());
    EntityExchangeResult<String> again = save(token, gold, "TR");
    assertStatus(again, 200);
    assertThat(json(again).get("savedAt")).isEqualTo(json(created).get("savedAt"));
    assertThat(saved(user)).isEqualTo(1);

    assertProblem(save(null, gold, "TR"), 401, "unauthorized");
    EntityExchangeResult<String> invalid = post(SAVED, body(Map.of("productId", "x")), token);
    assertProblem(invalid, 422, "validation_failed");
    assertThat(errorCodes(invalid, "productId")).containsExactly("invalid_format");
    assertThat(errorCodes(invalid, "countryCode")).containsExactly("required");
    assertProblem(post(SAVED, "[1]", token), 422, "validation_failed");
    assertProblem(
        post(SAVED, body(Map.of("productId", gold.toString(), "countryCode", "tr")), token),
        422,
        "validation_failed");
    assertProblem(save(token, gold, "XX"), 422, "country_not_found");
    assertProblem(save(token, UUID.randomUUID(), "TR"), 404, "product_not_found");
    assertProblem(save(token, gold, "US"), 404, "product_not_available_in_country");
    EntityExchangeResult<String> text =
        client
            .post()
            .uri(SAVED)
            .contentType(org.springframework.http.MediaType.TEXT_PLAIN)
            .header("Authorization", "Bearer " + token)
            .body("x")
            .exchange()
            .returnResult(String.class);
    assertStatus(text, 415);
    assertThat(saved(user)).isEqualTo(1);
  }

  @Test
  void tenParallelSavesWithFourSavedGiveExactlyOneSuccess() throws Exception {
    TestUser user = user();
    String token = token(user);
    List<UUID> products = availableInTr(14);
    for (int i = 0; i < 4; i++) {
      assertStatus(save(token, products.get(i), "TR"), 201);
    }
    List<Callable<EntityExchangeResult<String>>> tasks = new ArrayList<>();
    for (int i = 4; i < 14; i++) {
      UUID product = products.get(i);
      tasks.add(() -> save(token, product, "TR"));
    }
    List<EntityExchangeResult<String>> results = parallel(tasks);
    assertThat(results.stream().filter(r -> r.getStatus().value() == 201)).hasSize(1);
    List<EntityExchangeResult<String>> rejected =
        results.stream().filter(r -> r.getStatus().value() == 403).toList();
    assertThat(rejected).hasSize(9);
    rejected.forEach(r -> assertProblem(r, 403, "entitlement_exceeded"));
    JsonNode problem = json(rejected.getFirst());
    assertThat(problem.get("limit").asInt()).isEqualTo(5);
    assertThat(problem.get("upgradeHint").get("messageKey").asString())
        .isEqualTo("limits.saved_products_reached");
    assertThat(saved(user)).isEqualTo(5);
  }

  @Test
  void theSavedListShowsPricesFlagsActiveAlertsAndUnavailableProducts() {
    TestUser user = user();
    String token = token(user);
    UUID goldB = productId("GOLD-B");
    UUID gummy = productId("BH-FAMILY-GUMMY");
    assertStatus(createAlert(token, goldB, "PRICE_DROP", null), 201);
    UUID inactive =
        UUID.fromString(json(createAlert(token, goldB, "PRICE_CHANGE", null)).get("id").asString());
    assertStatus(patch(ALERTS + "/" + inactive, "{\"active\":false}", token), 200);
    clock.advance(java.time.Duration.ofSeconds(1));
    assertStatus(save(token, gummy, "TR"), 201);
    // A saved product that is no longer sold keeps its row and is shown without price.
    UUID goldA = productId("GOLD-A");
    jdbc.sql(
            "INSERT INTO saved_product (user_id, product_id, country_code, created_at)"
                + " VALUES (?, ?, 'US', now())")
        .params(user.id(), goldA)
        .update();

    EntityExchangeResult<String> list = get(SAVED + "?country=TR", token);
    assertStatus(list, 200);
    ContractSchemas.assertConforms("SavedProductList", list.getResponseBody());
    JsonNode body = json(list);
    assertThat(body.get("meta").get("country").asString()).isEqualTo("TR");
    assertThat(body.get("meta").get("nativeCurrency").asString()).isEqualTo("TRY");
    assertThat(body.get("items")).hasSize(2);
    JsonNode first = body.get("items").get(0);
    assertThat(first.get("productId").asString()).isEqualTo(gummy.toString());
    assertThat(first.get("cost").get("outOfStock").asBoolean()).isTrue();
    List<String> flags = new ArrayList<>();
    first.get("flags").forEach(f -> flags.add(f.get("code").asString()));
    assertThat(flags).contains("OUT_OF_STOCK");
    JsonNode second = body.get("items").get(1);
    assertThat(second.get("cost").get("effectivePriceNative").decimalValue())
        .isEqualByComparingTo("900");
    assertThat(second.get("selectedOffer").get("retailer").asString())
        .isEqualTo("Fixture Pharmacy");
    assertThat(second.get("alerts")).hasSize(1);
    assertThat(second.get("alerts").get(0).get("trigger").asString()).isEqualTo("PRICE_DROP");

    EntityExchangeResult<String> us = get(SAVED + "?country=US", token);
    assertStatus(us, 200);
    ContractSchemas.assertConforms("SavedProductList", us.getResponseBody());
    JsonNode unavailable = json(us).get("items").get(0);
    assertThat(unavailable.get("availabilityStatus").asString()).isIn("NOT_SOLD", "DISCONTINUED");
    assertThat(unavailable.get("cost").isNull()).isTrue();
    assertThat(unavailable.get("selectedOffer").isNull()).isTrue();
    assertThat(unavailable.get("flags").toString())
        .isEqualTo("[{\"code\":\"UNAVAILABLE_IN_COUNTRY\",\"nutrientCodes\":[]}]");

    assertProblem(get(SAVED, token), 422, "validation_failed");
    assertProblem(get(SAVED + "?country=TR&displayCurrency=usd", token), 422, "validation_failed");
    assertProblem(get(SAVED + "?country=XX", token), 422, "country_not_found");
    assertProblem(get(SAVED + "?country=TR", null), 401, "unauthorized");
    assertStatus(get(SAVED + "?country=DE", token), 200);
    assertThat(json(get(SAVED + "?country=DE", token)).get("items")).isEmpty();
  }

  @Test
  void removingASavedProductDeactivatesItsAlertsAndIsAlways204() {
    TestUser user = user();
    String token = token(user);
    UUID goldB = productId("GOLD-B");
    UUID alert =
        UUID.fromString(json(createAlert(token, goldB, "PRICE_DROP", 7.5)).get("id").asString());
    assertStatus(delete(SAVED + "/" + goldB + "?country=TR", token), 204);
    assertThat(saved(user)).isZero();
    Map<String, Object> row = subscriptionRow(alert);
    assertThat(row.get("active")).isEqualTo(false);
    assertThat(row.get("baseline_price")).isEqualTo(new BigDecimal("900.00"));
    assertStatus(delete(SAVED + "/" + goldB + "?country=TR", token), 204);
    assertStatus(delete(SAVED + "/not-a-uuid?country=TR", token), 204);
    assertProblem(delete(SAVED + "/" + goldB, token), 422, "validation_failed");
    assertProblem(delete(SAVED + "/" + goldB + "?country=XX", token), 422, "country_not_found");

    // Reactivation re-creates the implicit saved row and resets the baseline.
    jdbc.sql("UPDATE alert_subscription SET baseline_price = 1.00 WHERE id = ?")
        .param(alert)
        .update();
    EntityExchangeResult<String> reactivated =
        patch(ALERTS + "/" + alert, "{\"active\":true}", token);
    assertStatus(reactivated, 200);
    ContractSchemas.assertConforms("AlertSubscription", reactivated.getResponseBody());
    assertThat(json(reactivated).get("baselinePrice").decimalValue()).isEqualByComparingTo("900");
    assertThat(saved(user)).isEqualTo(1);
  }

  @Test
  void creatingAnAlertSavesTheProductStoresTheBaselineAndReturnsDuplicates() {
    TestUser user = user();
    String token = token(user);
    UUID goldB = productId("GOLD-B");
    EntityExchangeResult<String> created = createAlert(token, goldB, "PRICE_DROP", null);
    assertStatus(created, 201);
    ContractSchemas.assertConforms("AlertSubscription", created.getResponseBody());
    JsonNode alert = json(created);
    assertThat(alert.get("thresholdPercent").decimalValue()).isEqualByComparingTo("5");
    assertThat(alert.get("baselinePrice").decimalValue()).isEqualByComparingTo("900");
    assertThat(alert.get("baselineCurrency").asString()).isEqualTo("TRY");
    assertThat(alert.get("active").asBoolean()).isTrue();
    assertThat(saved(user)).isEqualTo(1);

    EntityExchangeResult<String> duplicate = createAlert(token, goldB, "PRICE_DROP", 20);
    assertStatus(duplicate, 200);
    assertThat(json(duplicate)).isEqualTo(alert);

    EntityExchangeResult<String> stock = createAlert(token, goldB, "BACK_IN_STOCK", null);
    assertStatus(stock, 201);
    assertThat(json(stock).get("thresholdPercent").isNull()).isTrue();
    assertThat(json(stock).get("baselinePrice").isNull()).isTrue();

    EntityExchangeResult<String> list = get(ALERTS, token);
    assertStatus(list, 200);
    ContractSchemas.assertConforms("AlertList", list.getResponseBody());
    JsonNode items = json(list).get("items");
    assertThat(items).hasSize(2);
    assertThat(items.get(0).get("product").get("code").asString()).isEqualTo("GOLD-B");
    assertThat(items.get(0).get("product").get("availabilityStatus").asString())
        .isEqualTo("AVAILABLE");

    // The watch state shows on the product detail for the signed-in caller only.
    EntityExchangeResult<String> detail = get("/api/v1/products/" + goldB + "?country=TR", token);
    assertStatus(detail, 200);
    JsonNode product = json(detail).get("product");
    assertThat(product.get("saved").asBoolean()).isTrue();
    assertThat(product.get("alerts")).hasSize(2);
    assertThat(product.get("alerts").get(0).get("trigger").asString()).isEqualTo("PRICE_DROP");
    assertThat(product.get("alerts").get(1).get("trigger").asString()).isEqualTo("BACK_IN_STOCK");
    JsonNode anonymous =
        json(get("/api/v1/products/" + goldB + "?country=TR", null)).get("product");
    assertThat(anonymous.get("saved").asBoolean()).isFalse();
    assertThat(anonymous.get("alerts")).isEmpty();
  }

  @Test
  void alertValidationRunsInTheContractOrderAndWritesNothing() {
    TestUser user = user();
    String token = token(user);
    UUID goldB = productId("GOLD-B");
    EntityExchangeResult<String> notAllowed = createAlert(token, goldB, "BACK_IN_STOCK", 5);
    assertProblem(notAllowed, 422, "validation_failed");
    assertThat(errorCodes(notAllowed, "thresholdPercent")).containsExactly("threshold_not_allowed");
    for (Object bad : List.of(0, 100, 5.123, -1, "5")) {
      EntityExchangeResult<String> result = createAlert(token, goldB, "PRICE_DROP", bad);
      assertProblem(result, 422, "validation_failed");
      assertThat(errorCodes(result, "thresholdPercent")).isNotEmpty();
    }
    assertThat(errorCodes(createAlert(token, goldB, "PRICE_DROP", 0), "thresholdPercent"))
        .containsExactly("out_of_range");
    EntityExchangeResult<String> missing = post(ALERTS, "{}", token);
    assertProblem(missing, 422, "validation_failed");
    assertThat(errorCodes(missing, "trigger")).containsExactly("required");
    assertProblem(createAlert(token, goldB, "SOMETIMES", null), 422, "validation_failed");
    assertProblem(
        createAlert(token, UUID.randomUUID(), "PRICE_DROP", null), 404, "product_not_found");
    assertProblem(
        post(
            ALERTS,
            body(
                Map.of(
                    "productId", goldB.toString(), "countryCode", "XX", "trigger", "PRICE_DROP")),
            token),
        422,
        "country_not_found");
    assertProblem(
        post(
            ALERTS,
            body(
                Map.of(
                    "productId", goldB.toString(), "countryCode", "US", "trigger", "PRICE_DROP")),
            token),
        404,
        "product_not_available_in_country");

    TestUser unverified = user(false, true, UserStatus.ACTIVE, "en");
    assertProblem(
        createAlert(token(unverified), goldB, "PRICE_DROP", null), 403, "email_not_verified");
    assertThat(alerts(user)).isZero();
    assertThat(saved(user)).isZero();
    assertThat(alerts(unverified)).isZero();
    assertThat(saved(unverified)).isZero();
  }

  @Test
  void theAlertAndTheImplicitSavedLimitsAreEnforced() {
    TestUser user = user();
    String token = token(user);
    List<UUID> products = availableInTr(6);
    for (int i = 0; i < 3; i++) {
      assertStatus(createAlert(token, products.get(i), "PRICE_CHANGE", null), 201);
    }
    EntityExchangeResult<String> fourth = createAlert(token, products.get(3), "PRICE_CHANGE", null);
    assertProblem(fourth, 403, "entitlement_exceeded");
    assertThat(json(fourth).get("upgradeHint").get("messageKey").asString())
        .isEqualTo("limits.alert_subscriptions_reached");
    assertThat(saved(user)).isEqualTo(3);

    // Free alert slot, but the saved limit is reached: the implicit saved row is refused.
    jdbc.sql("UPDATE alert_subscription SET active = false WHERE user_id = ?")
        .param(user.id())
        .update();
    assertStatus(save(token, products.get(3), "TR"), 201);
    assertStatus(save(token, products.get(4), "TR"), 201);
    EntityExchangeResult<String> implicit =
        createAlert(token, products.get(5), "PRICE_CHANGE", null);
    assertProblem(implicit, 403, "entitlement_exceeded");
    assertThat(json(implicit).get("upgradeHint").get("messageKey").asString())
        .isEqualTo("limits.saved_products_reached");
    assertThat(alerts(user)).isEqualTo(3);
    assertThat(saved(user)).isEqualTo(5);
  }

  @Test
  void parallelActivationsNeverExceedTheAlertLimit() throws Exception {
    TestUser user = user();
    String token = token(user);
    List<UUID> products = availableInTr(6);
    List<UUID> ids = new ArrayList<>();
    for (UUID product : products.subList(0, 5)) {
      UUID id =
          UUID.fromString(
              json(createAlert(token, product, "BACK_IN_STOCK", null)).get("id").asString());
      ids.add(id);
      if (ids.size() < 5) {
        jdbc.sql("UPDATE alert_subscription SET active = false WHERE id = ?").param(id).update();
      }
    }
    // One active (the last), four inactive: four parallel activations, two may succeed.
    List<Callable<EntityExchangeResult<String>>> tasks = new ArrayList<>();
    for (UUID id : ids.subList(0, 4)) {
      tasks.add(() -> patch(ALERTS + "/" + id, "{\"active\":true}", token));
    }
    List<EntityExchangeResult<String>> results = parallel(tasks);
    assertThat(results.stream().filter(r -> r.getStatus().value() == 200)).hasSize(2);
    assertThat(results.stream().filter(r -> r.getStatus().value() == 403)).hasSize(2);
    assertThat(
            count(
                "SELECT count(*) FROM alert_subscription WHERE user_id = ? AND active", user.id()))
        .isEqualTo(3);
  }

  @Test
  void patchAppliesTheReactivationAndThresholdRules() {
    TestUser user = user();
    String token = token(user);
    UUID goldB = productId("GOLD-B");
    UUID id =
        UUID.fromString(json(createAlert(token, goldB, "PRICE_DROP", 5)).get("id").asString());
    String path = ALERTS + "/" + id;
    jdbc.sql("UPDATE alert_subscription SET baseline_price = 1000.00 WHERE id = ?")
        .param(id)
        .update();

    EntityExchangeResult<String> same = patch(path, "{\"thresholdPercent\":5.00}", token);
    assertStatus(same, 200);
    assertThat(json(same).get("baselinePrice").decimalValue()).isEqualByComparingTo("1000");
    EntityExchangeResult<String> noop = patch(path, "{\"active\":true}", token);
    assertThat(json(noop).get("baselinePrice").decimalValue()).isEqualByComparingTo("1000");

    EntityExchangeResult<String> changed = patch(path, "{\"thresholdPercent\":7.5}", token);
    assertStatus(changed, 200);
    ContractSchemas.assertConforms("AlertSubscription", changed.getResponseBody());
    assertThat(json(changed).get("thresholdPercent").decimalValue()).isEqualByComparingTo("7.5");
    assertThat(json(changed).get("baselinePrice").decimalValue()).isEqualByComparingTo("900");

    EntityExchangeResult<String> off = patch(path, "{\"active\":false}", token);
    assertThat(json(off).get("active").asBoolean()).isFalse();
    assertThat(saved(user)).isEqualTo(1);

    for (String bad :
        List.of(
            "{}",
            "{\"active\":null}",
            "{\"thresholdPercent\":null}",
            "{\"active\":\"yes\"}",
            "{\"thresholdPercent\":120}",
            "[]")) {
      assertProblem(patch(path, bad, token), 422, "validation_failed");
    }
    UUID stock =
        UUID.fromString(
            json(createAlert(token, goldB, "BACK_IN_STOCK", null)).get("id").asString());
    EntityExchangeResult<String> notAllowed =
        patch(ALERTS + "/" + stock, "{\"thresholdPercent\":5}", token);
    assertProblem(notAllowed, 422, "validation_failed");
    assertThat(errorCodes(notAllowed, "thresholdPercent")).containsExactly("threshold_not_allowed");

    // Activation of a product that left the market.
    jdbc.sql(
            "UPDATE product_availability SET status = 'DISCONTINUED' WHERE product_id = ?"
                + " AND country_code = 'TR'")
        .param(goldB)
        .update();
    try {
      assertProblem(
          patch(path, "{\"active\":true}", token), 404, "product_not_available_in_country");
      EntityExchangeResult<String> list = get(SAVED + "?country=TR", token);
      assertThat(json(list).get("items").get(0).get("flags").get(0).get("code").asString())
          .isEqualTo("UNAVAILABLE_IN_COUNTRY");
      assertThat(
              json(get(ALERTS, token))
                  .get("items")
                  .get(0)
                  .get("product")
                  .get("availabilityStatus")
                  .asString())
          .isEqualTo("DISCONTINUED");
    } finally {
      jdbc.sql(
              "UPDATE product_availability SET status = 'AVAILABLE' WHERE product_id = ?"
                  + " AND country_code = 'TR'")
          .param(goldB)
          .update();
    }
    assertStatus(delete(path, token), 204);
    assertProblem(delete(path, token), 404, "about:blank");
  }

  private static JsonNode withoutRequest(EntityExchangeResult<String> result) {
    ObjectNode node = (ObjectNode) json(result);
    node.remove("instance");
    node.remove("requestId");
    return node;
  }

  @Test
  void everyIdRouteAnswersTheSame404ForUnknownForeignAndMalformedIds() {
    TestUser owner = user();
    TestUser intruder = user();
    String ownerToken = token(owner);
    String intruderToken = token(intruder);
    UUID goldB = productId("GOLD-B");
    UUID alert =
        UUID.fromString(
            json(createAlert(ownerToken, goldB, "PRICE_DROP", null)).get("id").asString());
    UUID notification = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO notification (id, user_id, type, payload, created_at)"
                + " VALUES (?, ?, 'PLAN_EXPIRED', '{\"expiredPlanCode\":\"PRO\",\"planCode\":\"FREE\","
                + "\"deactivatedAlertSubscriptions\":0}'::jsonb, now())")
        .params(notification, owner.id())
        .update();
    Map<String, Object> before = subscriptionRow(alert);

    List<EntityExchangeResult<String>> answers = new ArrayList<>();
    for (String id : List.of(alert.toString(), UUID.randomUUID().toString(), "not-a-uuid")) {
      answers.add(patch(ALERTS + "/" + id, "{\"active\":false}", intruderToken));
      answers.add(delete(ALERTS + "/" + id, intruderToken));
    }
    for (String id : List.of(notification.toString(), UUID.randomUUID().toString(), "x")) {
      answers.add(postEmpty("/api/v1/me/notifications/" + id + "/read", intruderToken));
    }
    JsonNode reference = withoutRequest(answers.getFirst());
    for (EntityExchangeResult<String> answer : answers) {
      assertProblem(answer, 404, "about:blank");
      assertThat(withoutRequest(answer)).isEqualTo(reference);
    }
    assertThat(subscriptionRow(alert)).isEqualTo(before);
    assertThat(notifications(owner).getFirst().get("read_at")).isNull();
    assertThat(json(get(ALERTS, intruderToken)).get("items")).isEmpty();
  }
}
