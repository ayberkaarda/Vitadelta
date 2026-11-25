package com.vitadelta.api.fx;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.common.jobs.JobLockKeys;
import com.vitadelta.api.common.jobs.JobRunRegistry;
import com.vitadelta.api.identity.ContractSchemas;
import com.vitadelta.api.identity.Role;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;

/** {@code GET /fx/currencies}, {@code GET /fx/rates} and {@code POST /admin/fx/refresh}. */
class FxEndpointsIntegrationTest extends FxApiTestBase {

  @Autowired DataSource dataSource;
  @Autowired JobRunRegistry jobRuns;
  @Autowired FxFreshness freshness;

  @Test
  void currenciesListTheCountryAndExtraCurrenciesWithTheirNewestDate() {
    storeRate("TRY", "USD", today().minusDays(1), TRY_USD);
    storeRate("EUR", "TRY", today().minusDays(8), new BigDecimal("47.90000000"));

    EntityExchangeResult<String> result = get("/api/v1/fx/currencies", null);

    assertStatus(result, 200);
    ContractSchemas.assertConforms("FxCurrencyList", result.getResponseBody());
    JsonNode items = json(result).get("items");
    assertThat(ids(items, "code")).containsExactlyElementsOf(currencies.all());
    assertThat(ids(items, "code")).contains("CHF", "EUR", "GBP", "TRY", "USD");
    for (JsonNode item : items) {
      String code = item.get("code").asString();
      switch (code) {
        case "TRY", "USD" -> {
          assertThat(item.get("asOf").asString()).isEqualTo(today().minusDays(1).toString());
          assertThat(item.get("stale").asBoolean()).isFalse();
        }
        case "EUR" -> {
          assertThat(item.get("asOf").asString()).isEqualTo(today().minusDays(8).toString());
          assertThat(item.get("stale").asBoolean()).isTrue();
        }
        default -> {
          assertThat(item.get("asOf").isNull()).as(code).isTrue();
          assertThat(item.get("stale").asBoolean()).isFalse();
        }
      }
    }
  }

  @Test
  void ratesAreTheNewestPerQuoteWithTheirStoredEightDecimals() {
    storeRate("TRY", "USD", today().minusDays(3), new BigDecimal("0.02000000"));
    storeRate("TRY", "USD", today().minusDays(1), TRY_USD);
    storeRate("TRY", "EUR", today().minusDays(6), TRY_EUR);
    storeRate("USD", "TRY", today().minusDays(1), new BigDecimal("41.43000000"));

    EntityExchangeResult<String> result = get("/api/v1/fx/rates?base=TRY", null);

    assertStatus(result, 200);
    ContractSchemas.assertConforms("FxRateList", result.getResponseBody());
    assertThat(result.getResponseBody())
        .isEqualTo(
            "{\"base\":\"TRY\",\"rates\":["
                + "{\"quote\":\"EUR\",\"rate\":0.02087500,\"asOf\":\""
                + today().minusDays(6)
                + "\",\"source\":\"FRANKFURTER\",\"stale\":true},"
                + "{\"quote\":\"USD\",\"rate\":0.02413700,\"asOf\":\""
                + today().minusDays(1)
                + "\",\"source\":\"FRANKFURTER\",\"stale\":false}]}");
  }

  @Test
  void aSupportedBaseWithoutRatesHasAnEmptyList() {
    EntityExchangeResult<String> result = get("/api/v1/fx/rates?base=" + EXTRA, null);

    assertStatus(result, 200);
    ContractSchemas.assertConforms("FxRateList", result.getResponseBody());
    assertThat(result.getResponseBody()).isEqualTo("{\"base\":\"CHF\",\"rates\":[]}");
  }

  @Test
  void stalenessFollowsTheInjectedClockAfterFiveDays() {
    LocalDate asOf = today();
    storeRate("TRY", "USD", asOf, TRY_USD);

    CLOCK.advance(Duration.ofDays(5));
    assertThat(
            json(get("/api/v1/fx/rates?base=TRY", null))
                .get("rates")
                .get(0)
                .get("stale")
                .asBoolean())
        .isFalse();
    assertThat(lookup.isStale(asOf)).isFalse();

    CLOCK.advance(Duration.ofDays(1));
    JsonNode rate = json(get("/api/v1/fx/rates?base=TRY", null)).get("rates").get(0);
    assertThat(rate.get("stale").asBoolean()).isTrue();
    assertThat(rate.get("asOf").asString()).isEqualTo(asOf.toString());
    assertThat(lookup.isStale(asOf)).isTrue();
  }

