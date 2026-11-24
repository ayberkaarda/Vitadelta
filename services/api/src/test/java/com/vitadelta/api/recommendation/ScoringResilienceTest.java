package com.vitadelta.api.recommendation;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.catalog.price.StockFilter;
import com.vitadelta.api.identity.ContractSchemas;
import com.vitadelta.api.identity.Role;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

/**
 * Scoring client resilience (section 7.6, ADR-0003) through the real HTTP client against the
 * WireMock stub: HTTP 500, read timeout, connection errors and an open circuit answer {@code 503
 * scoring_unavailable} and refund the reserved anonymous query; only connection errors are retried;
 * cache hits keep being served while the circuit is open.
 */
class ScoringResilienceTest extends RecommendationApiTestBase {

  private static final String TR_QUERY = "{\"country\":\"TR\"}";

  @Autowired RetryRegistry retries;

  private void stubStatus(int status) {
    scoring()
        .stubFor(post(urlEqualTo("/internal/v1/score")).willReturn(aResponse().withStatus(status)));
  }

  private static void assertUnavailable(EntityExchangeResult<String> result) {
    assertProblem(result, 503, "scoring_unavailable");
    ContractSchemas.assertConforms("Problem", result.getResponseBody());
  }

  @Test
  void aServerErrorIsUnavailableIsNotRetriedAndRefundsTheQuota() {
    stubStatus(500);
    String cookie = anonymousCookie();
    assertUnavailable(query(TR_QUERY, null, cookie));
    assertThat(scoringRequests("score")).hasSize(1);
    assertThat(queriesUsed(cookie)).isZero();
    stubStatus(503);
    assertUnavailable(query(TR_QUERY, null, cookie));
    assertThat(scoringRequests("score")).hasSize(2);
    assertThat(queriesUsed(cookie)).isZero();
  }

  @Test
  void aReadTimeoutIsUnavailableIsNotRetriedAndRefundsTheQuota() {
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
                        .withFixedDelay(2_600)));
    String cookie = anonymousCookie();
    long started = System.nanoTime();
    assertUnavailable(query(TR_QUERY, null, cookie));
    long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
    assertThat(elapsedMillis).isBetween(1_900L, 2_550L);
    assertThat(scoringRequests("score")).hasSize(1);
    assertThat(queriesUsed(cookie)).isZero();
  }

  @Test
  void connectionErrorsAreRetriedOnceAndThenUnavailable() {
    Retry retry = retries.retry(ScoringGateway.INSTANCE);
    long retriedBefore = retry.getMetrics().getNumberOfFailedCallsWithRetryAttempt();
    long notRetriedBefore = retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt();
    CircuitBreaker breaker = circuitBreakers.circuitBreaker(ScoringGateway.INSTANCE);
    String cookie = anonymousCookie();
    withScoringStopped(() -> assertUnavailable(query(TR_QUERY, null, cookie)));
    assertThat(retry.getMetrics().getNumberOfFailedCallsWithRetryAttempt())
        .isEqualTo(retriedBefore + 1);
    assertThat(retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt())
        .isEqualTo(notRetriedBefore);
    assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(2);
    assertThat(queriesUsed(cookie)).isZero();

    stubStatus(500);
    long notRetried = retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt();
    assertUnavailable(query(TR_QUERY, null, cookie));
    assertThat(retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt())
        .isEqualTo(notRetried + 1);
    assertThat(retry.getMetrics().getNumberOfFailedCallsWithRetryAttempt())
        .isEqualTo(retriedBefore + 1);
  }

  @Test
  void anOpenCircuitAnswers503WithoutCallingWhileCacheHitsAreStillServed() {
    stubScore(expectedCandidates("TR", StockFilter.IN_STOCK_ONLY), StockFilter.IN_STOCK_ONLY);
    TestUser user = user(Role.USER);
    assertStatus(query(TR_QUERY, user.token(), null), 200);

    scoring().resetAll();
    stubStatus(500);
    CircuitBreaker breaker = circuitBreakers.circuitBreaker(ScoringGateway.INSTANCE);
    int attempts = 0;
    while (breaker.getState() != CircuitBreaker.State.OPEN && attempts < 30) {
      attempts++;
      String distinct =
          "{\"country\":\"TR\",\"weights\":{\"overrides\":{\"VIT_C\":" + (attempts / 10.0) + "}}}";
      assertUnavailable(query(distinct, user.token(), null));
    }
    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    assertThat(attempts).isBetween(10, 20);
    int callsWhenOpened = scoringRequests("score").size();

    String cookie = anonymousCookie();
    assertUnavailable(query("{\"country\":\"TR\",\"excludeExceedingUl\":true}", null, cookie));
    assertThat(queriesUsed(cookie)).isZero();
    EntityExchangeResult<String> cached = query(TR_QUERY, null, cookie);
    assertStatus(cached, 200);
    assertThat(queriesUsed(cookie)).isEqualTo(1);
    assertThat(scoringRequests("score")).hasSize(callsWhenOpened);
  }
}
