package com.vitadelta.api.adversarial;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.vitadelta.api.adversarial.AttackClient.Resp;
import com.vitadelta.api.fx.FxApiTestBase;
import com.vitadelta.api.identity.ContractSchemas;
import com.vitadelta.api.identity.Role;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A rate provider that rejects base currencies, attacked as a hostile provider. Contract rule: a
 * base is unsupported only when the provider answers {@code 422} with the message {@code invalid
 * currency: <base>} naming exactly the requested base; it is listed in {@code unsupportedBases}
 * (not in {@code failedBases}), gets no rate and does not fail the run on its own. Any other
 * rejection (another text, another currency named, 5xx, empty answer) is a failure of that base.
 * {@code JobRun.lastOutcome}: an FX run is {@code FAILED} when at least one base failed or when no
 * base could be refreshed at all. {@code GET /admin/health} repeats the unsupported bases in {@code
 * components.fx.unsupportedBases}. Stored rates of a rejected base are never removed or invented.
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

  /** Every request for {@code base} (any quotes) is answered with {@code answer}. */
  private void answerBase(String base, ResponseDefinitionBuilder answer) {
    FRANKFURTER.stubFor(
        WireMock.get(urlPathEqualTo("/v2/rates"))
            .withQueryParam("base", equalTo(base))
            .atPriority(1)
            .willReturn(answer));
  }

  private static ResponseDefinitionBuilder json(int status, String body) {
    return aResponse()
        .withStatus(status)
        .withHeader("Content-Type", "application/json")
        .withBody(body);
  }

  /**
   * Every request for {@code base} is answered {@code 422} with the JSON string {@code message}.
   */
  private void rejectBase(String base, String message) {
    answerBase(base, json(422, "{\"status\":422,\"message\":\"" + message + "\"}"));
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

  private static List<String> list(JsonNode array) {
    assertThat(array).as("a required array").isNotNull();
    assertThat(array.isArray()).as(String.valueOf(array)).isTrue();
    List<String> values = new ArrayList<>();
    array.forEach(b -> values.add(b.asString()));
    return values;
  }

  private static List<String> failedBases(JsonNode result) {
    return list(result.get("failedBases"));
  }

  private static List<String> unsupportedBases(JsonNode result) {
    return list(result.get("unsupportedBases"));
  }

  private JsonNode health() {
    Resp health =
        raw.req("GET", API + "/admin/health")
            .ip(AdversarialIntegrationTest.randomIp())
            .bearer(admin)
            .send();
    assertThat(health.status()).as(health.toString()).isEqualTo(200);
    AttackClient.assertNoLeak(health);
    return health.json();
  }

  /** {@code jobs[FX_REFRESH].lastOutcome} of {@code GET /admin/health}. */
  private String fxOutcome() {
    JsonNode health = health();
    for (JsonNode job : health.get("jobs")) {
      if (job.get("name").asString().equals("FX_REFRESH")) {
        return job.get("lastOutcome").isNull() ? null : job.get("lastOutcome").asString();
      }
    }
    throw new AssertionError("no FX_REFRESH job in " + health);
  }

  /** {@code components.fx.unsupportedBases} of {@code GET /admin/health}. */
  private List<String> healthUnsupported() {
    return list(health().path("components").path("fx").get("unsupportedBases"));
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

  /** Asserts that {@code base} failed in the last run (not unsupported) and the run is FAILED. */
  private void assertFailedNotUnsupported(String label, JsonNode result, String base) {
    assertThat(failedBases(result)).as(label).containsExactly(base);
    assertThat(unsupportedBases(result)).as(label).isEmpty();
    assertThat(fxOutcome()).as(label).isEqualTo("FAILED");
    assertThat(healthUnsupported()).as(label).isEmpty();
  }

  @Test
  void anExactRejectionMakesTheBaseUnsupportedWithoutARateOrAFailedRun() {
    LocalDate date = today().minusDays(1);
    stubAllBases(date);
    rejectBase(EXTRA, "invalid currency: " + EXTRA);
    JsonNode result = refresh();
    assertThat(failedBases(result)).isEmpty();
    assertThat(unsupportedBases(result)).containsExactly(EXTRA);
    assertThat(fxOutcome())
        .as("unsupported bases alone do not fail the run")
        .isEqualTo("SUCCEEDED");
    assertThat(healthUnsupported()).containsExactly(EXTRA);
    int refreshedBases = currencies.all().size() - 1;
    assertThat(result.get("upsertedRows").asInt()).isEqualTo(refreshedBases * quoteCount());
    assertThat(rowsOfBase(EXTRA)).as("no rate is stored for the unsupported base").isEmpty();
    for (String base : currencies.all()) {
      if (!base.equals(EXTRA)) {
        assertThat(refreshedOn(base, date)).as(base).isTrue();
      }
    }
    Resp rates =
        raw.req("GET", API + "/fx/rates?base=" + EXTRA)
            .ip(AdversarialIntegrationTest.randomIp())
            .send();
    assertThat(rates.status()).as(rates.toString()).isEqualTo(200);
    assertThat(rates.json().get("rates").isEmpty()).as(rates.body()).isTrue();

    // The next clean run clears the health list again.
    FRANKFURTER.resetAll();
    stubAllBases(date);
    JsonNode clean = refresh();
    assertThat(unsupportedBases(clean)).isEmpty();
    assertThat(failedBases(clean)).isEmpty();
    assertThat(fxOutcome()).isEqualTo("SUCCEEDED");
    assertThat(healthUnsupported()).isEmpty();
  }

  @Test
  void aSupportedBaseNamedInAnyOtherRejectionTextIsAFailedBase() {
    stubAllBases(today().minusDays(1));
    rejectBase("TRY", "TRY again later");
    assertFailedNotUnsupported("TRY again later", refresh(), "TRY");

    FRANKFURTER.resetAll();
    stubAllBases(today().minusDays(1));
    rejectBase("TRY", "invalid currency: XAU");
    assertFailedNotUnsupported("another currency named", refresh(), "TRY");
    assertThat(storedRows()).noneMatch(r -> r.contains("XAU"));

    FRANKFURTER.resetAll();
    stubAllBases(today().minusDays(1));
    rejectBase("EUR", "invalid currency: USD");
    JsonNode quoteNamed = refresh();
    assertFailedNotUnsupported("a supported quote named", quoteNamed, "EUR");
    assertThat(refreshedOn("USD", today().minusDays(1))).as("USD base is not dropped").isTrue();

    FRANKFURTER.resetAll();
    stubAllBases(today().minusDays(1));
    rejectBase("GBP", "invalid currency: gbp");
    assertFailedNotUnsupported("lower case", refresh(), "GBP");

    FRANKFURTER.resetAll();
    stubAllBases(today().minusDays(1));
    answerBase("USD", json(503, "{\"status\":503,\"message\":\"invalid currency: USD\"}"));
    assertFailedNotUnsupported("5xx with the exact message", refresh(), "USD");

    FRANKFURTER.resetAll();
    stubAllBases(today().minusDays(1));
    answerBase("USD", json(200, "[]"));
    assertFailedNotUnsupported("empty answer", refresh(), "USD");
  }

  @Test
  void theStrictMessageParserCannotBeTalkedIntoAnUnsupportedBase() {
    String base = EXTRA;
    Map<String, ResponseDefinitionBuilder> variants = new LinkedHashMap<>();
    String exact = "invalid currency: " + base;
    variants.put("two spaces", json(422, msg("invalid currency:  " + base)));
    variants.put("leading space", json(422, msg(" " + exact)));
    variants.put("trailing space", json(422, msg(exact + " ")));
    variants.put("trailing newline", json(422, msg(exact + "\\n")));
    variants.put("trailing text", json(422, msg(exact + " (see docs)")));
    variants.put("trailing period", json(422, msg(exact + ".")));
    variants.put("prefix text", json(422, msg("error: " + exact)));
    variants.put("capitalised", json(422, msg("Invalid currency: " + base)));
    variants.put("lower-case code", json(422, msg("invalid currency: chf")));
    variants.put("no space", json(422, msg("invalid currency:" + base)));
    variants.put("base twice", json(422, msg(exact + " " + base)));
    variants.put("base and another", json(422, msg(exact + ", EUR")));
    variants.put("inside a longer word", json(422, msg("invalid currency: " + base + "X")));
    variants.put("longer code", json(422, msg("invalid currency: X" + base)));
    variants.put("Cyrillic look-alike", json(422, msg("invalid currency: СHF")));
    variants.put("full-width letters", json(422, msg("invalid currency: ＣＨＦ")));
    variants.put("full-width colon", json(422, msg("invalid currency： " + base)));
    variants.put("non-breaking space", json(422, msg("invalid currency: " + base)));
    variants.put("zero-width space", json(422, msg("invalid currency: ​" + base)));
    variants.put(
        "every currency at once",
        json(422, msg("invalid currency: " + String.join(" ", currencies.all()))));
    variants.put("other member", json(422, "{\"status\":422,\"error\":\"" + exact + "\"}"));
    variants.put(
        "message is an array", json(422, "{\"status\":422,\"message\":[\"" + exact + "\"]}"));
    variants.put("message nested", json(422, "{\"detail\":{\"message\":\"" + exact + "\"}}"));
    variants.put("plain text body", aResponse().withStatus(422).withBody(exact));
    variants.put(
        "HTML body",
        aResponse()
            .withStatus(422)
            .withHeader("Content-Type", "text/html")
            .withBody("<p>" + exact + "</p>"));
    variants.put("400 with the exact message", json(400, msg(exact)));
    variants.put("404 with the exact message", json(404, msg(exact)));
    variants.put("200 with the exact message", json(200, msg(exact)));
    variants.put("200 array with the message", json(200, "[" + msg(exact) + "]"));

    List<String> accepted = new ArrayList<>();
    for (Map.Entry<String, ResponseDefinitionBuilder> variant : variants.entrySet()) {
      FRANKFURTER.resetAll();
      stubAllBases(today().minusDays(1));
      answerBase(base, variant.getValue());
      JsonNode result = refresh();
      String label = variant.getKey();
      if (!unsupportedBases(result).isEmpty()
          || !failedBases(result).equals(List.of(base))
          || !"FAILED".equals(fxOutcome())
          || !healthUnsupported().isEmpty()) {
        accepted.add(label + ": " + result + " outcome " + fxOutcome());
      }
      assertThat(rowsOfBase(base)).as(label).isEmpty();
    }
    assertThat(accepted).as("variants not treated as a failure of the base").isEmpty();

    // Control: the exact verified answer is still recognised after all the variants.
    FRANKFURTER.resetAll();
    stubAllBases(today().minusDays(1));
    rejectBase(base, exact);
    JsonNode control = refresh();
    assertThat(unsupportedBases(control)).containsExactly(base);
    assertThat(failedBases(control)).isEmpty();
  }

  private static String msg(String message) {
    return "{\"status\":422,\"message\":\"" + message + "\"}";
  }

  @Test
  void aProviderThatRejectsEveryBaseFailsTheRunAndKeepsEveryStoredRate() {
    stubAllBases(today().minusDays(2));
    refresh();
    List<String> stored = storedRows();
    assertThat(stored).hasSize(pairCount());

    // Every base exactly unsupported: nothing could be refreshed, so the run fails.
    FRANKFURTER.resetAll();
    for (String base : currencies.all()) {
      rejectBase(base, "invalid currency: " + base);
    }
    JsonNode result = refresh();
    assertThat(unsupportedBases(result)).containsExactlyElementsOf(currencies.all());
    assertThat(failedBases(result)).isEmpty();
    assertThat(result.get("upsertedRows").asInt()).isZero();
    assertThat(storedRows()).as("stored rates stay in use").isEqualTo(stored);
    assertThat(fxOutcome()).as("no base could be refreshed").isEqualTo("FAILED");
    assertThat(healthUnsupported()).containsExactlyElementsOf(currencies.all());

    // One 422 naming every supported currency at once is not an exact rejection.
    FRANKFURTER.resetAll();
    for (String base : currencies.all()) {
      rejectBase(base, "invalid currency: " + String.join(", ", currencies.all()));
    }
    JsonNode all = refresh();
    assertThat(failedBases(all)).containsExactlyElementsOf(currencies.all());
    assertThat(unsupportedBases(all)).isEmpty();
    assertThat(storedRows()).isEqualTo(stored);
    assertThat(fxOutcome()).isEqualTo("FAILED");
    assertThat(healthUnsupported()).isEmpty();
  }

  @Test
  void aRejectionThatAlternatesBetweenRunsNeverLosesOrInventsRates() {
    LocalDate first = today().minusDays(3);
    stubAllBases(first);
    rejectBase(EXTRA, "invalid currency: " + EXTRA);
    JsonNode rejected = refresh();
    assertThat(failedBases(rejected)).isEmpty();
    assertThat(unsupportedBases(rejected)).containsExactly(EXTRA);
    assertThat(rowsOfBase(EXTRA)).isEmpty();

    // The provider accepts the base on the next run: its rates appear normally.
    FRANKFURTER.resetAll();
    LocalDate second = today().minusDays(2);
    stubAllBases(second);
    JsonNode accepted = refresh();
    assertThat(failedBases(accepted)).isEmpty();
    assertThat(unsupportedBases(accepted)).isEmpty();
    assertThat(fxOutcome()).isEqualTo("SUCCEEDED");
    assertThat(healthUnsupported()).isEmpty();
    List<String> extraRows = rowsOfBase(EXTRA);
    assertThat(extraRows).hasSize(quoteCount());
    assertThat(refreshedOn(EXTRA, second)).isTrue();
    List<String> usdRows = rowsOfBase("USD");

    // Then it rejects the base again and another one exactly, and a third one with another text.
    FRANKFURTER.resetAll();
    LocalDate third = today().minusDays(1);
    stubAllBases(third);
    rejectBase(EXTRA, "invalid currency: " + EXTRA);
    rejectBase("USD", "invalid currency: USD");
    rejectBase("GBP", "temporarily unavailable");
    List<String> gbpRows = rowsOfBase("GBP");
    JsonNode again = refresh();
    assertThat(unsupportedBases(again)).containsExactly(EXTRA, "USD");
    assertThat(failedBases(again)).containsExactly("GBP");
    assertThat(fxOutcome()).isEqualTo("FAILED");
    assertThat(healthUnsupported()).containsExactly(EXTRA, "USD");
    assertThat(rowsOfBase(EXTRA)).isEqualTo(extraRows);
    assertThat(rowsOfBase("USD")).isEqualTo(usdRows);
    assertThat(rowsOfBase("GBP")).isEqualTo(gbpRows);
    assertThat(refreshedOn("TRY", third)).isTrue();
    assertThat(refreshedOn(EXTRA, third)).as("no rate is invented for a rejected base").isFalse();
    assertThat(refreshedOn("USD", third)).as("no rate is invented for a rejected base").isFalse();
  }
}
