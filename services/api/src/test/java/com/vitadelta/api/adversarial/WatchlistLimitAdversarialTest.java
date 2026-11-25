package com.vitadelta.api.adversarial;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.adversarial.AttackClient.Resp;
import com.vitadelta.api.identity.ContractSchemas;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Section 15.7 case 16 through the endpoints, plus the plan-limit bypasses a FREE user would try
 * (section 8.4, contract {@code saveProduct}, {@code createAlert}, {@code updateAlert}): parallel
 * saves and alert creations, the implicit saved row of an alert at the saved limit, parallel
 * re-activations, a downgrade from PRO with more rows than FREE allows, delete-and-recreate churn,
 * duplicate requests, threshold bounds and an unverified e-mail.
 */
class WatchlistLimitAdversarialTest extends AlertsAdversarialTest {

  private static final int FREE_SAVED = 5;
  private static final int FREE_ALERTS = 3;

  private List<UUID> synthetic(int count) {
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      ids.add(syntheticProduct("AVAILABLE", "100.00"));
    }
    return ids;
  }

  private long saved(TestUser user) {
    return count("SELECT count(*) FROM saved_product WHERE user_id = ?", user.id());
  }

  private long activeAlerts(TestUser user) {
    return count("SELECT count(*) FROM alert_subscription WHERE user_id = ? AND active", user.id());
  }

  private long allAlerts(TestUser user) {
    return count("SELECT count(*) FROM alert_subscription WHERE user_id = ?", user.id());
  }

  private static void assertExceeded(Resp response, String messageKey) {
    AttackClient.assertNoLeak(response);
    assertThat(response.status()).as(response.toString()).isEqualTo(403);
    assertThat(response.json().get("type").asString())
        .isEqualTo("https://vitadelta.dev/problems/entitlement_exceeded");
    ContractSchemas.assertConforms("EntitlementExceededProblem", response.body());
    assertThat(response.json().get("upgradeHint").get("messageKey").asString())
        .isEqualTo(messageKey);
    assertThat(response.json().get("plan").asString()).isEqualTo("FREE");
  }

  private static List<Integer> statuses(List<Resp> responses) {
    return responses.stream().map(Resp::status).toList();
  }

  @Test
  void twentyParallelSavesStopAtTheFreeLimit() throws Exception {
    TestUser user = user();
    String token = token(user);
    List<UUID> products = synthetic(20);
    List<Callable<Resp>> tasks = new ArrayList<>();
    for (UUID product : products) {
      tasks.add(() -> saveProduct(token, product, "TR"));
    }
    List<Resp> results = parallel(tasks);
    assertThat(statuses(results)).containsOnly(201, 403);
    assertThat(statuses(results).stream().filter(s -> s == 201)).hasSize(FREE_SAVED);
    results.stream()
        .filter(r -> r.status() == 403)
        .forEach(r -> assertExceeded(r, "limits.saved_products_reached"));
    assertThat(saved(user)).isEqualTo(FREE_SAVED);
  }

  @Test
  void twentyParallelAlertCreationsStopAtTheActiveAlertLimit() throws Exception {
    TestUser user = user();
    String token = token(user);
    List<UUID> products = synthetic(20);
    List<Callable<Resp>> tasks = new ArrayList<>();
    for (int i = 0; i < products.size(); i++) {
      UUID product = products.get(i);
      String trigger = List.of("PRICE_DROP", "BACK_IN_STOCK", "PRICE_CHANGE").get(i % 3);
      tasks.add(() -> createAlert(token, product, "TR", trigger));
    }
    List<Resp> results = parallel(tasks);
    assertThat(statuses(results)).containsOnly(201, 403);
    assertThat(statuses(results).stream().filter(s -> s == 201)).hasSize(FREE_ALERTS);
    results.stream()
        .filter(r -> r.status() == 403)
        .forEach(r -> assertExceeded(r, "limits.alert_subscriptions_reached"));
    assertThat(activeAlerts(user)).isEqualTo(FREE_ALERTS);
    assertThat(saved(user)).as("one implicit saved row per created alert").isEqualTo(FREE_ALERTS);
  }

  @Test
  void theImplicitSavedRowOfAnAlertCannotPassTheSavedLimit() throws Exception {
    TestUser user = user();
    String token = token(user);
    List<UUID> products = synthetic(FREE_SAVED + 1);
    for (int i = 0; i < FREE_SAVED; i++) {
      assertThat(saveProduct(token, products.get(i), "TR").status()).isEqualTo(201);
    }
    UUID sixth = products.get(FREE_SAVED);
    List<Callable<Resp>> tasks = new ArrayList<>();
    for (String trigger : List.of("PRICE_DROP", "BACK_IN_STOCK", "PRICE_CHANGE")) {
      tasks.add(() -> createAlert(token, sixth, "TR", trigger));
      tasks.add(() -> createAlert(token, sixth, "TR", trigger));
    }
    for (Resp r : parallel(tasks)) {
      assertExceeded(r, "limits.saved_products_reached");
    }
    assertThat(saved(user)).isEqualTo(FREE_SAVED);
    assertThat(allAlerts(user)).as("a failed check writes nothing").isZero();
    Resp onSaved = createAlert(token, products.get(0), "TR", "PRICE_CHANGE");
    assertThat(onSaved.status()).as("an alert on a saved product is fine").isEqualTo(201);
  }

  @Test
  void parallelReactivationsNeverExceedTheActiveAlertLimit() throws Exception {
    TestUser user = user();
    String token = token(user);
    List<UUID> products = synthetic(FREE_SAVED);
    List<UUID> alerts = new ArrayList<>();
    for (UUID product : products) {
      for (String trigger : List.of("PRICE_CHANGE", "BACK_IN_STOCK")) {
        alerts.add(
            subscription(
                user,
                product,
                "TR",
                com.vitadelta.api.alerts.AlertTrigger.valueOf(trigger),
                null,
                null));
      }
    }
    jdbc.sql("UPDATE alert_subscription SET active = false WHERE user_id = ?")
        .param(user.id())
        .update();
    jdbc.sql("UPDATE alert_subscription SET active = true WHERE id IN (?, ?)")
        .params(alerts.get(0), alerts.get(1))
        .update();
    List<Callable<Resp>> tasks = new ArrayList<>();
    for (UUID alert : alerts.subList(2, alerts.size())) {
      for (int i = 0; i < 3; i++) {
        tasks.add(() -> patchAlert(token, alert.toString(), "{\"active\":true}"));
      }
    }
    List<Resp> results = parallel(tasks);
    assertThat(statuses(results)).containsOnly(200, 403);
    assertThat(activeAlerts(user)).isEqualTo(FREE_ALERTS);
    Set<String> activated = new HashSet<>();
    results.stream()
        .filter(r -> r.status() == 200)
        .forEach(r -> activated.add(r.json().get("id").asString()));
    assertThat(activated).as("only one subscription could be activated").hasSize(1);
  }

  @Test
  void aDowngradeKeepsExtraRowsButAllowsNothingNew() {
    TestUser user = user();
    String token = token(user);
    makePro(user);
    List<UUID> products = synthetic(8);
    List<UUID> alerts = new ArrayList<>();
    for (UUID product : products) {
      assertThat(saveProduct(token, product, "TR").status()).isEqualTo(201);
    }
    for (UUID product : products.subList(0, 6)) {
      alerts.add(alertId(createAlert(token, product, "TR", "PRICE_DROP")));
    }
    downgradeToFree(user);

    assertExceeded(
        saveProduct(token, syntheticProduct("AVAILABLE", "100.00"), "TR"),
        "limits.saved_products_reached");
    assertThat(saveProduct(token, products.get(0), "TR").status())
        .as("an existing saved row stays idempotent")
        .isEqualTo(200);
    assertExceeded(
        createAlert(token, products.get(7), "TR", "PRICE_CHANGE"),
        "limits.alert_subscriptions_reached");
    Resp deactivated = patchAlert(token, alerts.get(0).toString(), "{\"active\":false}");
    assertThat(deactivated.status()).isEqualTo(200);
    assertExceeded(
        patchAlert(token, alerts.get(0).toString(), "{\"active\":true}"),
        "limits.alert_subscriptions_reached");
    Resp duplicate = createAlert(token, products.get(0), "TR", "PRICE_DROP");
    assertThat(duplicate.status()).isEqualTo(200);
    assertThat(duplicate.json().get("active").asBoolean())
        .as("a duplicate POST never re-activates")
        .isFalse();
    assertThat(patchAlert(token, alerts.get(1).toString(), "{\"thresholdPercent\":7.5}").status())
        .as("changing an active threshold is not an activation")
        .isEqualTo(200);
    assertThat(saved(user)).isEqualTo(8);
    assertThat(allAlerts(user)).isEqualTo(6);
    assertThat(activeAlerts(user)).isEqualTo(5);
  }

  @Test
  void deleteAndRecreateChurnNeverExceedsTheLimit() throws Exception {
    TestUser user = user();
    String token = token(user);
    List<UUID> products = synthetic(FREE_SAVED);
    for (int i = 0; i < FREE_ALERTS; i++) {
      alertId(createAlert(token, products.get(i), "TR", "PRICE_CHANGE"));
    }
    String[] triggers = {"PRICE_DROP", "BACK_IN_STOCK", "PRICE_CHANGE"};
    for (int round = 0; round < 5; round++) {
      UUID victim =
          jdbc.sql(
                  "SELECT id FROM alert_subscription WHERE user_id = ? AND active"
                      + " ORDER BY created_at, id LIMIT 1")
              .param(user.id())
              .query(UUID.class)
              .single();
      List<Callable<Resp>> tasks = new ArrayList<>();
      tasks.add(() -> send("DELETE", API + "/me/alerts/" + victim, token));
      for (int i = 0; i < 6; i++) {
        UUID product = products.get(i % FREE_SAVED);
        String trigger = triggers[(i + round) % 3];
        tasks.add(() -> createAlert(token, product, "TR", trigger));
      }
      List<Resp> results = parallel(tasks);
      assertThat(results.getFirst().status()).isEqualTo(204);
      assertThat(activeAlerts(user)).as("round %d", round).isLessThanOrEqualTo(FREE_ALERTS);
      assertThat(saved(user)).isLessThanOrEqualTo(FREE_SAVED);
      long created = results.stream().filter(r -> r.status() == 201).count();
      assertThat(created)
          .as("round %d: one freed slot, at most one new alert", round)
          .isLessThanOrEqualTo(1L);
      for (Resp r : results.subList(1, results.size())) {
        assertThat(r.status()).as(r.toString()).isIn(200, 201, 403);
      }
      for (int p = 0; p < FREE_SAVED && activeAlerts(user) < FREE_ALERTS; p++) {
        for (String trigger : triggers) {
          if (activeAlerts(user) < FREE_ALERTS) {
            assertThat(createAlert(token, products.get(p), "TR", trigger).status()).isIn(200, 201);
          }
        }
      }
    }
    assertThat(activeAlerts(user)).isEqualTo(FREE_ALERTS);
  }

  @Test
  void parallelDuplicateRequestsCreateOneRow() throws Exception {
    TestUser user = user();
    String token = token(user);
    UUID product = syntheticProduct("AVAILABLE", "100.00");
    List<Callable<Resp>> saves = new ArrayList<>();
    List<Callable<Resp>> creates = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      saves.add(() -> saveProduct(token, product, "TR"));
      int threshold = i + 1;
      creates.add(() -> createPriceDrop(token, product, "TR", threshold));
    }
    List<Resp> saved = parallel(saves);
    assertThat(statuses(saved).stream().filter(s -> s == 201)).hasSize(1);
    assertThat(statuses(saved).stream().filter(s -> s == 200)).hasSize(9);
    assertThat(saved.stream().map(r -> r.json().get("savedAt").asString()).distinct()).hasSize(1);
    List<Resp> created = parallel(creates);
    assertThat(statuses(created).stream().filter(s -> s == 201)).hasSize(1);
    assertThat(statuses(created).stream().filter(s -> s == 200)).hasSize(9);
    assertThat(created.stream().map(r -> r.json().get("id").asString()).distinct()).hasSize(1);
    String winner =
        created.stream()
            .filter(r -> r.status() == 201)
            .findFirst()
            .orElseThrow()
            .json()
            .get("thresholdPercent")
            .toString();
    assertThat(created.stream().map(r -> r.json().get("thresholdPercent").toString()).distinct())
        .as("a duplicate POST never changes the threshold")
        .containsExactly(winner);
    assertThat(allAlerts(user)).isEqualTo(1);
    assertThat(saved(user)).isEqualTo(1);
  }

  @Test
  void thresholdsOutsideTheContractAreRejectedAndNeverRounded() {
    TestUser user = user();
    String token = token(user);
    makePro(user);
    UUID product = syntheticProduct("AVAILABLE", "100.00");
    String prefix =
        "{\"productId\":\""
            + product
            + "\",\"countryCode\":\"TR\",\"trigger\":\"PRICE_DROP\","
            + "\"thresholdPercent\":";
    List<String> outOfRange =
        List.of(
            "0",
            "0.00",
            "100",
            "100.00",
            "-1",
            "-0.01",
            "0.001",
            "5.555",
            "99.995",
            "1e400",
            "1e-400",
            "1E+2",
            "9999999999999999999999",
            "99.999999999999999999");
    List<String> wrongType = List.of("\"5\"", "true", "[5]", "{\"v\":5}");
    for (String value : outOfRange) {
      Resp r = sendJson("POST", API + "/me/alerts", token, prefix + value + "}");
      AttackClient.assertProblem(r, 422, "validation_failed");
      assertThat(r.body()).as(value).contains("out_of_range");
    }
    for (String value : wrongType) {
      Resp r = sendJson("POST", API + "/me/alerts", token, prefix + value + "}");
      AttackClient.assertProblem(r, 422, "validation_failed");
    }
    for (String value : List.of("NaN", "Infinity", "-Infinity", "0x10", "5.", ".5", "+5", "05")) {
      Resp r = sendJson("POST", API + "/me/alerts", token, prefix + value + "}");
      assertThat(r.status()).as(value + " -> " + r).isIn(400, 422);
      assertThat(r.header("content-type")).startsWith("application/problem+json");
      AttackClient.assertNoLeak(r);
    }
    Resp longNumber =
        sendJson("POST", API + "/me/alerts", token, prefix + "5." + "0".repeat(20000) + "1}");
    assertThat(longNumber.status()).as(longNumber.toString()).isIn(400, 422);
    AttackClient.assertNoLeak(longNumber);
    assertThat(allAlerts(user)).as("nothing stored for a rejected threshold").isZero();

    Resp valid = sendJson("POST", API + "/me/alerts", token, prefix + "1E+1}");
    assertThat(valid.status()).as(valid.toString()).isEqualTo(201);
    assertThat(valid.json().get("thresholdPercent").decimalValue()).isEqualByComparingTo("10");
    String id = valid.json().get("id").asString();
    for (String value : List.of("0", "100", "-5", "0.001", "null", "\"7\"")) {
      Resp patched = patchAlert(token, id, "{\"thresholdPercent\":" + value + "}");
      AttackClient.assertProblem(patched, 422, "validation_failed");
    }
    for (String value : List.of("0.01", "99.99")) {
      Resp patched = patchAlert(token, id, "{\"thresholdPercent\":" + value + "}");
      assertThat(patched.status()).isEqualTo(200);
      assertThat(patched.json().get("thresholdPercent").decimalValue()).isEqualByComparingTo(value);
    }
    for (String trigger : List.of("BACK_IN_STOCK", "PRICE_CHANGE")) {
      Resp r =
          sendJson(
              "POST",
              API + "/me/alerts",
              token,
              "{\"productId\":\""
                  + product
                  + "\",\"countryCode\":\"TR\",\"trigger\":\""
                  + trigger
                  + "\",\"thresholdPercent\":5}");
      AttackClient.assertProblem(r, 422, "validation_failed");
      assertThat(r.body()).contains("threshold_not_allowed");
    }
    for (String trigger : List.of("price_drop", "PRICE-DROP", "", "PRICE_DROP ", "ALL")) {
      Resp r =
          sendJson(
              "POST",
              API + "/me/alerts",
              token,
              "{\"productId\":\""
                  + product
                  + "\",\"countryCode\":\"TR\",\"trigger\":\""
                  + trigger
                  + "\"}");
      AttackClient.assertProblem(r, 422, "validation_failed");
    }
  }

  @Test
  void anUnverifiedUserCannotCreateOrActivateAlertsWhateverItsTokenClaims() {
    TestUser user = unverifiedUser();
    UUID product = syntheticProduct("AVAILABLE", "100.00");
    UUID other = syntheticProduct("AVAILABLE", "100.00");
    for (String token : List.of(unverifiedToken(user), token(user))) {
      for (String trigger : List.of("PRICE_DROP", "BACK_IN_STOCK", "PRICE_CHANGE")) {
        AttackClient.assertProblem(
            createAlert(token, product, "TR", trigger), 403, "email_not_verified");
      }
    }
    assertThat(allAlerts(user)).isZero();
    assertThat(saved(user)).as("a failed alert check writes no implicit saved row").isZero();
    assertThat(saveProduct(unverifiedToken(user), other, "TR").status())
        .as("saving does not require a verified e-mail")
        .isEqualTo(201);
    UUID inactive =
        subscription(
            user, other, "TR", com.vitadelta.api.alerts.AlertTrigger.PRICE_CHANGE, null, null);
    jdbc.sql("UPDATE alert_subscription SET active = false WHERE id = ?").param(inactive).update();
    AttackClient.assertProblem(
        patchAlert(token(user), inactive.toString(), "{\"active\":true}"),
        403,
        "email_not_verified");
    assertThat(activeAlerts(user)).isZero();
    JsonNode me = send("GET", API + "/me", token(user)).json();
    assertThat(me.toString()).doesNotContain("password");
  }
}
