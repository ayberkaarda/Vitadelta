package com.vitadelta.api.recommendation;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.catalog.price.StockFilter;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

/**
 * Section 7.6: no database connection or transaction is held while the scoring service is called.
 * The application runs with a pool of two connections and the test holds one of them, so the
 * request has exactly one connection to work with; while the (delayed) scoring call is in flight
 * that connection is idle and another statement gets it at once (the pool waits at most 250 ms).
 * Both a successful and a timed-out call are checked; the timed-out one refunds the anonymous query
 * in its own short transaction afterwards.
 */
@TestPropertySource(
    properties = {
      "spring.datasource.hikari.maximum-pool-size=2",
      "spring.datasource.hikari.minimum-idle=2",
      "spring.datasource.hikari.connection-timeout=250"
    })
class NoConnectionHeldDuringScoringTest extends RecommendationApiTestBase {

  private static final String TR_QUERY = "{\"country\":\"TR\"}";

  @Autowired DataSource dataSource;

  private void stubDelayed(int delayMillis) {
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

  /** Waits until the scoring stub received the call, then probes the pool during the call. */
  private void assertPoolIsFreeDuringTheCall() throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (scoringRequests("score").isEmpty() && System.nanoTime() < deadline) {
      TimeUnit.MILLISECONDS.sleep(10);
    }
    assertThat(scoringRequests("score")).as("the scoring call is in flight").hasSize(1);
    HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
    assertThat(pool.getMaximumPoolSize()).isEqualTo(2);
    assertThat(pool.getHikariPoolMXBean().getActiveConnections())
        .as("only the connection held by the test is active")
        .isEqualTo(1);
    long started = System.nanoTime();
    assertThat(jdbc.sql("SELECT count(*) FROM product").query(Long.class).single()).isPositive();
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(250));
  }

  private void assertConnectionFreeDuring(int delayMillis, int expectedStatus, String cookie)
      throws Exception {
    stubDelayed(delayMillis);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (Connection held = dataSource.getConnection()) {
      assertThat(held.isValid(1)).isTrue();
      Future<EntityExchangeResult<String>> result =
          executor.submit(() -> query(TR_QUERY, null, cookie));
      assertPoolIsFreeDuringTheCall();
      assertThat(result.get(30, TimeUnit.SECONDS).getStatus().value()).isEqualTo(expectedStatus);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void theRequestsConnectionIsFreeWhileTheScoringCallIsInFlight() throws Exception {
    String cookie = anonymousCookie();
    assertConnectionFreeDuring(1_200, 200, cookie);
    assertThat(queriesUsed(cookie)).isEqualTo(1);
  }

  @Test
  void theRequestsConnectionIsFreeDuringATimedOutCallAndTheQuotaIsRefunded() throws Exception {
    String cookie = anonymousCookie();
    assertConnectionFreeDuring(2_600, 503, cookie);
    assertThat(queriesUsed(cookie)).isZero();
  }
}
