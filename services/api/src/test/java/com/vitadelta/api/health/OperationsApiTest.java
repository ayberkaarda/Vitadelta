package com.vitadelta.api.health;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.vitadelta.api.fx.FxRefreshJob;
import com.vitadelta.api.identity.ContractSchemas;
import com.vitadelta.api.identity.Role;
import com.vitadelta.api.recommendation.RecommendationApiTestBase;
import com.vitadelta.api.recommendation.ScoringGateway;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;

/**
 * {@code GET /admin/health}, {@code GET /admin/outbox/stats}, {@code GET /health} and the {@code
 * engine} of {@code GET /weights/defaults} against a running server with the scoring service
 * replaced by a stub. The engine retry interval is shortened so that the stub answers are read on
 * the next request.
 */
@TestPropertySource(properties = "vitadelta.health.engine-retry-interval=1ms")
class OperationsApiTest extends RecommendationApiTestBase {

  private static final String HEALTH_PATH = "/internal/v1/health";

  private static final String HEALTH_ANSWER =
      "{\"status\":\"UP\",\"config\":{\"diminishing_beta\":0.35,\"utility_ceiling\":1.5,"
          + "\"comparison_epsilon\":1.0e-9,\"verdict_worth_it_max_ratio\":1.0,"
          + "\"verdict_marginal_max_ratio\":1.5}}";

  private static final List<String> JOB_NAMES =
      List.of(
          "OUTBOX_PROCESSOR",
          "FX_REFRESH",
          "SUBSCRIPTION_EXPIRY",
          "HOUSEKEEPING_IDENTITY",
          "HOUSEKEEPING_ALERTS");

  @Autowired private FxRefreshJob fxRefresh;

  @BeforeEach
  @AfterEach
  void removeRates() {
    jdbc.sql("DELETE FROM fx_rate").update();
  }

  private void scoringHealthAnswers(int status) {
    scoring()
        .stubFor(
            WireMock.get(urlEqualTo(HEALTH_PATH))
                .willReturn(
                    aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(status == 200 ? HEALTH_ANSWER : "{}")));
  }

  private void storeFreshRate() {
    jdbc.sql(
            "INSERT INTO fx_rate (base_currency, quote_currency, as_of, rate, fetched_at, source)"
                + " VALUES ('EUR', 'GBP', ?, 0.83700000, now(), 'FRANKFURTER')")
        .param(LocalDate.now(ZoneOffset.UTC))
        .update();
  }

  private JsonNode adminHealth() {
    EntityExchangeResult<String> result = get("/api/v1/admin/health", user(Role.ADMIN).token());
    assertStatus(result, 200);
    ContractSchemas.assertConforms("AdminHealth", result.getResponseBody());
    return json(result);
  }

  @Test
  void weightDefaultsCarryTheEngineParametersUnrounded() {
    scoringHealthAnswers(200);
    EntityExchangeResult<String> result = get("/api/v1/weights/defaults", null);
    assertStatus(result, 200);
    ContractSchemas.assertConforms("DefaultWeights", result.getResponseBody());
    assertThat(json(result).get("engine").toString())
        .isEqualTo(
            "{\"diminishingBeta\":0.35,\"utilityCeiling\":1.5,\"verdictWorthItMaxRatio\":1.0,"
                + "\"verdictMarginalMaxRatio\":1.5}");
    assertThat(json(result).get("categories").toString())
        .isEqualTo("{\"VITAMIN\":1.0,\"MINERAL\":1.0,\"COENZYME\":0.8,\"OTHER\":0.5}");

    // Kept after the scoring service went down; never 503.
    scoringHealthAnswers(500);
    EntityExchangeResult<String> later = get("/api/v1/weights/defaults", null);
    assertStatus(later, 200);
    assertThat(json(later).get("engine").get("utilityCeiling").toString()).isEqualTo("1.5");
  }

