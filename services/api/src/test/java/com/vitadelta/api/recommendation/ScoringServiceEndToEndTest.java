package com.vitadelta.api.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.catalog.web.CatalogApiTest;
import com.vitadelta.api.identity.ContractSchemas;
import com.vitadelta.api.identity.Role;
import com.vitadelta.api.support.TestData;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import tools.jackson.databind.JsonNode;

/**
 * End to end against the real scoring service: the image is built from {@code services/scoring}
 * (its Dockerfile, lock file and sources) and the API talks to it over HTTP with the seeded
 * catalog. The golden pair of section 6.11 (GT-1) holds in the full TR market: VALUE ranks GOLD-A
 * before GOLD-B, RICHNESS the other way round, and the comparison is {@code NOT_WORTH_IT} in both
 * request orders.
 */
class ScoringServiceEndToEndTest extends CatalogApiTest {

  private static final Path SCORING_DIRECTORY =
      TestData.dataDir().getParent().resolve("services/scoring");

  @SuppressWarnings("resource")
  private static final GenericContainer<?> SCORING_SERVICE =
      new GenericContainer<>(
              new ImageFromDockerfile("vitadelta-scoring-test", false)
                  .withFileFromPath("Dockerfile", SCORING_DIRECTORY.resolve("Dockerfile"))
                  .withFileFromPath("pyproject.toml", SCORING_DIRECTORY.resolve("pyproject.toml"))
                  .withFileFromPath("uv.lock", SCORING_DIRECTORY.resolve("uv.lock"))
                  .withFileFromPath(".python-version", SCORING_DIRECTORY.resolve(".python-version"))
                  .withFileFromPath("src", SCORING_DIRECTORY.resolve("src")))
          .withExposedPorts(8000)
          .waitingFor(
              Wait.forHttp("/internal/v1/health")
                  .forStatusCode(200)
                  .withStartupTimeout(Duration.ofMinutes(5)));

  static {
    SCORING_SERVICE.start();
  }

  @DynamicPropertySource
  static void scoringService(DynamicPropertyRegistry registry) {
    registry.add(
        "vitadelta.scoring.base-url",
        () -> "http://" + SCORING_SERVICE.getHost() + ":" + SCORING_SERVICE.getMappedPort(8000));
  }

  private EntityExchangeResult<String> post(String path, String body, String token) {
    return client
        .post()
        .uri(URI.create("http://localhost:" + port + path))
        .contentType(MediaType.APPLICATION_JSON)
        .headers(h -> {
          h.set("X-Forwarded-For", ip);
          h.setBearerAuth(token);
        })
        .body(body)
        .exchange()
        .returnResult(String.class);
  }

  private List<String> rankedCodes(String mode, String token) {
    EntityExchangeResult<String> result =
        post(
            "/api/v1/recommendations/query",
            "{\"country\":\"TR\",\"ranking\":{\"mode\":\"" + mode + "\"},\"pageSize\":100}",
            token);
    assertStatus(result, 200);
    ContractSchemas.assertConforms("RecommendationQueryResponse", result.getResponseBody());
    List<String> codes = new ArrayList<>();
    json(result).get("items").forEach(item -> codes.add(item.get("code").asString()));
    return codes;
  }

  @Test
  void theRealScoringServiceRanksTheGoldenPairAsSpecified() {
    String token = token(Role.USER);
    List<String> value = rankedCodes("VALUE", token);
    assertThat(value).contains("GOLD-A", "GOLD-B");
    assertThat(value.indexOf("GOLD-A")).isLessThan(value.indexOf("GOLD-B"));
    List<String> richness = rankedCodes("RICHNESS", token);
    assertThat(richness.indexOf("GOLD-B")).isLessThan(richness.indexOf("GOLD-A"));
  }

  @Test
  void theRealScoringServiceFindsGoldBNotWorthItInBothRequestOrders() {
    String token = token(Role.USER);
    UUID goldA = productId("GOLD-A");
    UUID goldB = productId("GOLD-B");
    for (boolean swappedRequest : List.of(false, true)) {
      UUID baseline = swappedRequest ? goldB : goldA;
      UUID candidate = swappedRequest ? goldA : goldB;
      EntityExchangeResult<String> result =
          post(
              "/api/v1/recommendations/compare",
              "{\"country\":\"TR\",\"baselineProductId\":\""
                  + baseline
                  + "\",\"candidateProductId\":\""
                  + candidate
                  + "\"}",
              token);
      assertStatus(result, 200);
      ContractSchemas.assertConforms("CompareResponse", result.getResponseBody());
      JsonNode body = json(result);
      assertThat(body.get("verdict").asString()).isEqualTo("NOT_WORTH_IT");
      assertThat(body.get("swapped").asBoolean()).isEqualTo(swappedRequest);
      assertThat(body.get("productA").get("code").asString()).isEqualTo("GOLD-A");
      assertThat(body.get("productB").get("code").asString()).isEqualTo("GOLD-B");
      assertThat(body.get("deltaCost").decimalValue()).isEqualByComparingTo("1.6667");
      assertThat(body.get("explanation").get("key").asString())
          .isEqualTo("explain.verdict.not_worth_it");
      assertThat(body.get("meta").get("marketSize").asInt()).isGreaterThan(2);
    }
  }
}
