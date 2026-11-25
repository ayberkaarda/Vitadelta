package com.vitadelta.api.adversarial;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.adversarial.AttackClient.Resp;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Section 15.7 case 18 and section 15.4 for the Phase 5 operations surface: the public health
 * endpoint reveals only the status, the admin operations endpoints reject every non-admin caller,
 * and every new endpoint carries the section 15.2 API headers on its success and error answers
 * without leaking a component, host name, version or stack trace.
 */
class OperationsAdversarialTest extends AlertsAdversarialTest {

  private static final List<String> FORBIDDEN_FRAGMENTS =
      List.of(
          "components",
          "scoring",
          "database",
          "outbox",
          "version",
          "java",
          "spring",
          "postgres",
          "jdbc",
          "localhost",
          "127.0.0.1",
          "Exception");

  private static String hostName() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (java.io.IOException e) {
      return "unknown-host-name-of-this-machine";
    }
  }

  @Test
  void publicHealthRevealsOnlyTheStatusWhateverTheRequestAsks() {
    String host = hostName().toLowerCase(Locale.ROOT);
    for (String path :
        List.of(
            "/health",
            "/health?details=true",
            "/health?showDetails=always&include=all",
            "/health;jsessionid=1")) {
      for (String accept : List.of("application/json", "text/html", "*/*", "application/xml")) {
        Resp health = req("GET", API + path).header("Accept", accept).send();
        if (health.status() == 406 || health.status() == 404 || health.status() == 400) {
          AttackClient.assertNoLeak(health);
          continue;
        }
        assertThat(health.status()).as(path + " " + accept + " " + health).isIn(200, 503);
        assertThat(health.json().size()).as(health.body()).isEqualTo(1);
        assertThat(health.json().get("status").asString()).isIn("UP", "DEGRADED", "DOWN");
        String lower = health.body().toLowerCase(Locale.ROOT);
        for (String fragment : FORBIDDEN_FRAGMENTS) {
          assertThat(lower).as(path).doesNotContain(fragment.toLowerCase(Locale.ROOT));
        }
        assertThat(lower).doesNotContain(host);
        AttackClient.assertApiSecurityHeaders(health);
        assertThat(health.header("server")).as("Server header").isNull();
        assertThat(health.header("x-powered-by")).isNull();
      }
    }
  }

  @Test
  void adminOperationsRejectEveryNonAdminCaller() {
    TestUser plain = user();
    TestUser pro = user();
    makePro(pro);
    TestUser unverified = unverifiedUser();
    List<String> callers =
        List.of(token(plain), token(pro), unverifiedToken(unverified), token(user()));
    record Route(String method, String path) {}
    List<Route> routes =
        List.of(
            new Route("GET", "/admin/health"),
            new Route("GET", "/admin/outbox/stats"),
            new Route("POST", "/admin/fx/refresh"),
            new Route("GET", "/admin/health/"),
            new Route("GET", "/admin/outbox/stats?pending=0"),
            new Route("GET", "/ADMIN/health"),
            new Route("GET", "/admin//health"),
            new Route("GET", "/admin/./health"),
            new Route("GET", "/me/../admin/health"),
            new Route("GET", "/admin/health%2F"));
    for (Route route : routes) {
      Resp anonymous = send(route.method(), API + route.path(), null);
      assertThat(anonymous.status()).as("anonymous " + route + " " + anonymous).isIn(400, 401, 404);
      AttackClient.assertNoLeak(anonymous);
      for (String token : callers) {
        Resp response = send(route.method(), API + route.path(), token);
        assertThat(response.status()).as(route + " " + response).isIn(400, 403, 404);
        AttackClient.assertNoLeak(response);
        assertThat(response.body()).doesNotContain("deadLetter").doesNotContain("pending\":");
      }
    }
    AttackClient.assertProblem(send("GET", API + "/admin/health", null), 401, "unauthorized");
    AttackClient.assertProblem(send("GET", API + "/admin/outbox/stats", null), 401, "unauthorized");
    AttackClient.assertProblem(send("POST", API + "/admin/fx/refresh", null), 401, "unauthorized");
    for (String token : callers) {
      AttackClient.assertProblem(send("GET", API + "/admin/health", token), 403, "forbidden");
      AttackClient.assertProblem(send("GET", API + "/admin/outbox/stats", token), 403, "forbidden");
      AttackClient.assertProblem(send("POST", API + "/admin/fx/refresh", token), 403, "forbidden");
    }
    Resp admin = send("GET", API + "/admin/health", adminToken());
    assertThat(admin.status()).isEqualTo(200);
    AttackClient.assertNoLeak(admin);
    assertThat(admin.body().toLowerCase(Locale.ROOT))
        .doesNotContain(hostName().toLowerCase(Locale.ROOT));
    assertThat(admin.body()).doesNotContain("jdbc:").doesNotContain("password");
  }

  @Test
  void everyPhaseFiveEndpointCarriesTheApiHeadersOnSuccessAndError() {
    TestUser user = user();
    String token = token(user);
    String admin = adminToken();
    UUID product = syntheticProduct("AVAILABLE", "100.00");
    String alert = alertId(createAlert(token, product, "TR", "PRICE_CHANGE")).toString();
    String oversized = "{\"productId\":\"" + "a".repeat(70 * 1024) + "\"}";
    List<Resp> responses = new ArrayList<>();
    responses.add(send("GET", API + "/me/saved-products?country=TR", token));
    responses.add(send("GET", API + "/me/saved-products?country=tr", token));
    responses.add(send("GET", API + "/me/saved-products", token));
    responses.add(send("GET", API + "/me/saved-products?country=TR", null));
    responses.add(saveProduct(token, product, "TR"));
    responses.add(
        req("POST", API + "/me/saved-products").bearer(token).body("x", "text/plain").send());
    responses.add(req("POST", API + "/me/saved-products").bearer(token).json(oversized).send());
    responses.add(
        req("POST", API + "/me/saved-products").bearer(token).json("{\"productId\":").send());
    responses.add(req("POST", API + "/me/saved-products").bearer(token).json("[]").send());
    responses.add(
        send("DELETE", API + "/me/saved-products/" + UUID.randomUUID() + "?country=TR", token));
    responses.add(send("GET", API + "/me/alerts", token));
    responses.add(send("GET", API + "/me/alerts", "not.a.token"));
    responses.add(sendJson("POST", API + "/me/alerts", token, "{}"));
    responses.add(patchAlert(token, alert, "{\"active\":\"yes\"}"));
    responses.add(patchAlert(token, UUID.randomUUID().toString(), "{\"active\":false}"));
    responses.add(
        req("PATCH", API + "/me/alerts/" + alert).bearer(token).body("a", "text/plain").send());
    responses.add(send("PUT", API + "/me/alerts/" + alert, token));
    responses.add(send("DELETE", API + "/me/alerts/" + UUID.randomUUID(), token));
    responses.add(send("GET", API + "/me/notifications?pageSize=1000", token));
    responses.add(send("GET", API + "/me/notifications?page=0", token));
    responses.add(send("GET", API + "/me/notifications?unreadOnly=yes", token));
    responses.add(send("GET", API + "/me/notifications?page=99999999999", token));
    responses.add(send("GET", API + "/me/notifications", token));
    responses.add(send("POST", API + "/me/notifications/" + UUID.randomUUID() + "/read", token));
    responses.add(send("POST", API + "/me/notifications/read-all", token));
    responses.add(send("GET", API + "/me/notifications/unread-count", token));
    responses.add(send("GET", API + "/me/notifications/unread-count", null));
    responses.add(send("GET", API + "/fx/currencies", null));
    responses.add(send("GET", API + "/fx/rates?base=TRY", null));
    responses.add(send("GET", API + "/fx/rates?base=bad", null));
    responses.add(send("GET", API + "/admin/outbox/stats", admin));
    responses.add(send("GET", API + "/admin/outbox/stats", token));
    responses.add(send("GET", API + "/admin/health", admin));
    responses.add(send("GET", API + "/health", null));
    responses.add(send("GET", API + "/weights/defaults", null));
    List<String> missing = new ArrayList<>();
    for (Resp response : responses) {
      try {
        AttackClient.assertApiSecurityHeaders(response);
      } catch (AssertionError e) {
        missing.add(response.status() + " " + response.body());
      }
      if (response.status() >= 400) {
        AttackClient.assertNoLeak(response);
        assertThat(response.header("content-type"))
            .as(response.toString())
            .startsWith("application/problem+json");
        assertThat(response.body()).doesNotContain("a".repeat(100));
      }
      assertThat(response.status()).as(response.toString()).isNotIn(500, 502, 503);
    }
    assertThat(missing).as("responses without the section 15.2 headers").isEmpty();
  }

  @Test
  void theOutboxStatsAreReadOnlyAndExposeNoEventData() {
    TestUser user = user(true, false, com.vitadelta.api.identity.UserStatus.ACTIVE, "en");
    UUID product = syntheticProduct("AVAILABLE", "100.00");
    subscription(
        user, product, "TR", com.vitadelta.api.alerts.AlertTrigger.PRICE_CHANGE, null, null);
    offersChanged(product, "TR", state("100.00", true), state("95.00", true));
    List<java.util.Map<String, Object>> before = rows("SELECT * FROM outbox_event ORDER BY id");
    String admin = adminToken();
    for (int i = 0; i < 5; i++) {
      Resp stats = send("GET", API + "/admin/outbox/stats", admin);
      assertThat(stats.status()).isEqualTo(200);
      assertThat(stats.json().get("pending").asLong()).isEqualTo(1);
      assertThat(stats.body())
          .doesNotContain(product.toString())
          .doesNotContain(user.id().toString());
    }
    assertThat(rows("SELECT * FROM outbox_event ORDER BY id"))
        .as("no event claimed or changed")
        .isEqualTo(before);
  }
}
