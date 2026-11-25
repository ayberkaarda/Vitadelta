package com.vitadelta.api.fx;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.Fault;
import com.vitadelta.api.common.jobs.JobLockKeys;
import com.vitadelta.api.fx.FxRefreshJob.Result;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/** {@link FxRefreshJob} against the WireMock provider and the real {@code fx_rate} table. */
class FxRefreshIntegrationTest extends FxApiTestBase {

  @Autowired DataSource dataSource;
  @Autowired MeterRegistry meters;

  private double failures(String reason) {
    var counter = meters.find("vitadelta.fx.refresh.failure").tag("reason", reason).counter();
    return counter == null ? 0 : counter.count();
  }

  private double successes() {
    var counter = meters.find("vitadelta.fx.refresh.success").counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  void aRefreshUpsertsEveryPairOfSupportedCurrenciesWithTheProviderDate() {
    LocalDate date = today().minusDays(1);
    stubAllBases(date);
    double before = successes();

    Result result = job.run();

    assertThat(result.ran()).isTrue();
    assertThat(result.failedBases()).isEmpty();
    assertThat(result.upsertedRows()).isEqualTo(pairCount());
    assertThat(storedRowCount()).isEqualTo(pairCount());
    assertThat(providerRequests()).isEqualTo(currencies.all().size());
    assertThat(successes() - before).isEqualTo(currencies.all().size());
    assertThat(currencies.all()).contains("TRY", "EUR", "GBP", "USD", EXTRA);
    assertThat(repository.newest("TRY", "USD").orElseThrow().rate()).isEqualTo(TRY_USD);
    assertThat(repository.newest("TRY", "USD").orElseThrow().asOf()).isEqualTo(date);
    assertThat(repository.newest("TRY", "USD").orElseThrow().source()).isEqualTo("FRANKFURTER");
    assertThat(repository.newest("CHF", "TRY")).isPresent();
  }

  @Test
  void aSecondRefreshOfTheSamePublicationIsIdempotent() {
    LocalDate date = today().minusDays(1);
    stubAllBases(date);
    job.run();
    List<String> first = storedRows();

    Result again = job.run();

    assertThat(again.upsertedRows()).isEqualTo(pairCount());
    assertThat(storedRows()).isEqualTo(first);
    assertThat(storedRowCount()).isEqualTo(pairCount());
  }

  @Test
  void aNewPublicationAddsRowsAndTheNewestDateWins() {
    stubAllBases(today().minusDays(2));
    job.run();
    FRANKFURTER.resetAll();
    stubAllBases(today().minusDays(1));

    job.run();

    assertThat(storedRowCount()).isEqualTo(2 * pairCount());
    assertThat(lookup.newest("TRY", "USD").orElseThrow().asOf()).isEqualTo(today().minusDays(1));
  }

  @Test
  void aRefreshClearsTheLookupCache() {
    storeRate("TRY", "USD", today().minusDays(3), new BigDecimal("0.02000000"));
    assertThat(lookup.newest("TRY", "USD").orElseThrow().rate()).isEqualByComparingTo("0.02");
    stubAllBases(today());

    job.run();

    assertThat(lookup.newest("TRY", "USD").orElseThrow().rate()).isEqualTo(TRY_USD);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "500",
        "422",
        "malformed",
        "wrongBase",
        "zeroRate",
        "negativeRate",
        "hugeRate",
        "emptyArray",
        "mixedDates",
        "timeout",
        "reset"
      })
  void providerErrorsLeaveTheStoredRatesUntouchedAndNeverThrow(String error) {
    LocalDate stored = today().minusDays(2);
    for (String base : currencies.all()) {
      for (String quote : quotesFor(base)) {
        storeRate(base, quote, stored, new BigDecimal("1.25000000"));
      }
    }
    List<String> before = storedRows();
    String base = "TRY";
    String valid = verifiedBody(base, quotesFor(base), today());
    switch (error) {
      case "500" -> stubBase(base, 500, "{\"status\":500,\"message\":\"internal error\"}");
      case "422" -> stubBase(base, 422, "{\"status\":422,\"message\":\"invalid currency: CHF\"}");
      case "malformed" -> stubBase(base, 200, valid.substring(0, valid.length() - 2));
      case "wrongBase" ->
          stubBase(base, 200, valid.replace("\"base\":\"TRY\"", "\"base\":\"EUR\""));
      case "zeroRate" -> stubBase(base, 200, valid.replace("\"rate\":0.024137", "\"rate\":0"));
      case "negativeRate" ->
          stubBase(base, 200, valid.replace("\"rate\":0.024137", "\"rate\":-0.024137"));
      case "hugeRate" -> stubBase(base, 200, valid.replace("\"rate\":0.024137", "\"rate\":1e15"));
      case "emptyArray" -> stubBase(base, 200, "[]");
      case "mixedDates" ->
          stubBase(base, 200, valid.replaceFirst(today().toString(), stored.toString()));
      case "timeout" ->
          FRANKFURTER.stubFor(
              WireMock.get(urlPathEqualTo("/v2/rates"))
                  .willReturn(aResponse().withStatus(200).withBody(valid).withFixedDelay(3000)));
      case "reset" ->
          FRANKFURTER.stubFor(
              WireMock.get(urlPathEqualTo("/v2/rates"))
                  .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
      default -> throw new IllegalArgumentException(error);
    }
    double failuresBefore =
        meters.find("vitadelta.fx.refresh.failure").counters().stream()
            .mapToDouble(c -> c.count())
            .sum();

    Result result = job.run();

    assertThat(result.ran()).isTrue();
    assertThat(result.failedBases()).contains(base);
    assertThat(result.upsertedRows()).isZero();
    assertThat(storedRows()).isEqualTo(before);
    assertThat(lookup.newest("TRY", "USD").orElseThrow().rate()).isEqualByComparingTo("1.25");
    double failuresAfter =
        meters.find("vitadelta.fx.refresh.failure").counters().stream()
            .mapToDouble(c -> c.count())
            .sum();
    assertThat(failuresAfter - failuresBefore).isEqualTo(currencies.all().size());
  }

  @Test
  void oneFailingBaseDoesNotStopTheOthers() {
    stubAllBases(today());
    stubBase("GBP", 500, "{}");
    double before = failures("HTTP_STATUS");

    Result result = job.run();

    assertThat(result.failedBases()).containsExactly("GBP");
    int perBase = currencies.all().size() - 1;
    assertThat(result.upsertedRows()).isEqualTo(pairCount() - perBase);
    assertThat(repository.newest("GBP", "TRY")).isEmpty();
    assertThat(repository.newest("TRY", "GBP")).isPresent();
    assertThat(failures("HTTP_STATUS") - before).isEqualTo(1);
  }

  @Test
  void theStartupRuleRefreshesAnEmptyTable() {
    stubAllBases(today());

    assertThat(job.isRefreshDue()).isTrue();
    Result result = job.runIfDue().orElseThrow();

    assertThat(result.upsertedRows()).isEqualTo(pairCount());
    assertThat(providerRequests()).isEqualTo(currencies.all().size());
  }

  @Test
  void theStartupRuleRefreshesRatesOlderThanOneDay() {
    storeRate("TRY", "USD", today().minusDays(2), TRY_USD);
    stubAllBases(today());

    assertThat(job.isRefreshDue()).isTrue();
    assertThat(job.runIfDue()).isPresent();
    assertThat(providerRequests()).isEqualTo(currencies.all().size());
  }

  @Test
  void theStartupRuleSkipsFreshRates() {
    storeRate("TRY", "USD", today().minusDays(1), TRY_USD);
    stubAllBases(today());

    assertThat(job.isRefreshDue()).isFalse();
    assertThat(job.runIfDue()).isEmpty();
    assertThat(providerRequests()).isZero();

    jdbc.sql("UPDATE fx_rate SET as_of = ?").param(today()).update();
    assertThat(job.isRefreshDue()).isFalse();
  }

  @Test
  void theAdvisoryLockHeldElsewhereSkipsTheRun() throws Exception {
    stubAllBases(today());
    try (Connection other = dataSource.getConnection()) {
      assertThat(lock(other, "SELECT pg_try_advisory_lock(?)")).isTrue();
      try {
        Result result = job.run();

        assertThat(result.ran()).isFalse();
        assertThat(result.upsertedRows()).isZero();
        assertThat(providerRequests()).isZero();
        assertThat(storedRowCount()).isZero();
      } finally {
        lock(other, "SELECT pg_advisory_unlock(?)");
      }
    }
    assertThat(job.run().ran()).isTrue();
  }

  @Test
  void concurrentRunsDoNotOverlap() throws Exception {
    for (String base : currencies.all()) {
      FRANKFURTER.stubFor(
          WireMock.get(urlPathEqualTo("/v2/rates"))
              .withQueryParam("base", com.github.tomakehurst.wiremock.client.WireMock.equalTo(base))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "application/json")
                      .withBody(verifiedBody(base, quotesFor(base), today()))
                      .withFixedDelay(150)));
    }
    int threads = 4;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<Result>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        Callable<Result> run =
            () -> {
              start.await();
              return job.run();
            };
        futures.add(pool.submit(run));
      }
      start.countDown();
      int ran = 0;
      for (Future<Result> future : futures) {
        if (future.get(60, TimeUnit.SECONDS).ran()) {
          ran++;
        }
      }
      assertThat(ran).isEqualTo(1);
      assertThat(providerRequests()).isEqualTo(currencies.all().size());
      assertThat(storedRowCount()).isEqualTo(pairCount());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void theRateAgeGaugeFollowsTheNewestStoredDate() {
    assertThat(meters.get("vitadelta.fx.rate.age").gauge().value()).isNaN();

    storeRate("TRY", "USD", today().minusDays(3), TRY_USD);
    storeRate("TRY", "EUR", today().minusDays(4), TRY_EUR);

    assertThat(meters.get("vitadelta.fx.rate.age").gauge().value()).isEqualTo(3.0);
  }

  private static boolean lock(Connection connection, String sql) throws Exception {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, JobLockKeys.FX_REFRESH);
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next() && rs.getBoolean(1);
      }
    }
  }
}
