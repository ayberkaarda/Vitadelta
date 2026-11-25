package com.vitadelta.api.fx;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.Fault;
import com.vitadelta.api.common.jobs.JobLockKeys;
import com.vitadelta.api.common.jobs.JobRunRegistry;
import com.vitadelta.api.fx.FxRefreshJob.Result;
import com.vitadelta.api.identity.ContractSchemas;
import com.vitadelta.api.identity.Role;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;

/** {@link FxRefreshJob} against the WireMock provider and the real {@code fx_rate} table. */
@ExtendWith(OutputCaptureExtension.class)
class FxRefreshIntegrationTest extends FxApiTestBase {

  @Autowired DataSource dataSource;
  @Autowired MeterRegistry meters;
  @Autowired JobRunRegistry jobRuns;

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

  private double unsupported(String currency) {
    var counter =
        meters.find("vitadelta.fx.refresh.unsupported").tag("currency", currency).counter();
    return counter == null ? 0 : counter.count();
  }

  /** Stubs the answer to the request for {@code base} with exactly {@code quotes}. */
  private void stubRequest(String base, SortedSet<String> quotes, int status, String body) {
    FRANKFURTER.stubFor(
        WireMock.get(urlPathEqualTo("/v2/rates"))
            .withQueryParam("base", WireMock.equalTo(base))
            .withQueryParam("quotes", WireMock.equalTo(String.join(",", quotes)))
            .withQueryParam("providers", WireMock.equalTo("ECB"))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));
  }

  private SortedSet<String> without(SortedSet<String> quotes, String currency) {
    SortedSet<String> rest = new TreeSet<>(quotes);
    rest.remove(currency);
    return rest;
  }

  /**
   * Every pair is stored except those from or to {@link #EXTRA}; the extra has no rate.
   *
   * @param extraBaseUnsupported whether the provider rejected the extra as an invalid base (an
   *     unsupported base) rather than answering it with an empty list (a failed base)
   */
  private void assertEveryPairButTheExtraIsStored(Result result, boolean extraBaseUnsupported) {
    assertThat(result.ran()).isTrue();
    if (extraBaseUnsupported) {
      // Contract FxRefreshResult: an unsupported base is not a failed base.
      assertThat(result.failedBases()).isEmpty();
      assertThat(result.unsupportedBases()).containsExactly(EXTRA);
      assertThat(result.succeeded()).as("an unsupported base does not fail the run").isTrue();
    } else {
      assertThat(result.failedBases()).containsExactly(EXTRA);
      assertThat(result.unsupportedBases()).isEmpty();
      assertThat(result.succeeded()).isFalse();
    }
    int others = currencies.all().size() - 1;
    assertThat(result.upsertedRows()).isEqualTo(others * (others - 1));
    for (String base : currencies.all()) {
      for (String quote : quotesFor(base)) {
        boolean extra = base.equals(EXTRA) || quote.equals(EXTRA);
        assertThat(repository.newest(base, quote).isPresent())
            .as(base + ">" + quote)
            .isEqualTo(!extra);
      }
    }
    JsonNode items = json(get("/api/v1/fx/currencies", null)).get("items");
    for (JsonNode item : items) {
      String code = item.get("code").asString();
      assertThat(item.get("asOf").isNull()).as(code).isEqualTo(code.equals(EXTRA));
    }
    assertThat(json(get("/api/v1/fx/rates?base=TRY", null)).get("rates").toString())
        .doesNotContain(EXTRA);
    JsonNode extraPlans = json(get("/api/v1/plans?displayCurrency=" + EXTRA, null));
    assertThat(extraPlans.get("meta").get("fx").get("status").asString()).isEqualTo("UNAVAILABLE");
    JsonNode usdPlans = json(get("/api/v1/plans?displayCurrency=USD", null));
    assertThat(usdPlans.get("meta").get("fx").get("status").asString()).isEqualTo("OK");
  }

  @Test
  void aQuoteTheProviderLeavesOutIsDroppedAndTheOtherQuotesAreStored() {
    double before = unsupported(EXTRA);
    for (String base : currencies.all()) {
      SortedSet<String> quotes = quotesFor(base);
      if (base.equals(EXTRA)) {
        stubRequest(base, quotes, 200, "[]");
        continue;
      }
      SortedSet<String> published = without(quotes, EXTRA);
      // The verified ECB behaviour: an unpublished ISO code is left out of a 200 answer.
      stubRequest(base, quotes, 200, verifiedBody(base, published, today()));
      stubRequest(base, published, 200, verifiedBody(base, published, today()));
    }

    Result result = job.run();

    assertEveryPairButTheExtraIsStored(result, false);
    assertThat(unsupported(EXTRA) - before).isEqualTo(currencies.all().size() - 1);
    // One request per base plus one retry per base that listed the extra currency.
    assertThat(providerRequests()).isEqualTo(2 * currencies.all().size() - 1);
  }

  @Test
  void anInvalidCurrencyRejectionIsRetriedWithoutItOrQuoteByQuote() {
    double before = unsupported(EXTRA);
    String invalid = "{\"status\":422,\"message\":\"invalid currency: " + EXTRA + "\"}";
    for (String base : currencies.all()) {
      SortedSet<String> quotes = quotesFor(base);
      if (base.equals(EXTRA)) {
        stubRequest(base, quotes, 422, invalid);
        continue;
      }
      if (base.equals("TRY")) {
        // A rejection that names no currency: every quote is requested on its own.
        stubRequest(base, quotes, 422, "{\"status\":422,\"message\":\"unsupported quotes\"}");
        for (String quote : quotes) {
          SortedSet<String> single = new TreeSet<>(Set.of(quote));
          if (quote.equals(EXTRA)) {
            stubRequest(base, single, 422, invalid);
          } else {
            stubRequest(base, single, 200, verifiedBody(base, single, today()));
          }
        }
        continue;
      }
      SortedSet<String> published = without(quotes, EXTRA);
      stubRequest(base, quotes, 422, invalid);
      stubRequest(base, published, 200, verifiedBody(base, published, today()));
    }

    Result result = job.run();

    assertEveryPairButTheExtraIsStored(result, true);
    assertThat(lookup.newest("TRY", "USD").orElseThrow().rate()).isEqualTo(TRY_USD);
    // Dropped as a quote of every other base and once as a base of its own.
    assertThat(unsupported(EXTRA) - before).isEqualTo(currencies.all().size());
  }

  /**
   * The verified provider answer for a base it does not know ({@code 422 invalid currency: X},
   * 2026-11-25): that base is reported as unsupported (not failed) with an operator warning and the
   * unsupported metric; the run, the job-run registry and the admin refresh stay successful, the
   * admin health lists the base, and nothing converts from it.
   */
  @Test
  void aBaseTheProviderNamesInvalidIsUnsupportedAndTheRunSucceeds(CapturedOutput output) {
    String invalid = "{\"status\":422,\"message\":\"invalid currency: " + EXTRA + "\"}";
    for (String base : currencies.all()) {
      SortedSet<String> quotes = quotesFor(base);
      if (base.equals(EXTRA)) {
        stubRequest(base, quotes, 422, invalid);
        continue;
      }
      SortedSet<String> published = without(quotes, EXTRA);
      stubRequest(base, quotes, 422, invalid);
      stubRequest(base, published, 200, verifiedBody(base, published, today()));
    }
    double before = unsupported(EXTRA);

    Result result = job.run();

    assertEveryPairButTheExtraIsStored(result, true);
    assertThat(unsupported(EXTRA) - before).isEqualTo(currencies.all().size());
    assertThat(job.lastRun().orElseThrow().succeeded()).isTrue();
    assertThat(job.lastRun().orElseThrow().unsupportedBases()).containsExactly(EXTRA);
    assertThat(jobRuns.lastRun(JobLockKeys.FX_REFRESH).orElseThrow().lastOutcome())
        .isEqualTo(JobRunRegistry.Outcome.SUCCEEDED);
    var health = get("/api/v1/admin/health", token(Role.ADMIN));
    ContractSchemas.assertConforms("AdminHealth", health.getResponseBody());
    JsonNode fx = json(health).get("components").get("fx");
    assertThat(fx.get("unsupportedBases").toString()).isEqualTo("[\"" + EXTRA + "\"]");
    JsonNode jobs = json(health).get("jobs");
    JsonNode fxJob = null;
    for (JsonNode job : jobs) {
      if (job.get("name").asString().equals("FX_REFRESH")) {
        fxJob = job;
      }
    }
    assertThat(fxJob).isNotNull();
    assertThat(fxJob.get("lastOutcome").asString()).isEqualTo("SUCCEEDED");
    assertThat(output.getAll())
        .contains("the provider does not publish base " + EXTRA)
        .contains("VITADELTA_FX_EXTRA_CURRENCIES");
    assertThat(json(get("/api/v1/fx/rates?base=" + EXTRA, null)).get("rates").isEmpty())
        .as("no conversion from the unsupported base")
        .isTrue();
  }

  /**
   * A 422 that only mentions the base ({@code TRY again later}) or names another currency is not
   * the verified "invalid currency" answer for that base: the base fails, the run fails, and the
   * job-run registry and {@code GET /admin/health} report {@code FAILED}.
   */
  @ParameterizedTest
  @ValueSource(strings = {"TRY again later", "invalid currency: XAU", "invalid currency: TRY, EUR"})
  void aRejectionThatIsNotTheVerifiedAnswerForTheBaseFailsTheBaseAndTheRun(String message) {
    stubAllBases(today());
    stubBase("TRY", 422, "{\"status\":422,\"message\":\"" + message + "\"}");

    Result result = job.run();

    assertThat(result.failedBases()).containsExactly("TRY");
    assertThat(result.unsupportedBases()).isEmpty();
    assertThat(result.succeeded()).isFalse();
    assertThat(jobRuns.lastRun(JobLockKeys.FX_REFRESH).orElseThrow().lastOutcome())
        .isEqualTo(JobRunRegistry.Outcome.FAILED);
    JsonNode health = json(get("/api/v1/admin/health", token(Role.ADMIN)));
    for (JsonNode run : health.get("jobs")) {
      if (run.get("name").asString().equals("FX_REFRESH")) {
        assertThat(run.get("lastOutcome").asString()).isEqualTo("FAILED");
      }
    }
    assertThat(repository.newest("TRY", "USD")).isEmpty();
    assertThat(repository.newest("USD", "TRY")).isPresent();
  }

  @Test
  void theRunFailsWhenNoBaseAtAllCouldBeRefreshed() {
    for (String base : currencies.all()) {
      stubBase(base, 422, "{\"status\":422,\"message\":\"invalid currency: " + base + "\"}");
    }

    Result result = job.run();

    assertThat(result.upsertedRows()).isZero();
    assertThat(result.failedBases()).isEmpty();
    assertThat(result.unsupportedBases()).containsExactlyElementsOf(currencies.all());
    assertThat(result.succeeded()).as("no base could be refreshed at all").isFalse();
    assertThat(jobRuns.lastRun(JobLockKeys.FX_REFRESH).orElseThrow().lastOutcome())
        .isEqualTo(JobRunRegistry.Outcome.FAILED);
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
