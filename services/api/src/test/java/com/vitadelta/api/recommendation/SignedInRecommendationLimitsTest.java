package com.vitadelta.api.recommendation;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.catalog.price.StockFilter;
import com.vitadelta.api.identity.Role;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

/**
 * Signed-in callers are limited per user before the country catalog is loaded: a request budget for
 * query cache misses and comparisons, and a cap on requests in flight. Cached rankings stay
 * available, and other users keep their own budget.
 */
@TestPropertySource(
    properties = {
      "vitadelta.recommendation.user-requests-per-minute=3",
      "vitadelta.recommendation.user-concurrency=1"
    })
class SignedInRecommendationLimitsTest extends RecommendationApiTestBase {

  private static final String CACHED_QUERY = "{\"country\":\"TR\"}";

  private static String missQuery(int variant) {
    return "{\"country\":\"TR\",\"weights\":{\"categories\":{\"VITAMIN\":0.%d}}}"
        .formatted(9 - variant);
  }

  private void stubDelayedScore(int delayMillis) {
    scoring()
        .stubFor(
            post(urlEqualTo("/internal/v1/score"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            scoreAnswer(
                                    expectedCandidates("TR", StockFilter.IN_STOCK_ONLY),
                                    StockFilter.IN_STOCK_ONLY)
                                .toString())
                        .withFixedDelay(delayMillis)));
  }

  @Test
  void cacheMissesAreLimitedPerUserBeforeTheCatalogIsLoadedAndCachedRankingsStayAvailable() {
    stubScore(expectedCandidates("TR", StockFilter.IN_STOCK_ONLY), StockFilter.IN_STOCK_ONLY);
    TestUser user = user(Role.USER);
    TestUser other = user(Role.USER);

    assertStatus(query(CACHED_QUERY, user.token(), null), 200);
    assertStatus(query(missQuery(1), user.token(), null), 200);
    assertStatus(query(missQuery(2), user.token(), null), 200);
    assertThat(scoringRequests("score")).hasSize(3);

    EntityExchangeResult<String> limited = query(missQuery(3), user.token(), null);
    assertStatus(limited, 429);
    assertThat(limited.getResponseHeaders().getFirst("Retry-After")).isNotNull();
    assertThat(json(limited).get("type").asString())
        .isEqualTo("https://vitadelta.dev/problems/rate_limited");
    assertThat(scoringRequests("score")).as("no scoring call for the limited request").hasSize(3);

    assertStatus(query(CACHED_QUERY, user.token(), null), 200);
    assertThat(scoringRequests("score")).hasSize(3);
    assertStatus(query(missQuery(3), other.token(), null), 200);
  }

  @Test
  void comparisonsAreLimitedPerUser() {
    TestUser user = user(Role.USER);
    String body =
        "{\"country\":\"TR\",\"baselineProductId\":\""
            + productId("GOLD-A")
            + "\",\"candidateProductId\":\""
            + productId("GOLD-B")
            + "\"}";
    for (int i = 0; i < 3; i++) {
      // Scoring is not stubbed: the request is admitted and fails downstream, but it was counted.
      assertThat(compare(body, user.token()).getStatus().value()).isNotEqualTo(429);
    }
    assertStatus(compare(body, user.token()), 429);
    assertThat(scoringRequests("compare")).hasSize(3);
  }

  @Test
  void aSecondRequestInFlightIsRejectedWhileTheFirstIsStillScoring() throws Exception {
    stubDelayedScore(1500);
    TestUser user = user(Role.USER);
    CompletableFuture<EntityExchangeResult<String>> first =
        CompletableFuture.supplyAsync(() -> query(missQuery(0), user.token(), null));
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (scoringRequests("score").isEmpty() && System.nanoTime() < deadline) {
      TimeUnit.MILLISECONDS.sleep(10);
    }
    assertThat(scoringRequests("score")).as("the first request is scoring").hasSize(1);

    assertStatus(query(missQuery(1), user.token(), null), 429);
    assertThat(scoringRequests("score")).hasSize(1);
    assertStatus(first.get(10, TimeUnit.SECONDS), 200);

    // The slot is free again once the first request has finished.
    assertStatus(query(missQuery(1), user.token(), null), 200);
  }
}