  @Test
  void unsupportedAndMalformedBasesAreRejected() {
    assertProblem(get("/api/v1/fx/rates?base=XYZ", null), 422, "fx_unsupported_currency");
    assertProblem(get("/api/v1/fx/rates?base=try", null), 422, "validation_failed");
    assertProblem(get("/api/v1/fx/rates", null), 422, "validation_failed");
  }

  @Test
  void theAdminRefreshRunsTheJobAndReturnsTheUpsertedRows() {
    stubAllBases(today());

    EntityExchangeResult<String> result = post("/api/v1/admin/fx/refresh", "", token(Role.ADMIN));

    assertStatus(result, 200);
    ContractSchemas.assertConforms("FxRefreshResult", result.getResponseBody());
    assertThat(json(result).get("upsertedRows").asInt()).isEqualTo(pairCount());
    assertThat(storedRowCount()).isEqualTo(pairCount());
    assertThat(jobRuns.lastRun(JobLockKeys.FX_REFRESH).orElseThrow().lastOutcome())
        .isEqualTo(JobRunRegistry.Outcome.SUCCEEDED);
  }

  @Test
  void theAdminRefreshReportsZeroRowsWhenTheProviderFails() {
    EntityExchangeResult<String> result = post("/api/v1/admin/fx/refresh", "", token(Role.ADMIN));

    assertStatus(result, 200);
    ContractSchemas.assertConforms("FxRefreshResult", result.getResponseBody());
    assertThat(result.getResponseBody())
        .isEqualTo(
            "{\"upsertedRows\":0,\"failedBases\":[\""
                + String.join("\",\"", currencies.all())
                + "\"],\"unsupportedBases\":[]}");
    assertThat(job.lastRun().orElseThrow().succeeded()).isFalse();
    assertThat(jobRuns.lastRun(JobLockKeys.FX_REFRESH).orElseThrow().lastOutcome())
        .as("a refresh with failed bases is a failed job run")
        .isEqualTo(JobRunRegistry.Outcome.FAILED);
  }

  @Test
  void theAdminRefreshIsAConflictWhileTheJobHoldsItsLock() throws Exception {
    try (Connection other = dataSource.getConnection();
        PreparedStatement lock = other.prepareStatement("SELECT pg_advisory_lock(?)")) {
      lock.setLong(1, JobLockKeys.FX_REFRESH);
      lock.execute();
      try {
        assertProblem(
            post("/api/v1/admin/fx/refresh", "", token(Role.ADMIN)), 409, "conflict_retry");
        assertThat(providerRequests()).isZero();
      } finally {
        try (PreparedStatement unlock = other.prepareStatement("SELECT pg_advisory_unlock(?)")) {
          unlock.setLong(1, JobLockKeys.FX_REFRESH);
          unlock.execute();
        }
      }
    }
  }

  @Test
  void freshnessReportsAgeAndStaleness() {
    // The application context is shared: an earlier test may have run the job.
    List<String> unsupported =
        job.lastRun().map(FxRefreshJob.LastRun::unsupportedBases).orElse(List.of());
    assertThat(freshness.current())
        .isEqualTo(new FxFreshness.Snapshot(FxFreshness.DEGRADED, null, null, false, unsupported));
    storeRate("TRY", "USD", today().minusDays(2), TRY_USD);
    assertThat(freshness.current())
        .isEqualTo(
            new FxFreshness.Snapshot(FxFreshness.UP, today().minusDays(2), 2, false, unsupported));
    storeRate("TRY", "EUR", today().minusDays(9), TRY_EUR);
    jdbc.sql("DELETE FROM fx_rate WHERE quote_currency = 'USD'").update();
    assertThat(freshness.current())
        .isEqualTo(
            new FxFreshness.Snapshot(
                FxFreshness.DEGRADED, today().minusDays(9), 9, true, unsupported));
  }

  @Test
  void theAdminRefreshRequiresTheAdminRole() {
    assertProblem(post("/api/v1/admin/fx/refresh", "", null), 401, "unauthorized");
    assertProblem(post("/api/v1/admin/fx/refresh", "", token(Role.USER)), 403, "forbidden");
    assertThat(providerRequests()).isZero();
  }

  @Test
  void theFxListsArePublic() {
    assertStatus(get("/api/v1/fx/currencies", null), 200);
    assertStatus(get("/api/v1/fx/rates?base=TRY", null), 200);
  }
}