  @Test
  void everyComponentUpMeansOverallUp() {
    scoringHealthAnswers(200);
    storeFreshRate();
    JsonNode body = adminHealth();
    assertThat(body.get("status").asString()).isEqualTo("UP");
    JsonNode components = body.get("components");
    assertThat(components.get("scoring").toString()).isEqualTo("{\"status\":\"UP\"}");
    assertThat(components.get("database").toString()).isEqualTo("{\"status\":\"UP\"}");
    JsonNode fx = components.get("fx");
    assertThat(fx.get("status").asString()).isEqualTo("UP");
    assertThat(fx.get("newestAsOf").asString()).isEqualTo(LocalDate.now(ZoneOffset.UTC).toString());
    assertThat(fx.get("rateAgeDays").asInt()).isZero();
    assertThat(fx.get("stale").asBoolean()).isFalse();
    assertThat(components.get("outbox").toString())
        .isEqualTo(
            "{\"pending\":0,\"failed\":0,\"deadLetter\":0,\"oldestPendingAt\":null,"
                + "\"status\":\"UP\"}");
    List<String> names = new ArrayList<>();
    body.get("jobs").forEach(job -> names.add(job.get("name").asString()));
    assertThat(names).containsExactlyElementsOf(JOB_NAMES);
    assertThat(body.get("checkedAt").asString()).isNotBlank();
  }

  @Test
  void aDownScoringServiceOrMissingRatesDegradeButStillAnswer200() {
    scoringHealthAnswers(500);
    JsonNode body = adminHealth();
    assertThat(body.get("components").get("scoring").get("status").asString()).isEqualTo("DOWN");
    assertThat(body.get("components").get("database").get("status").asString()).isEqualTo("UP");
    JsonNode fx = body.get("components").get("fx");
    assertThat(fx.get("status").asString()).isEqualTo("DEGRADED");
    assertThat(fx.get("newestAsOf").isNull()).isTrue();
    assertThat(fx.get("rateAgeDays").isNull()).isTrue();
    assertThat(body.get("status").asString()).isEqualTo("DEGRADED");
  }

  @Test
  void anOpenCircuitIsDownWithoutACall() {
    scoringHealthAnswers(200);
    circuitBreakers.circuitBreaker(ScoringGateway.INSTANCE).transitionToOpenState();
    JsonNode body = adminHealth();
    assertThat(body.get("components").get("scoring").get("status").asString()).isEqualTo("DOWN");
    assertThat(scoring().findAll(getRequestedFor(urlEqualTo(HEALTH_PATH)))).isEmpty();
  }

  @Test
  void jobRunsReportTheLastRunThatObtainedTheLock() {
    // The provider URL of this context is a discard port: every base fails, the run is FAILED.
    FxRefreshJob.Result result = fxRefresh.run();
    JsonNode fxJob = adminHealth().get("jobs").get(1);
    assertThat(fxJob.get("name").asString()).isEqualTo("FX_REFRESH");
    if (result.ran()) {
      assertThat(fxJob.get("lastOutcome").asString()).isEqualTo("FAILED");
      assertThat(fxJob.get("lastRunAt").asString()).isNotBlank();
    }
    JsonNode outboxJob = adminHealth().get("jobs").get(0);
    assertThat(outboxJob.get("lastRunAt").isNull()).isTrue();
    assertThat(outboxJob.get("lastOutcome").isNull()).isTrue();
  }

  @Test
  void adminOperationsNeedTheAdminRole() {
    String user = user(Role.USER).token();
    for (String path : List.of("/api/v1/admin/health", "/api/v1/admin/outbox/stats")) {
      assertProblem(get(path, null), 401, "unauthorized");
      assertProblem(get(path, user), 403, "forbidden");
    }
    EntityExchangeResult<String> stats =
        get("/api/v1/admin/outbox/stats", user(Role.ADMIN).token());
    assertStatus(stats, 200);
    ContractSchemas.assertConforms("OutboxStats", stats.getResponseBody());
    assertThat(json(stats).toString())
        .isEqualTo("{\"pending\":0,\"failed\":0,\"deadLetter\":0,\"oldestPendingAt\":null}");
  }

  @Test
  void publicHealthShowsTheOverallStatusOnly() {
    scoringHealthAnswers(200);
    EntityExchangeResult<String> result = get("/api/v1/health", null);
    assertStatus(result, 200);
    ContractSchemas.assertConforms("Health", result.getResponseBody());
    JsonNode body = json(result);
    assertThat(body.size()).isEqualTo(1);
    assertThat(body.get("status").asString()).isIn("UP", "DEGRADED");
  }
}
