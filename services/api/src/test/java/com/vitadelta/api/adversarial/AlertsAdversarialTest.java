package com.vitadelta.api.adversarial;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.adversarial.AttackClient.Req;
import com.vitadelta.api.adversarial.AttackClient.Resp;
import com.vitadelta.api.alerts.AlertsIntegrationTest;
import com.vitadelta.api.identity.PasswordHasher;
import com.vitadelta.api.identity.Role;
import com.vitadelta.api.identity.UserStatus;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Base of the adversarial tests of the Phase 5 surface (saved products, alert subscriptions, the
 * notification inbox, the outbox and the alert e-mail; project specification sections 8.4 to 8.7
 * and 15.7): the alerts test server (Mailpit over real SMTP, movable clock, the outbox processor
 * run by the test only) attacked through the raw {@link AttackClient}.
 *
 * <p>Synthetic products of these tests use the code prefix {@value #PREFIX} and a fictional brand;
 * they are removed after every test together with the subscriptions and saved rows on them.
 */
abstract class AlertsAdversarialTest extends AlertsIntegrationTest {

  static final String API = "/api/v1";
  static final String PREFIX = "ADVA-";
  static final String PASSWORD = "plum-orbit-lantern-quartz";

  /** The configured web base URL of the test environment (the only allowed link target). */
  static final String WEB_BASE = "https://vitadelta.test";

  @Autowired PasswordHasher hasher;

  AttackClient raw;

  @BeforeEach
  void setUpAttackClient() {
    raw = new AttackClient(port);
  }

  @AfterEach
  void removeSyntheticProducts() {
    jdbc.sql(
            "DELETE FROM alert_subscription WHERE product_id IN"
                + " (SELECT id FROM product WHERE code LIKE ?)")
        .param(PREFIX + "%")
        .update();
    jdbc.sql(
            "DELETE FROM saved_product WHERE product_id IN"
                + " (SELECT id FROM product WHERE code LIKE ?)")
        .param(PREFIX + "%")
        .update();
    int removed = jdbc.sql("DELETE FROM product WHERE code LIKE ?").param(PREFIX + "%").update();
    if (removed > 0) {
      jdbc.sql("UPDATE catalog_version SET version = version + 1, bumped_at = now() WHERE id = 1")
          .update();
    }
  }

  // ---------- Raw requests ----------

  /** A raw request from a fresh client IP (the trusted loopback forwards it). */
  Req req(String method, String path) {
    return raw.req(method, path).ip(AdversarialIntegrationTest.randomIp());
  }

  Resp send(String method, String path, String token) {
    return req(method, path).bearer(token).send();
  }

  Resp sendJson(String method, String path, String token, String json) {
    return req(method, path).bearer(token).json(json).send();
  }

  Resp sendJson(String method, String path, String token, Map<String, ?> body) {
    return req(method, path).bearer(token).json(body).send();
  }

  Resp saveProduct(String token, UUID productId, String country) {
    return sendJson(
        "POST",
        API + "/me/saved-products",
        token,
        Map.of("productId", productId.toString(), "countryCode", country));
  }

  Resp createAlert(String token, UUID productId, String country, String trigger) {
    return sendJson(
        "POST",
        API + "/me/alerts",
        token,
        Map.of("productId", productId.toString(), "countryCode", country, "trigger", trigger));
  }

  Resp createPriceDrop(String token, UUID productId, String country, Object threshold) {
    return sendJson(
        "POST",
        API + "/me/alerts",
        token,
        Map.of(
            "productId",
            productId.toString(),
            "countryCode",
            country,
            "trigger",
            "PRICE_DROP",
            "thresholdPercent",
            threshold));
  }

  Resp patchAlert(String token, String alertId, String json) {
    return sendJson("PATCH", API + "/me/alerts/" + alertId, token, json);
  }

  /** The id of the subscription of a 200/201 answer. */
  static UUID alertId(Resp created) {
    assertThat(created.status()).as(created.toString()).isIn(200, 201);
    return UUID.fromString(created.json().get("id").asString());
  }

  /** An RFC 9457 {@code about:blank} 404 without leaks. */
  static void assertBlankNotFound(Resp response) {
    assertThat(response.status()).as(response.toString()).isEqualTo(404);
    assertThat(response.header("content-type")).startsWith("application/problem+json");
    assertThat(response.json().get("type").asString()).isEqualTo("about:blank");
    AttackClient.assertNoLeak(response);
    AttackClient.assertApiSecurityHeaders(response);
  }

  /** A problem body without the per-request members {@code instance} and {@code requestId}. */
  static String comparableProblem(Resp response) {
    JsonNode body = response.json();
    if (body instanceof ObjectNode object) {
      object.remove("instance");
      object.remove("requestId");
    }
    return body.toString();
  }

  /** Header names of a response, without the per-response ones. */
  static List<String> headerNames(Resp response) {
    return response.comparableHeaders().keySet().stream()
        .filter(h ->
            !List.of(
                    "x-request-id",
                    "content-length",
                    "transfer-encoding",
                    "connection",
                    "keep-alive")
                .contains(h))
        .toList();
  }

  // ---------- Principals ----------

  /** A verified FREE user that can sign in with {@link #PASSWORD} (Argon2id hash stored). */
  TestUser withPassword(TestUser user) {
    jdbc.sql("UPDATE app_user SET password_hash = ? WHERE id = ?")
        .params(hasher.hash(PASSWORD), user.id())
        .update();
    return user;
  }

  TestUser unverifiedUser() {
    return user(false, true, UserStatus.ACTIVE, "en");
  }

  String unverifiedToken(TestUser user) {
    return accessTokens.issue(user.id(), Role.USER, false).value();
  }

  /** Replaces the ACTIVE subscription of {@code user} with PRO for 30 days. */
  void makePro(TestUser user) {
    jdbc.sql(
            "UPDATE subscription SET status = 'CANCELED', canceled_at = now()"
                + " WHERE user_id = ? AND status = 'ACTIVE'")
        .param(user.id())
        .update();
    jdbc.sql(
            "INSERT INTO subscription (id, user_id, plan_code, status, source, started_at,"
                + " current_period_end) VALUES (gen_random_uuid(), ?, 'PRO', 'ACTIVE', 'MANUAL',"
                + " now(), now() + interval '30 days')")
        .param(user.id())
        .update();
  }

  /** Replaces the ACTIVE PRO subscription of {@code user} with FREE (as an expiry would). */
  void downgradeToFree(TestUser user) {
    jdbc.sql("UPDATE subscription SET status = 'EXPIRED' WHERE user_id = ? AND status = 'ACTIVE'")
        .param(user.id())
        .update();
    jdbc.sql(
            "INSERT INTO subscription (id, user_id, plan_code, status, source, started_at)"
                + " VALUES (gen_random_uuid(), ?, 'FREE', 'ACTIVE', 'MANUAL', now())")
        .param(user.id())
        .update();
  }

  // ---------- Catalog ----------

  /** Seeded products that are {@code AVAILABLE} in {@code country}, in id order. */
  List<UUID> availableIn(String country, int count) {
    List<UUID> ids =
        jdbc.sql(
                "SELECT product_id FROM product_availability WHERE country_code = ?"
                    + " AND status = 'AVAILABLE' ORDER BY product_id LIMIT ?")
            .params(country, count)
            .query(UUID.class)
            .list();
    assertThat(ids).as("seeded products available in %s", country).hasSize(count);
    return ids;
  }

  /**
   * A synthetic product with a fictional brand, available in TR with the given status ({@code
   * null}: no availability row) and, when {@code price} is set, one in-stock TRY offer.
   */
  UUID syntheticProduct(String status, String price) {
    return syntheticProduct("Synthetic Adversary Labs", "Synthetic daily", status, price);
  }

  UUID syntheticProduct(String brand, String name, String status, String price) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO product (id, code, brand, name, dosage_form, units_per_container,"
                + " units_per_serving, servings_per_day, source, synthetic, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, 'TABLET', 60, 1, 1, 'Synthetic adversarial test product',"
                + " true, now(), now())")
        .params(id, code(id), brand, name)
        .update();
    if (status != null) {
      jdbc.sql(
              "INSERT INTO product_availability (product_id, country_code, status)"
                  + " VALUES (?, 'TR', ?)")
          .params(id, status)
          .update();
    }
    if (price != null) {
      setOffer(id, price, true);
    }
    return id;
  }

  static String code(UUID id) {
    return PREFIX + id.toString().substring(0, 8).toUpperCase(Locale.ROOT);
  }

  /** Sets the single TR offer of {@code productId} (insert or update). */
  void setOffer(UUID productId, String price, boolean inStock) {
    jdbc.sql(
            "INSERT INTO offer (id, product_id, country_code, retailer, price, currency, in_stock,"
                + " url, checked_at) VALUES (gen_random_uuid(), ?, 'TR', 'Fixture Pharmacy', ?,"
                + " 'TRY', ?, 'https://fixture-pharmacy.example/tr/p/adv', ?)"
                + " ON CONFLICT (product_id, country_code, retailer) DO UPDATE"
                + " SET price = EXCLUDED.price, in_stock = EXCLUDED.in_stock")
        .params(
            productId,
            new BigDecimal(price),
            inStock,
            Timestamp.from(clock.instant().minus(Duration.ofHours(1))))
        .update();
  }

  void setAvailability(UUID productId, String status) {
    jdbc.sql(
            "UPDATE product_availability SET status = ? WHERE product_id = ? AND country_code = 'TR'")
        .params(status, productId)
        .update();
  }

  // ---------- Database snapshots ----------

  /** Every column of the rows a query returns, as text, for before/after comparison. */
  List<Map<String, Object>> rows(String sql, Object... params) {
    return jdbc.sql(sql).params(params).query().listOfRows().stream()
        .<Map<String, Object>>map(TreeMap::new)
        .toList();
  }

  long count(String sql, Object... params) {
    return jdbc.sql(sql).params(params).query(Long.class).single();
  }

  /** Inserts a {@code PLAN_ACTIVATED} notification (unread) for {@code user}. */
  UUID insertNotification(TestUser user) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO notification (id, user_id, type, payload, created_at) VALUES (?, ?,"
                + " 'PLAN_ACTIVATED', CAST(? AS jsonb), ?)")
        .params(
            id,
            user.id(),
            "{\"planCode\":\"PRO\",\"source\":\"PROMO\","
                + "\"currentPeriodEnd\":\"2026-12-24T00:00:00Z\",\"extended\":false}",
            Timestamp.from(clock.instant()))
        .update();
    return id;
  }

  /** The outcomes of every outbox event, sorted. */
  List<String> outcomes() {
    return jdbc.sql("SELECT coalesce(outcome, 'PENDING') FROM outbox_event ORDER BY 1")
        .query(String.class)
        .list();
  }

  /** Runs the processor until nothing is due, moving the clock past every retry back-off. */
  void drainWithRetries(int rounds) {
    for (int i = 0; i < rounds; i++) {
      drainOutbox();
      clock.advance(Duration.ofMinutes(40));
    }
    drainOutbox();
  }

  // ---------- Imports ----------

  /** Waits until the import job of a 202 answer has finished and returns its detail. */
  JsonNode awaitJob(Resp accepted, String adminToken) {
    assertThat(accepted.status()).as(accepted.toString()).isEqualTo(202);
    String id = accepted.json().get("id").asString();
    Instant deadline = Instant.now().plus(Duration.ofSeconds(90));
    while (true) {
      Resp job = send("GET", API + "/admin/imports/" + id, adminToken);
      assertThat(job.status()).as(job.toString()).isEqualTo(200);
      JsonNode body = job.json();
      if (!body.get("status").asString().equals("RUNNING")) {
        return body;
      }
      assertThat(Instant.now()).as("import job %s still running", id).isBefore(deadline);
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
  }

  /** {@code POST /admin/imports/offers} with one CSV file part. */
  Resp importOffersCsv(String csv, String adminToken, Map<String, String> extraHeaders) {
    byte[] body =
        CatalogAdversarialTest.multipart(
            List.of(
                new CatalogAdversarialTest.Part(
                    "file", "offers.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8))));
    Req request =
        req("POST", API + "/admin/imports/offers")
            .bearer(adminToken)
            .body(body, "multipart/form-data; boundary=" + CatalogAdversarialTest.BOUNDARY);
    extraHeaders.forEach(request::header);
    return request.send();
  }

  /** One CSV row of the offers import for TR, retailer {@code Fixture Pharmacy}. */
  static String offerRow(String productCode, String price, boolean inStock) {
    return productCode
        + ",TR,Fixture Pharmacy,"
        + price
        + ",TRY,"
        + inStock
        + ",https://fixture-pharmacy.example/tr/p/adv,2026-11-20T09:00:00Z\n";
  }
}
