package com.vitadelta.api.fx;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.vitadelta.api.catalog.web.CatalogApiTest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

/**
 * Base of the FX integration tests: the seeded catalog test server (scheduling off) with the FX
 * provider pointed at a WireMock server that answers with the verified Frankfurter v2 shape
 * (ADR-0009), the scoring service replaced by a second WireMock server, the extra display currency
 * {@code CHF}, a 1 s provider read timeout and a movable application clock. Every test starts and
 * ends with an empty {@code fx_rate} table and a cleared lookup cache, so the shared database holds
 * no rates for other test classes. The context is closed after each class so that the FX contexts
 * do not add to the memory held by the context cache for later test classes.
 */
@Import(FxApiTestBase.ClockConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class FxApiTestBase extends CatalogApiTest {

  /** Extra display currency configured for these tests. */
  protected static final String EXTRA = "CHF";

  /** Rates of the four country currencies used by the stubs (ECB-like precision). */
  protected static final BigDecimal TRY_USD = new BigDecimal("0.02413700");

  protected static final BigDecimal TRY_EUR = new BigDecimal("0.02087500");

  protected static final MovableClock CLOCK = new MovableClock();

  protected static final WireMockServer FRANKFURTER = new WireMockServer(options().dynamicPort());
  protected static final WireMockServer SCORING = new WireMockServer(options().dynamicPort());

  static {
    FRANKFURTER.start();
    SCORING.start();
  }

  @DynamicPropertySource
  static void fxProperties(DynamicPropertyRegistry registry) {
    registry.add("vitadelta.fx.base-url", () -> FRANKFURTER.baseUrl() + "/v2");
    registry.add("vitadelta.fx.extra-currencies", () -> EXTRA);
    registry.add("vitadelta.fx.read-timeout", () -> "1s");
    registry.add("vitadelta.scoring.base-url", SCORING::baseUrl);
  }

  /** The movable clock as the application clock. */
  @TestConfiguration(proxyBeanMethods = false)
  static class ClockConfig {

    @Bean
    @Primary
    Clock movableClock() {
      return CLOCK;
    }
  }

  @Autowired protected FxRefreshJob job;
  @Autowired protected FxRateLookup lookup;
  @Autowired protected SupportedCurrencies currencies;
  @Autowired protected FxRateRepository repository;

  @BeforeEach
  void resetFx() {
    CLOCK.reset();
    FRANKFURTER.resetAll();
    SCORING.resetAll();
    clearRates();
  }

  @AfterEach
  void removeFxRates() {
    CLOCK.reset();
    clearRates();
  }

  protected void clearRates() {
    jdbc.sql("DELETE FROM fx_rate").update();
    lookup.invalidate();
  }

  /** Today (UTC) by the application clock. */
  protected static LocalDate today() {
    return LocalDate.now(CLOCK);
  }

  /** Stores one rate row directly and clears the lookup cache. */
  protected void storeRate(String base, String quote, LocalDate asOf, BigDecimal rate) {
    jdbc.sql(
            "INSERT INTO fx_rate (base_currency, quote_currency, as_of, rate, fetched_at, source)"
                + " VALUES (?, ?, ?, ?, ?, 'FRANKFURTER')")
        .params(base, quote, asOf, rate, Timestamp.from(CLOCK.instant()))
        .update();
    lookup.invalidate();
  }

  /** {@code fx_rate} rows as {@code base>quote@asOf=rate|source}, sorted. */
  protected List<String> storedRows() {
    return jdbc.sql(
            "SELECT base_currency || '>' || quote_currency || '@' || as_of || '=' || rate"
                + " || '|' || source FROM fx_rate ORDER BY 1")
        .query(String.class)
        .list();
  }

  protected int storedRowCount() {
    return jdbc.sql("SELECT count(*) FROM fx_rate").query(Integer.class).single();
  }

  // ---------- Frankfurter stubs ----------

  /** The deterministic stub rate from {@code base} to {@code quote}. */
  protected static BigDecimal stubRate(String base, String quote) {
    if (base.equals("TRY") && quote.equals("USD")) {
      return TRY_USD;
    }
    if (base.equals("TRY") && quote.equals("EUR")) {
      return TRY_EUR;
    }
    int seed = Math.floorMod((base + quote).hashCode(), 9000) + 1000;
    return new BigDecimal(seed).movePointLeft(3).setScale(8, RoundingMode.UNNECESSARY);
  }

  /** The verified v2 body for {@code base} with every quote of {@code quotes} on {@code date}. */
  protected static String verifiedBody(String base, SortedSet<String> quotes, LocalDate date) {
    StringBuilder body = new StringBuilder("[");
    for (String quote : quotes) {
      if (body.length() > 1) {
        body.append(',');
      }
      body.append("{\"date\":\"")
          .append(date)
          .append("\",\"base\":\"")
          .append(base)
          .append("\",\"quote\":\"")
          .append(quote)
          .append("\",\"rate\":")
          .append(stubRate(base, quote).stripTrailingZeros().toPlainString())
          .append('}');
    }
    return body.append(']').toString();
  }

  /** The quotes the job requests for {@code base}: every other supported currency. */
  protected SortedSet<String> quotesFor(String base) {
    SortedSet<String> quotes = new TreeSet<>(currencies.all());
    quotes.remove(base);
    return quotes;
  }

  /** Stubs a valid answer dated {@code date} for every supported base. */
  protected void stubAllBases(LocalDate date) {
    for (String base : currencies.all()) {
      stubBase(base, 200, verifiedBody(base, quotesFor(base), date));
    }
  }

  /** Stubs the answer to the request for {@code base} (ECB provider, every other currency). */
  protected void stubBase(String base, int status, String body) {
    FRANKFURTER.stubFor(
        WireMock.get(urlPathEqualTo("/v2/rates"))
            .withQueryParam("base", equalTo(base))
            .withQueryParam("quotes", equalTo(String.join(",", quotesFor(base))))
            .withQueryParam("providers", equalTo("ECB"))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));
  }

  /** Number of rate requests the provider stub received. */
  protected int providerRequests() {
    return FRANKFURTER
        .findAll(
            com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(
                urlPathEqualTo("/v2/rates")))
        .size();
  }

  /** Number of supported currency pairs (every ordered pair of distinct supported currencies). */
  protected int pairCount() {
    int n = currencies.all().size();
    return n * (n - 1);
  }

  // ---------- HTTP ----------

  protected EntityExchangeResult<String> post(String path, String body, String token) {
    return client
        .post()
        .uri(URI.create("http://localhost:" + port + path))
        .contentType(MediaType.APPLICATION_JSON)
        .headers(h -> {
          h.set("X-Forwarded-For", ip);
          if (token != null) {
            h.setBearerAuth(token);
          }
        })
        .body(body)
        .exchange()
        .returnResult(String.class);
  }

  /** {@code nativeValue x rate}, rounded once half-even to 4 decimals (the reference values). */
  protected static BigDecimal converted(BigDecimal nativeValue, BigDecimal rate) {
    return nativeValue.multiply(rate).setScale(4, RoundingMode.HALF_EVEN);
  }

  /** The reference conversion of an unrounded double from the scoring service. */
  protected static BigDecimal converted(double nativeValue, BigDecimal rate) {
    return converted(new BigDecimal(nativeValue), rate);
  }

  /** A UTC clock that starts at the real time and can be moved by tests. */
  protected static final class MovableClock extends Clock {

    private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

    /** Moves the clock {@code duration} ahead of real time. */
    public void advance(Duration duration) {
      offset.updateAndGet(current -> current.plus(duration));
    }

    /** Back to real time. */
    public void reset() {
      offset.set(Duration.ZERO);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return Instant.now().plus(offset.get());
    }
  }

  private static final tools.jackson.databind.json.JsonMapper EXACT =
      tools.jackson.databind.json.JsonMapper.builder()
          .enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .build();

  /** The body parsed with decimal numbers kept as written (scale included). */
  protected static tools.jackson.databind.JsonNode exact(EntityExchangeResult<String> result) {
    return EXACT.readTree(result.getResponseBody());
  }

  /** The {@code items[].id} of a list body, in order. */
  protected static List<String> ids(tools.jackson.databind.JsonNode items, String field) {
    List<String> ids = new ArrayList<>();
    for (tools.jackson.databind.JsonNode item : items) {
      ids.add(item.get(field).asString());
    }
    return ids;
  }
}
