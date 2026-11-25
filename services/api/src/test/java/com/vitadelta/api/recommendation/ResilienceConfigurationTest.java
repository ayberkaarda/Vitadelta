package com.vitadelta.api.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.catalog.price.StockFilter;
import com.vitadelta.api.identity.Role;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * The Resilience4j Spring Boot 4 module binds the section 7.6 settings from {@code application.yml}
 * on Spring Boot 4.2 (context load), and the scoring metrics are exported: call latency histogram,
 * circuit breaker state, ranking cache hits and misses, anonymous quota rejections.
 */
class ResilienceConfigurationTest extends RecommendationApiTestBase {

  @Autowired RetryRegistry retries;
  @Autowired MeterRegistry meters;

  @Test
  void theCircuitBreakerAndRetryCarryTheSpecifiedSettings() {
    CircuitBreakerConfig breaker =
        circuitBreakers.circuitBreaker(ScoringGateway.INSTANCE).getCircuitBreakerConfig();
    assertThat(breaker.getSlidingWindowType())
        .isEqualTo(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED);
    assertThat(breaker.getSlidingWindowSize()).isEqualTo(20);
    assertThat(breaker.getMinimumNumberOfCalls()).isEqualTo(20);
    assertThat(breaker.getFailureRateThreshold()).isEqualTo(50f);
    assertThat(breaker.getWaitIntervalFunctionInOpenState().apply(1))
        .isEqualTo(Duration.ofSeconds(30).toMillis());
    assertThat(breaker.getRecordExceptionPredicate().test(new ResourceAccessException("down")))
        .isTrue();
    assertThat(
            breaker
                .getRecordExceptionPredicate()
                .test(
                    HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "x", null, null, null)))
        .isTrue();
    assertThat(
            breaker
                .getIgnoreExceptionPredicate()
                .test(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "x", null, null, null)))
        .isTrue();

    RetryConfig retry = retries.retry(ScoringGateway.INSTANCE).getRetryConfig();
    assertThat(retry.getMaxAttempts()).isEqualTo(2);
    assertThat(retry.getExceptionPredicate().test(connectionRefused())).isTrue();
    assertThat(
            retry
                .getExceptionPredicate()
                .test(new ResourceAccessException("timeout", new HttpTimeoutException("read"))))
        .isFalse();
    assertThat(
            retry
                .getExceptionPredicate()
                .test(
                    HttpServerErrorException.create(
                        HttpStatus.INTERNAL_SERVER_ERROR, "x", null, null, null)))
        .isFalse();
  }

  @Test
  void scoringCallsCircuitStateCacheAndQuotaRejectionsAreMeasured() {
    stubScore(expectedCandidates("TR", StockFilter.IN_STOCK_ONLY), StockFilter.IN_STOCK_ONLY);
    TestUser user = user(Role.USER);
    double hitsBefore = cacheGets("hit");
    double missesBefore = cacheGets("miss");
    assertStatus(query("{\"country\":\"TR\"}", user.token(), null), 200);
    assertStatus(query("{\"country\":\"TR\"}", user.token(), null), 200);
    assertThat(cacheGets("miss")).isEqualTo(missesBefore + 1);
    assertThat(cacheGets("hit")).isEqualTo(hitsBefore + 1);

    Timer latency =
        meters
            .get(ScoringGateway.LATENCY_METRIC)
            .tag("operation", "score")
            .tag("outcome", "success")
            .timer();
    assertThat(latency.count()).isPositive();
    assertThat(
            meters
                .get("resilience4j.circuitbreaker.state")
                .tag("name", ScoringGateway.INSTANCE)
                .tag("state", "closed")
                .gauge()
                .value())
        .isEqualTo(1.0);

    double rejectedBefore =
        meters.get(RecommendationService.QUOTA_REJECTED_METRIC).counter().count();
    String cookie = anonymousCookie();
    for (int i = 0; i < 4; i++) {
      query("{\"country\":\"TR\"}", null, cookie);
    }
    assertThat(meters.get(RecommendationService.QUOTA_REJECTED_METRIC).counter().count())
        .isEqualTo(rejectedBefore + 1);
  }

  private double cacheGets(String result) {
    return meters
        .get("cache.gets")
        .tag("cache", RecommendationCaches.RANKING_CACHE)
        .tag("result", result)
        .functionCounter()
        .count();
  }

  private static ResourceAccessException connectionRefused() {
    return new ResourceAccessException(
        "refused", new IOException("wrapped", new ConnectException("Connection refused")));
  }
}
