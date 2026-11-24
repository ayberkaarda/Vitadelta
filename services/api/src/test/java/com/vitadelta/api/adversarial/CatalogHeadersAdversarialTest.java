package com.vitadelta.api.adversarial;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.adversarial.AttackClient.Resp;
import com.vitadelta.api.identity.Role;
import com.vitadelta.api.support.TestData;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Section 15.7 case 18 parts and section 15.2/15.4 for the Phase 4 endpoints: the API security
 * headers on every response including errors, an OpenAPI document that describes no more than the
 * contract, no Actuator on the API port, path-normalization tricks answered as problems, and the
 * anonymous per-IP limits of {@code /products/**} and {@code /recommendations/compare} (120 per
 * minute, shared) that spoofed forwarding headers cannot evade.
 */
class CatalogHeadersAdversarialTest extends CatalogAdversarialTest {

  private void assertJsonResponse(Resp response, int status) {
    assertThat(response.status()).as(response.toString()).isEqualTo(status);
    AttackClient.assertApiSecurityHeaders(response);
    assertThat(response.header("x-powered-by")).isNull();
    assertThat(response.headers().allValues("server").toString()).doesNotContain("Tomcat");
    if (status < 300) {
      assertThat(response.header("content-type")).startsWith("application/json");
    } else if (status != 202) {
      assertThat(response.header("content-type")).startsWith("application/problem+json");
      AttackClient.assertNoLeak(response);
    }
  }

  @Test
  void everyPhaseFourEndpointCarriesTheApiHeadersOnSuccessAndOnError() {
    stubTr();
    String user = user(Role.USER).token();
    String admin = admin();
    UUID gold = productId("GOLD-A");
    for (String path : List.of("/countries", "/nutrients", "/weights/defaults", "/legal")) {
      assertJsonResponse(rawGet(API + path, null), 200);
    }
    assertJsonResponse(rawGet(API + "/openapi.json", null), 200);
    assertJsonResponse(rawGet(API + "/products?country=TR", null), 200);
    assertJsonResponse(rawGet(API + "/products/" + gold + "?country=TR", null), 200);
    assertJsonResponse(rawGet(API + "/products", null), 422);
    assertJsonResponse(rawGet(API + "/products?country=ZZ", null), 422);
    assertJsonResponse(rawGet(API + "/products/" + UUID.randomUUID() + "?country=TR", null), 404);
    assertJsonResponse(rawGet(API + "/products?country=TR", "garbage.token.value"), 401);

    assertJsonResponse(rawQuery(TR_QUERY, user, null), 200);
    assertJsonResponse(rawQuery("{\"country\":\"ZZ\"}", user, null), 422);
    assertJsonResponse(
        req("POST", API + "/recommendations/query")
            .bearer(user)
            .body(TR_QUERY, "text/plain")
            .send(),
        415);
    assertJsonResponse(
        rawQuery("{\"country\":\"TR\",\"p\":\"" + "x".repeat(70_000) + "\"}", user, null), 413);
    String cookie = anonymousCookie();
    for (int i = 0; i < 3; i++) {
      rawQuery(TR_QUERY, null, cookie);
    }
    assertJsonResponse(rawQuery(TR_QUERY, null, cookie), 403);
    assertJsonResponse(
        rawCompare(
            "{\"country\":\"TR\",\"baselineProductId\":\""
                + gold
                + "\",\"candidateProductId\":\""
                + gold
                + "\"}",
            null),
        422);
    scoring().resetAll();
    caches.clear();
    scoring()
        .stubFor(post(urlEqualTo("/internal/v1/score")).willReturn(aResponse().withStatus(500)));
    assertJsonResponse(
        rawQuery("{\"country\":\"TR\",\"excludeExceedingUl\":true}", user, null), 503);

    String doc = productDocument(code("HDR"), "Adv Labs", "Headers", "");
    assertJsonResponse(importProducts(doc, null), 401);
    assertJsonResponse(importProducts(doc, user), 403);
    Resp accepted = importProducts(doc, admin);
    assertJsonResponse(accepted, 202);
    awaitJob(accepted, admin);
    assertJsonResponse(
        req("POST", API + "/admin/imports/products").bearer(admin).body(doc, "text/plain").send(),
        415);
    assertJsonResponse(rawGet(API + "/admin/imports/" + UUID.randomUUID(), admin), 404);
    assertJsonResponse(
        rawGet(API + "/admin/imports/" + accepted.json().get("id").asString(), admin), 200);
  }

  @Test
  void theOpenApiDocumentDescribesNothingBeyondTheContract() throws IOException {
    Resp response = rawGet(API + "/openapi.json", null);
    assertThat(response.status()).isEqualTo(200);
    Set<String> contractPaths = new TreeSet<>();
    for (String line :
        Files.readAllLines(
            TestData.dataDir().getParent().resolve("contracts/public-api.openapi.yaml"))) {
      if (line.matches("^  /[^ ]*:\\s*$")) {
        contractPaths.add(line.trim().replaceAll(":$", ""));
      }
    }
    JsonNode document = response.json();
    List<String> extra = new ArrayList<>();
    document
        .get("paths")
        .propertyNames()
        .forEach(path -> {
          String relative = path.startsWith(API) ? path.substring(API.length()) : path;
          // "entitlement-probe" controllers are test-only beans of the entitlement tests that
          // share this application context; they do not exist in the production application.
          if (!contractPaths.contains(relative) && !relative.contains("/entitlement-probe")) {
            extra.add(path);
          }
        });
    assertThat(extra).as("paths outside the contract").isEmpty();
    String body = response.body();
    for (String marker :
        List.of(
            "actuator", "/internal/", "com.vitadelta", "java.", "jakarta.", "springframework")) {
      assertThat(body).as("openapi.json reveals %s", marker).doesNotContain(marker);
    }
  }

  @Test
  void actuatorIsNotReachableThroughTheApiPort() {
    for (String path :
        List.of(
            "/actuator",
            "/actuator/health",
            "/actuator/metrics",
            "/actuator/env",
            "/actuator/heapdump",
            "/actuator/configprops",
            "/api/v1/actuator/health",
            "/api/v1/../actuator/env",
            "/api/v1/%2e%2e/actuator/env",
            "/api/v1/health/../../actuator/env")) {
      Resp response = rawGet(path, null);
      assertThat(response.status()).as(path + " -> " + response).isIn(400, 401, 403, 404);
      AttackClient.assertNoLeak(response);
      assertThat(response.body()).doesNotContain("\"status\":\"UP\"").doesNotContain("names");
    }
  }

  @Test
  void pathNormalizationTricksAreAnsweredAsProblemsWithTheApiHeaders() {
    List<String> offenders = new ArrayList<>();
    for (String path :
        List.of(
            API + "/products/..%2F..%2Fadmin%2Fimports?country=TR",
            API + "/products/%2e%2e/admin/imports/" + UUID.randomUUID(),
            API + "/products;jsessionid=adv/" + UUID.randomUUID() + "?country=TR",
            API + "/products//" + UUID.randomUUID() + "?country=TR",
            API + "/products/%5C..%5Cadmin?country=TR",
            API + "/admin/imports/%2e%2e%2fproducts")) {
      Resp response = rawGet(path, null);
      assertThat(response.status()).as(path + " -> " + response).isIn(400, 401, 404);
      AttackClient.assertNoLeak(response);
      String type = response.header("content-type");
      if (!"nosniff".equals(response.header("x-content-type-options"))
          || response.header("content-security-policy") == null
          || type == null
          || !type.startsWith("application/problem+json")) {
        offenders.add(path + " -> " + response.status() + " " + type);
      }
    }
    assertThat(offenders)
        .as("responses without the API headers or without a problem body")
        .isEmpty();
  }

  @Test
  void browsingIsCappedAtOneHundredTwentyPerMinuteAndSpoofedHeadersDoNotHelp() {
    // The bucket refills greedily (2 tokens per second), so a fast burst gets slightly more than
    // 120 requests before the first 429; spoofed entries must not reset it.
    int successes = 0;
    Resp capped = null;
    for (int i = 0; i < 200 && capped == null; i++) {
      Resp response =
          raw.req("GET", API + "/products?country=TR")
              .header("X-Forwarded-For", randomIp() + ", " + ip)
              .header("X-Real-IP", randomIp())
              .header("Forwarded", "for=" + randomIp())
              .send();
      if (response.status() == 200) {
        successes++;
      } else {
        capped = response;
      }
    }
    assertThat(successes).as("requests before the first 429").isBetween(120, 135);
    assertThat(capped).isNotNull();
    AttackClient.assertProblem(capped, 429, "rate_limited");
    assertThat(capped.header("retry-after")).matches("[1-9][0-9]*");
    AttackClient.assertApiSecurityHeaders(capped);
    // Detail and compare draw from the same bucket: with at most a refilled token or two, a short
    // burst on each is rejected.
    String detailPath = API + "/products/" + productId("GOLD-A") + "?country=TR";
    String compareBody =
        "{\"country\":\"TR\",\"baselineProductId\":\""
            + productId("GOLD-A")
            + "\",\"candidateProductId\":\""
            + productId("GOLD-B")
            + "\"}";
    List<Integer> detail = new ArrayList<>();
    List<Integer> compare = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      detail.add(rawGet(detailPath, null).status());
      compare.add(rawCompare(compareBody, null).status());
    }
    assertThat(detail).as("product detail shares the browse bucket").contains(429);
    assertThat(compare).as("compare shares the browse bucket").contains(429);
    assertThat(rawGet(API + "/products?country=TR", user(Role.USER).token()).status())
        .as("signed-in users are not limited by the anonymous browse limit")
        .isEqualTo(200);
  }
}
