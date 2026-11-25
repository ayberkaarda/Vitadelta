package com.vitadelta.api.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.identity.ContractSchemas;
import com.vitadelta.api.support.TestData;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.util.LinkedMultiValueMap;
import tools.jackson.databind.JsonNode;

/**
 * The alert flow end to end (section 13.7 acceptance): the price-drop fixture file imported through
 * the real admin import writes exactly one {@code OFFERS_CHANGED} per changed pair inside the
 * import transaction; a {@code PRICE_DROP} subscription on {@code GOLD-B} (5 %) then yields a
 * notification row and an e-mail readable through Mailpit's API. Re-importing the same file writes
 * no event. The seeded offers are restored afterwards.
 */
class AlertPipelineEndToEndTest extends AlertsIntegrationTest {

  private String admin;

  @AfterEach
  void restoreSeededOffers() throws Exception {
    if (admin != null) {
      importOffers("offers.synthetic.csv");
      clearOutbox();
    }
  }

  private JsonNode importOffers(String file) throws Exception {
    byte[] csv = Files.readAllBytes(TestData.dataDir().resolve("fixtures").resolve(file));
    LinkedMultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
    HttpHeaders partHeaders = new HttpHeaders();
    partHeaders.setContentType(MediaType.parseMediaType("text/csv"));
    parts.add(
        "file",
        new HttpEntity<>(
            new ByteArrayResource(csv) {
              @Override
              public String getFilename() {
                return "offers.csv";
              }
            },
            partHeaders));
    EntityExchangeResult<String> accepted =
        client
            .post()
            .uri("/api/v1/admin/imports/offers")
            .headers(h -> h.setBearerAuth(admin))
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(parts)
            .exchange()
            .returnResult(String.class);
    assertStatus(accepted, 202);
    String location = accepted.getResponseHeaders().getFirst(HttpHeaders.LOCATION);
    JsonNode[] done = new JsonNode[1];
    Awaitility.await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(100))
        .until(() -> {
          JsonNode job = json(get(location, admin));
          done[0] = job;
          return !job.get("status").asString().equals("RUNNING");
        });
    assertThat(done[0].get("status").asString()).as(done[0].toString()).isEqualTo("SUCCEEDED");
    return done[0];
  }

  private long catalogVersion() {
    return jdbc.sql("SELECT version FROM catalog_version WHERE id = 1").query(Long.class).single();
  }

  @Test
  void theImportedPriceDropReachesTheInboxAndMailpit() throws Exception {
    admin = adminToken();
    TestUser user = user();
    String token = token(user);
    UUID goldB = productId("GOLD-B");
    UUID gummy = productId("BH-FAMILY-GUMMY");
    EntityExchangeResult<String> alert =
        post(
            "/api/v1/me/alerts",
            body(
                Map.of(
                    "productId",
                    goldB.toString(),
                    "countryCode",
                    "TR",
                    "trigger",
                    "PRICE_DROP",
                    "thresholdPercent",
                    5)),
            token);
    assertStatus(alert, 201);
    assertThat(json(alert).get("baselinePrice").decimalValue()).isEqualByComparingTo("900");

    long versionBefore = catalogVersion();
    JsonNode job = importOffers("offers.price-drop.synthetic.csv");
    long catalogVersion = catalogVersion();
    assertThat(catalogVersion).as("one bump per changing import").isEqualTo(versionBefore + 1);
    List<Map<String, Object>> written = events();
    assertThat(written).hasSize(2);
    assertThat(written.stream().map(e -> e.get("event_type"))).containsOnly("OFFERS_CHANGED");
    assertThat(written.stream().map(e -> e.get("aggregate_id")))
        .containsExactlyInAnyOrder(goldB + ":TR", gummy + ":TR");
    JsonNode drop =
        JSON.readTree(
            (String)
                written.stream()
                    .filter(e -> e.get("aggregate_id").equals(goldB + ":TR"))
                    .findFirst()
                    .orElseThrow()
                    .get("payload"));
    assertThat(drop.get("productId").asString()).isEqualTo(goldB.toString());
    assertThat(drop.get("countryCode").asString()).isEqualTo("TR");
    assertThat(drop.get("before").get("effectivePrice").decimalValue()).isEqualByComparingTo("900");
    assertThat(drop.get("after").get("effectivePrice").decimalValue()).isEqualByComparingTo("846");
    assertThat(drop.get("after").get("inStock").asBoolean()).isTrue();
    assertThat(drop.get("after").get("retailer").asString()).isEqualTo("Fixture Pharmacy");
    assertThat(drop.get("catalogVersion").asLong()).isEqualTo(catalogVersion);
    assertThat(job.get("summary").get("changedPairs").asInt()).isEqualTo(2);

    drainOutbox();

    List<Map<String, Object>> rows = notifications(user);
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().get("type")).isEqualTo("ALERT_PRICE_DROP");
    assertThat(rows.getFirst().get("email_sent_at")).isNotNull();
    EntityExchangeResult<String> inbox = get("/api/v1/me/notifications", token);
    ContractSchemas.assertConforms("NotificationPage", inbox.getResponseBody());
    JsonNode payload = json(inbox).get("items").get(0).get("payload");
    assertThat(payload.get("productCode").asString()).isEqualTo("GOLD-B");
    assertThat(payload.get("after").get("effectivePrice").decimalValue())
        .isEqualByComparingTo("846");
    assertThat(json(get("/api/v1/me/notifications/unread-count", token)).get("unreadCount").asInt())
        .isEqualTo(1);
    JsonNode subscription = json(get("/api/v1/me/alerts", token)).get("items").get(0);
    assertThat(subscription.get("baselinePrice").decimalValue()).isEqualByComparingTo("846");
    assertThat(subscription.get("lastTriggeredAt").isNull()).isFalse();

    JsonNode message = awaitMessage(user.email());
    assertThat(message.path("Subject").asString())
        .isEqualTo("Price drop: Gold Standard Daily B is now TRY846.00");
    assertThat(message.path("Text").asString())
        .contains("Change your notification preferences: https://vitadelta.test/account");
    assertThat(events().stream().map(e -> e.get("outcome"))).containsOnly("DELIVERED");

    clearOutbox();
    Instant before = clock.instant();
    JsonNode again = importOffers("offers.price-drop.synthetic.csv");
    assertThat(again.get("summary").get("changedPairs").asInt()).isZero();
    assertThat(events()).isEmpty();
    assertThat(catalogVersion()).as("an unchanged import bumps nothing").isEqualTo(catalogVersion);
    assertThat(clock.instant()).isEqualTo(before);
  }
}
