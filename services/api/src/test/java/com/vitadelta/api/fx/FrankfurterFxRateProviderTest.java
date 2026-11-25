package com.vitadelta.api.fx;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.vitadelta.api.fx.FxProviderException.Reason;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The Frankfurter v2 adapter against a WireMock server answering with the shapes verified in
 * ADR-0009 ({@code GET /v2/rates?base=TRY&quotes=EUR,GBP,USD&providers=ECB}).
 */
class FrankfurterFxRateProviderTest {

  /** The verified answer of 2026-11-23 for base TRY (rows as published, one date). */
  static final String VERIFIED_TRY =
      "[{\"date\":\"2026-11-23\",\"base\":\"TRY\",\"quote\":\"EUR\",\"rate\":0.01807},"
          + "{\"date\":\"2026-11-23\",\"base\":\"TRY\",\"quote\":\"GBP\",\"rate\":0.015844},"
          + "{\"date\":\"2026-11-23\",\"base\":\"TRY\",\"quote\":\"USD\",\"rate\":0.019767}]";

  private static final Set<String> QUOTES = Set.of("USD", "EUR", "GBP");
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-24T10:00:00Z"), ZoneOffset.UTC);

  private static WireMockServer server;
  private FrankfurterFxRateProvider provider;

  @BeforeAll
  static void start() {
    server = new WireMockServer(options().dynamicPort());
    server.start();
  }

  @AfterAll
  static void stop() {
    server.stop();
  }

  @BeforeEach
  void reset() {
    server.resetAll();
    provider = new FrankfurterFxRateProvider(properties(server.baseUrl() + "/v2/"), CLOCK);
  }

  static FxProperties properties(String baseUrl) {
    return new FxProperties(
        baseUrl,
        List.of(),
        Duration.ofSeconds(2),
        Duration.ofMillis(500),
        4096,
        Duration.ofHours(6),
        1,
        5,
        Duration.ofSeconds(60));
  }

