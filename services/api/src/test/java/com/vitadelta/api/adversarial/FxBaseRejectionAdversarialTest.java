package com.vitadelta.api.adversarial;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.vitadelta.api.adversarial.AttackClient.Resp;
import com.vitadelta.api.fx.FxApiTestBase;
import com.vitadelta.api.identity.ContractSchemas;
import com.vitadelta.api.identity.Role;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A rate provider that rejects base currencies (the verified Frankfurter answer {@code 422
 * {"status":422,"message":"invalid currency: CHF"}}), attacked as a hostile provider: it rejects
 * one base, every base, alternates between runs, or names currencies that were not requested. The
 * stored rates of a rejected base are never removed or invented, {@code failedBases} lists exactly
 * the bases that were not refreshed, and the job outcome in {@code GET /admin/health} follows the
 * contract ({@code JobRun.lastOutcome}: "an FX run with at least one failed base also counts as
 * {@code FAILED}").
 */
class FxBaseRejectionAdversarialTest extends FxApiTestBase {

  private static final String API = "/api/v1";

  private AttackClient raw;
  private String admin;

  @BeforeEach
  void prepareProvider() {
    raw = new AttackClient(port);
    admin = token(Role.ADMIN);
  }

  /** Every request for {@code base} (any quotes) is answered {@code 422} with {@code message}. */
  private void rejectBase(String base, String message) {
    FRANKFURTER.stubFor(
        WireMock.get(urlPathEqualTo("/v2/rates"))
            .withQueryParam("base", equalTo(base))
            .atPriority(1)
            .willReturn(
                aResponse()
                    .withStatus(422)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"status\":422,\"message\":\"" + message + "\"}")));
  }

  private JsonNode refresh() {
    Resp response =
        raw.req("POST", API + "/admin/fx/refresh")
            .ip(AdversarialIntegrationTest.randomIp())
            .bearer(admin)
            .send();
    assertThat(response.status()).as(response.toString()).isEqualTo(200);
    ContractSchemas.assertConforms("FxRefreshResult", response.body());
    AttackClient.assertNoLeak(response);
    return response.json();
  }

  private static List<String> failedBases(JsonNode result) {
    List<String> bases = new ArrayList<>();
    result.get("failedBases").forEach(b -> bases.add(b.asString()));
    return bases;
  }

  /** {@code jobs[FX_REFRESH].lastOutcome} of {@code GET /admin/health}. */
  private String fxOutcome() {
    Resp health =
        raw.req("GET", API + "/admin/health")
            .ip(AdversarialIntegrationTest.randomIp())
            .bearer(admin)
            .send();
    assertThat(health.status()).as(health.toString()).isEqualTo(200);
    for (JsonNode job : health.json().get("jobs")) {
      if (job.get("name").asString().equals("FX_REFRESH")) {
        return job.get("lastOutcome").isNull() ? null : job.get("lastOutcome").asString();
      }
    }
    throw new AssertionError("no FX_REFRESH job in " + health.body());
  }

  private List<String> rowsOfBase(String base) {
    return storedRows().stream().filter(r -> r.startsWith(base + ">")).toList();
  }

  /** Whether every pair of {@code base} has a stored row dated {@code date} (rows are per date). */
  private boolean refreshedOn(String base, LocalDate date) {
    List<String> rows = rowsOfBase(base);
    return quotesFor(base).stream()
        .allMatch(
            q -> rows.stream().anyMatch(r -> r.startsWith(base + ">" + q + "@" + date + "=")));
  }

  private int quoteCount() {
    return currencies.all().size() - 1;
  }

  @Test
  void aRejectedBaseIsListedAsFailedAndGetsNoRate() {
    LocalDate date = today().minusDays(1);
    stubAllBases(date);
    rejectBase(EXTRA, "invalid currency: " + EXTRA);
    JsonNode result = refresh();
    assertThat(failedBases(result)).containsExactly(EXTRA);
    int refreshedBases = currencies.all().size() - 1;
    assertThat(result.get("upsertedRows").asInt()).isEqualTo(refreshedBases * quoteCount());
    assertThat(rowsOfBase(EXTRA)).as("no rate is stored for the rejected base").isEmpty();
    for (String base : currencies.all()) {
      if (!base.equals(EXTRA)) {
        assertThat(rowsOfBase(base)).as(base).hasSize(quoteCount());
        assertThat(storedRows()).contains(base + ">" + EXTRA + "@" + date + "=" + rateText(base));
      }
    }
    Resp rates =
        raw.req("GET", API + "/fx/rates?base=" + EXTRA)
            .ip(AdversarialIntegrationTest.randomIp())
            .send();
    assertThat(rates.status()).as(rates.toString()).isEqualTo(200);
    assertThat(rates.json().get("rates").isEmpty()).as(rates.body()).isTrue();
  }

  private static String rateText(String base) {
    return stubRate(base, EXTRA).toPlainString() + "|FRANKFURTER";
  }

  /**
   * Contract {@code JobRun.lastOutcome}: a run with at least one failed base counts as {@code
   * FAILED}; {@code FxRefreshResult.failedBases}: the bases whose provider call failed. A run that
   * lists a failed base must therefore never be reported as {@code SUCCEEDED}.
   */
  @Test
  void aRunThatListsAFailedBaseIsNeverReportedAsSucceeded() {
    stubAllBases(today().minusDays(1));
    JsonNode clean = refresh();
    assertThat(failedBases(clean)).isEmpty();
    assertThat(fxOutcome()).isEqualTo("SUCCEEDED");

    rejectBase(EXTRA, "invalid currency: " + EXTRA);
    JsonNode rejected = refresh();
    assertThat(failedBases(rejected)).containsExactly(EXTRA);
    assertThat(fxOutcome())
        .as("lastOutcome after a run with failedBases %s", failedBases(rejected))
        .isEqualTo("FAILED");

    // A hostile provider can name a supported base in any 422 text ("TRY again later").
    FRANKFURTER.resetAll();
    stubAllBases(today().minusDays(1));
    rejectBase("TRY", "TRY again later");
    JsonNode hidden = refresh();
    assertThat(failedBases(hidden)).containsExactly("TRY");
    assertThat(fxOutcome())
        .as("a country currency left unrefreshed must be visible to the operator")
        .isEqualTo("FAILED");
  }

  @Test
  void aProviderThatRejectsEveryBaseFailsTheRunAndKeepsEveryStoredRate() {
    stubAllBases(today().minusDays(2));
    refresh();
    List<String> stored = storedRows();
    assertThat(stored).hasSize(pairCount());

    FRANKFURTER.resetAll();
    for (String base : currencies.all()) {
      rejectBase(base, "invalid currency: " + base);
    }
    JsonNode result = refresh();
    assertThat(failedBases(result)).containsExactlyElementsOf(currencies.all());
    assertThat(result.get("upsertedRows").asInt()).isZero();
    assertThat(storedRows()).as("stored rates stay in use").isEqualTo(stored);
    assertThat(fxOutcome()).isEqualTo("FAILED");

    // One 422 naming every supported currency at once.
    FRANKFURTER.resetAll();
    for (String base : currencies.all()) {
      rejectBase(base, "invalid currency: " + String.join(", ", currencies.all()));
    }
    JsonNode all = refresh();
    assertThat(failedBases(all)).containsExactlyElementsOf(currencies.all());
    assertThat(storedRows()).isEqualTo(stored);
    assertThat(fxOutcome()).isEqualTo("FAILED");
  }

  @Test
  void aRejectionThatAlternatesBetweenRunsNeverLosesOrInventsRates() {
    LocalDate first = today().minusDays(3);
    stubAllBases(first);
    rejectBase(EXTRA, "invalid currency: " + EXTRA);
    assertThat(failedBases(refresh())).containsExactly(EXTRA);
    assertThat(rowsOfBase(EXTRA)).isEmpty();

    // The provider accepts the base on the next run: its rates appear normally.
    FRANKFURTER.resetAll();
    LocalDate second = today().minusDays(2);
    stubAllBases(second);
    JsonNode accepted = refresh();
    assertThat(failedBases(accepted)).isEmpty();
    assertThat(fxOutcome()).isEqualTo("SUCCEEDED");
    List<String> extraRows = rowsOfBase(EXTRA);
    assertThat(extraRows).hasSize(quoteCount());
    assertThat(refreshedOn(EXTRA, second)).isTrue();
    List<String> usdRows = rowsOfBase("USD");

    // Then it rejects the base again, and another one: their stored rates stay as they were.
    FRANKFURTER.resetAll();
    LocalDate third = today().minusDays(1);
    stubAllBases(third);
    rejectBase(EXTRA, "invalid currency: " + EXTRA);
    rejectBase("USD", "invalid currency: USD");
    JsonNode again = refresh();
    assertThat(failedBases(again)).containsExactly(EXTRA, "USD");
    assertThat(rowsOfBase(EXTRA)).isEqualTo(extraRows);
    assertThat(rowsOfBase("USD")).isEqualTo(usdRows);
    assertThat(refreshedOn("TRY", third)).isTrue();
    assertThat(refreshedOn(EXTRA, third)).as("no rate is invented for a rejected base").isFalse();
    assertThat(refreshedOn("USD", third)).as("no rate is invented for a rejected base").isFalse();
  }

  @Test
  void aRejectionNamingACurrencyThatWasNotRequestedDropsNoBase() {
    LocalDate old = today().minusDays(2);
    stubAllBases(old);
    refresh();
    List<String> tryRows = rowsOfBase("TRY");

    FRANKFURTER.resetAll();
    LocalDate fresh = today().minusDays(1);
    stubAllBases(fresh);
    rejectBase("TRY", "invalid currency: XAU");
    JsonNode result = refresh();
    assertThat(failedBases(result))
        .as("a rejection about another currency fails the base, it does not drop it silently")
        .containsExactly("TRY");
    assertThat(rowsOfBase("TRY")).as("the stored TRY rates stay in use").isEqualTo(tryRows);
    assertThat(rowsOfBase("XAU")).isEmpty();
    assertThat(storedRows()).noneMatch(r -> r.contains("XAU"));
    assertThat(refreshedOn("EUR", fresh)).isTrue();
    assertThat(fxOutcome()).isEqualTo("FAILED");

    // A rejection of the EUR request that names USD (a supported quote) never drops the USD base.
    FRANKFURTER.resetAll();
    stubAllBases(fresh);
    rejectBase("EUR", "invalid currency: USD");
    JsonNode named = refresh();
    assertThat(failedBases(named)).containsExactly("EUR");
    assertThat(refreshedOn("USD", fresh)).isTrue();
    assertThat(refreshedOn("TRY", fresh)).isTrue();
    assertThat(fxOutcome()).isEqualTo("FAILED");

    // Lower-case or embedded codes are not currency names.
    FRANKFURTER.resetAll();
    stubAllBases(fresh);
    rejectBase("GBP", "invalid currency: gbp (GBPX)");
    JsonNode lower = refresh();
    assertThat(failedBases(lower)).containsExactly("GBP");
    assertThat(fxOutcome()).isEqualTo("FAILED");
  }
}