  private void answer(int status, String contentType, String body) {
    server.stubFor(
        get(urlPathEqualTo("/v2/rates"))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", contentType)
                    .withBody(body)));
  }

  private void answer(String body) {
    answer(200, "application/json", body);
  }

  private FxProviderException failure() {
    try {
      provider.fetchLatest("TRY", QUOTES);
    } catch (FxProviderException e) {
      return e;
    }
    throw new AssertionError("the fetch was expected to fail");
  }

  @Test
  void theVerifiedAnswerIsMappedWithDecimalRatesAndTheRowDate() {
    answer(VERIFIED_TRY);

    FxQuote quote = provider.fetchLatest("TRY", QUOTES);

    assertThat(quote.base()).isEqualTo("TRY");
    assertThat(quote.asOf()).isEqualTo(LocalDate.of(2026, 11, 23));
    assertThat(quote.rates()).containsOnlyKeys("EUR", "GBP", "USD");
    assertThat(quote.rates().get("EUR")).isEqualTo(new BigDecimal("0.01807000"));
    assertThat(quote.rates().get("GBP")).isEqualTo(new BigDecimal("0.01584400"));
    assertThat(quote.rates().get("USD")).isEqualTo(new BigDecimal("0.01976700"));
    assertThat(provider.source()).isEqualTo("FRANKFURTER");
  }

  @Test
  void theRequestSelectsTheEcbProviderExplicitlyWithSortedQuotes() {
    answer(VERIFIED_TRY);

    provider.fetchLatest("TRY", QUOTES);

    List<LoggedRequest> requests = server.findAll(getRequestedFor(urlPathEqualTo("/v2/rates")));
    assertThat(requests).hasSize(1);
    LoggedRequest request = requests.get(0);
    assertThat(request.getUrl()).isEqualTo("/v2/rates?base=TRY&quotes=EUR,GBP,USD&providers=ECB");
    assertThat(request.getHeader("Accept")).contains("application/json");
  }

  @Test
  void integerRatesExtraMembersAndJsonContentTypeVariantsAreAccepted() {
    answer(
        200,
        "application/json; charset=utf-8",
        "[{\"date\":\"2026-11-20\",\"base\":\"USD\",\"quote\":\"TRY\",\"rate\":50,"
            + "\"provider\":\"ECB\"}]");

    FxQuote quote = provider.fetchLatest("USD", Set.of("TRY"));

    assertThat(quote.rates().get("TRY")).isEqualTo(new BigDecimal("50.00000000"));
    assertThat(quote.asOf()).isEqualTo(LocalDate.of(2026, 11, 20));
  }

  @Test
  void ratesAreRoundedHalfEvenToEightDecimalsFromTheirDecimalText() {
    answer(
        "[{\"date\":\"2026-11-23\",\"base\":\"TRY\",\"quote\":\"EUR\",\"rate\":0.123456785},"
            + "{\"date\":\"2026-11-23\",\"base\":\"TRY\",\"quote\":\"GBP\",\"rate\":0.123456795},"
            + "{\"date\":\"2026-11-23\",\"base\":\"TRY\",\"quote\":\"USD\",\"rate\":9999999999.99999999}]");

    FxQuote quote = provider.fetchLatest("TRY", QUOTES);

    assertThat(quote.rates().get("EUR")).isEqualTo(new BigDecimal("0.12345678"));
    assertThat(quote.rates().get("GBP")).isEqualTo(new BigDecimal("0.12345680"));
    assertThat(quote.rates().get("USD")).isEqualTo(new BigDecimal("9999999999.99999999"));
  }

  @Test
  void aServerErrorFailsTheFetchWithABoundedSummary() {
    answer(500, "text/html", "<html>" + "x".repeat(1000) + "\n\u001b[31m</html>");

    FxProviderException e = failure();

    assertThat(e.reason()).isEqualTo(Reason.HTTP_STATUS);
    assertThat(e.getMessage()).startsWith("status 500, ").contains("[truncated]");
    assertThat(e.getMessage().length()).isLessThan(200);
    assertThat(e.getMessage()).doesNotContain("\n").doesNotContain("\u001b");
  }

  @Test
  void theVerifiedInvalidCurrencyAnswerFailsTheFetch() {
    answer(422, "application/json", "{\"status\":422,\"message\":\"invalid currency: XXQ\"}");

    FxProviderException e = failure();

    assertThat(e.reason()).isEqualTo(Reason.HTTP_STATUS);
    assertThat(e.getMessage()).contains("422").contains("invalid currency: XXQ");
  }

  @Test
  void notFoundAndRedirectsAreFailuresAndRedirectsAreNotFollowed() {
    answer(404, "application/json", "{\"status\":404,\"message\":\"not found\"}");
    assertThat(failure().reason()).isEqualTo(Reason.HTTP_STATUS);

    server.resetAll();
    server.stubFor(
        get(urlPathEqualTo("/v2/rates"))
            .willReturn(aResponse().withStatus(302).withHeader("Location", "/elsewhere")));
    assertThat(failure().getMessage()).startsWith("status 302");
    assertThat(server.findAll(getRequestedFor(urlPathEqualTo("/elsewhere")))).isEmpty();
  }

  @Test
  void aSlowAnswerTimesOut() {
    server.stubFor(
        get(urlPathEqualTo("/v2/rates"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(VERIFIED_TRY)
                    .withFixedDelay(2000)));

    assertThat(failure().reason()).isEqualTo(Reason.TIMEOUT);
  }

  @Test
  void aBrokenConnectionIsAConnectionFailure() {
    server.stubFor(
        get(urlPathEqualTo("/v2/rates"))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

    assertThat(failure().reason()).isEqualTo(Reason.CONNECTION);
  }

  @Test
  void anUnreachableHostIsAConnectionFailure() {
    WireMockServer closed = new WireMockServer(options().dynamicPort());
    closed.start();
    String url = closed.baseUrl() + "/v2";
    closed.stop();
    FrankfurterFxRateProvider unreachable = new FrankfurterFxRateProvider(properties(url), CLOCK);

    assertThatThrownBy(() -> unreachable.fetchLatest("TRY", QUOTES))
        .isInstanceOfSatisfying(
            FxProviderException.class, e -> assertThat(e.reason()).isEqualTo(Reason.CONNECTION));
  }

  @Test
  void anOversizedBodyIsRejectedWithoutParsing() {
    answer("[" + " ".repeat(5000) + "]");

    FxProviderException e = failure();

    assertThat(e.reason()).isEqualTo(Reason.TOO_LARGE);
    assertThat(e.getMessage()).isEqualTo("body exceeds 4096 bytes");
  }

  @Test
  void aNonJsonContentTypeIsRejected() {
    answer(200, "text/plain", VERIFIED_TRY);
    assertThat(failure().reason()).isEqualTo(Reason.MALFORMED);

    server.resetAll();
    server.stubFor(
        get(urlPathEqualTo("/v2/rates")).willReturn(aResponse().withStatus(200).withBody("[]")));
    assertThat(failure().getMessage()).startsWith("content type missing");
  }

  @Test
  void aJsonSuffixContentTypeCountsAsJson() {
    answer(200, "application/vnd.rates+json", VERIFIED_TRY);

    assertThat(provider.fetchLatest("TRY", QUOTES).rates()).hasSize(3);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "not json",
        "[{\"date\":\"2026-11-23\"",
        "[] []",
        "{\"date\":\"2026-11-23\",\"base\":\"TRY\",\"rates\":{\"EUR\":0.01807}}",
        "[1, 2]",
        "[{\"date\":\"2026-11-23\",\"base\":\"TRY\",\"quote\":\"EUR\"}]",
        "[{\"date\":\"2026-11-23\",\"base\":\"TRY\",\"quote\":\"EUR\",\"rate\":\"0.01807\"}]",
        "[{\"date\":20261123,\"base\":\"TRY\",\"quote\":\"EUR\",\"rate\":0.01807}]",
        "[{\"date\":\"23.11.2026\",\"base\":\"TRY\",\"quote\":\"EUR\",\"rate\":0.01807}]",
        "[{\"date\":\"2026-11-23\",\"base\":\"TRY\",\"quote\":\"EUR\",\"rate\":NaN}]",
        "[{\"date\":\"2026-11-23\",\"base\":\"TRY\",\"base\":\"EUR\",\"quote\":\"EUR\","
            + "\"rate\":0.01807}]"
      })
  void malformedBodiesAreRejected(String body) {
    answer(body);

    assertThat(failure().reason()).isEqualTo(Reason.MALFORMED);
  }

  @Test
  void anEmptyArrayIsRejected() {
    answer("[]");

    FxProviderException e = failure();

    assertThat(e.reason()).isEqualTo(Reason.INVALID_ROWS);
    assertThat(e.getMessage()).isEqualTo("no rates in the answer");
  }

  @Test
  void rowsForAnotherBaseAreRejected() {
    answer(
        VERIFIED_TRY.replace(
            "\"base\":\"TRY\",\"quote\":\"GBP\"", "\"base\":\"EUR\",\"quote\":\"GBP\""));

    FxProviderException e = failure();

    assertThat(e.reason()).isEqualTo(Reason.INVALID_ROWS);
    assertThat(e.getMessage()).isEqualTo("row 1 is for another base");
  }

  @Test
  void unrequestedRepeatedAndMissingQuotesAreRejected() {
    answer(VERIFIED_TRY.replace("\"quote\":\"GBP\"", "\"quote\":\"CHF\""));
    assertThat(failure().getMessage()).isEqualTo("row 1 has an unrequested quote");

    server.resetAll();
    answer(VERIFIED_TRY.replace("\"quote\":\"GBP\"", "\"quote\":\"EUR\""));
    assertThat(failure().getMessage()).isEqualTo("row 1 repeats quote EUR");

    server.resetAll();
    answer("[{\"date\":\"2026-11-23\",\"base\":\"TRY\",\"quote\":\"EUR\",\"rate\":0.01807}]");
    FxProviderException missing = failure();
    assertThat(missing.reason()).isEqualTo(Reason.INVALID_ROWS);
    assertThat(missing.getMessage()).isEqualTo("quotes missing: [GBP, USD]");
  }

  @Test
  void mixedDatesAreRejected() {
    answer(VERIFIED_TRY.replaceFirst("2026-11-23", "2026-11-20"));

    FxProviderException e = failure();

    assertThat(e.reason()).isEqualTo(Reason.INVALID_ROWS);
    assertThat(e.getMessage()).isEqualTo("the rows carry different dates");
  }

  @Test
  void aDateAfterTomorrowIsRejected() {
    answer(VERIFIED_TRY.replace("2026-11-23", "2026-11-26"));
    assertThat(failure().getMessage()).isEqualTo("the rate date lies in the future");

    server.resetAll();
    answer(VERIFIED_TRY.replace("2026-11-23", "2026-11-25"));
    assertThat(provider.fetchLatest("TRY", QUOTES).asOf()).isEqualTo(LocalDate.of(2026, 11, 25));
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "0.0", "-0.01807", "10000000000", "1e12", "0.000000004"})
  void ratesOutsideTheStorableRangeAreRejected(String rate) {
    answer(VERIFIED_TRY.replace("0.015844", rate));

    FxProviderException e = failure();

    assertThat(e.reason()).isEqualTo(Reason.INVALID_ROWS);
    assertThat(e.getMessage()).isEqualTo("row 1 has a rate out of range");
  }

  @Test
  void invalidArgumentsAreRejectedBeforeAnyCall() {
    assertThatThrownBy(() -> provider.fetchLatest("try", QUOTES))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> provider.fetchLatest(null, QUOTES))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> provider.fetchLatest("TRY", Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> provider.fetchLatest("TRY", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> provider.fetchLatest("TRY", Set.of("EUR", "TRY")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> provider.fetchLatest("TRY", Set.of("EUR", "E&R")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(server.getAllServeEvents()).isEmpty();
  }

  @Test
  void quotesRejectNonPositiveRatesAndCopyTheirMap() {
    assertThatThrownBy(() ->
            new FxQuote(
                "TRY", LocalDate.of(2026, 11, 23), java.util.Map.of("EUR", BigDecimal.ZERO)))
        .isInstanceOf(IllegalArgumentException.class);
    java.util.Map<String, BigDecimal> rates = new java.util.HashMap<>();
    rates.put("USD", BigDecimal.ONE);
    FxQuote quote = new FxQuote("TRY", LocalDate.of(2026, 11, 23), rates);
    rates.put("EUR", BigDecimal.ONE);
    assertThat(quote.rates()).containsOnlyKeys("USD");
  }
}
