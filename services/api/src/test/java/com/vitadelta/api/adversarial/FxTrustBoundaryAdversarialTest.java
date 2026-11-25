package com.vitadelta.api.adversarial;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.vitadelta.api.adversarial.AttackClient.Resp;
import com.vitadelta.api.catalog.price.EffectivePriceResolver;
import com.vitadelta.api.catalog.price.StockFilter;
import com.vitadelta.api.catalog.read.CatalogReadService;
import com.vitadelta.api.catalog.read.CatalogViews.CatalogProduct;
import com.vitadelta.api.fx.FxApiTestBase;
import com.vitadelta.api.identity.ContractSchemas;
import com.vitadelta.api.identity.Role;
import com.vitadelta.api.recommendation.RecommendationCaches;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.SortedSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The FX trust boundary (sections 7.7 and 4.6): a hostile or broken rate provider never changes the
 * stored rates, never crashes the job and never reaches a client; hostile {@code base} and {@code
 * displayCurrency} values get the contract's answers on every money endpoint; conversion with any
 * stored rate, extreme ones included, never changes the ranking and never converts {@code
 * valueScore}; the admin refresh is ADMIN only and runs once at a time; the scoring-engine
 * parameters republished by {@code GET /weights/defaults} stay within the public contract.
 */
class FxTrustBoundaryAdversarialTest extends FxApiTestBase {

  static final String MARKER = "PROVIDER-MARKER-7731";
  static final String API = "/api/v1";

  @Autowired CatalogReadService catalog;
  @Autowired EffectivePriceResolver prices;
  @Autowired RecommendationCaches caches;

  private AttackClient raw;
  private String admin;
  private String user;

  @BeforeEach
  void prepareAttack() {
    raw = new AttackClient(port);
    admin = token(Role.ADMIN);
    user = token(Role.USER);
    caches.clear();
  }

  private Resp send(String method, String path, String token) {
    return raw.req(method, path).ip(AdversarialIntegrationTest.randomIp()).bearer(token).send();
  }

  private Resp sendJson(String path, String token, String json) {
    return raw.req("POST", path)
        .ip(AdversarialIntegrationTest.randomIp())
        .bearer(token)
        .json(json)
        .send();
  }

  // ---------- Hostile provider ----------

  private static String row(String date, String base, String quote, String rate, boolean marker) {
    return "{\"date\":\""
        + date
        + "\",\"base\":\""
        + base
        + "\",\"quote\":\""
        + quote
        + "\",\"rate\":"
        + rate
        + (marker ? ",\"note\":\"" + MARKER + "\"" : "")
        + "}";
  }

  /** Rows for every quote of {@code base}; the first quote gets {@code firstRate}. */
  private String rows(String base, String date, String firstRate, boolean marker) {
    List<String> rows = new ArrayList<>();
    for (String quote : quotesFor(base)) {
      String rate =
          rows.isEmpty() && firstRate != null
              ? firstRate
              : stubRate(base, quote).multiply(BigDecimal.TEN).toPlainString();
      rows.add(row(date, base, quote, rate, marker));
    }
    return "[" + String.join(",", rows) + "]";
  }

  private static ResponseDefinitionBuilder json(int status, String body) {
    return aResponse()
        .withStatus(status)
        .withHeader("Content-Type", "application/json")
        .withBody(body);
  }

  private void stubEveryBase(Function<String, ResponseDefinitionBuilder> answer) {
    FRANKFURTER.resetAll();
    for (String base : currencies.all()) {
      FRANKFURTER.stubFor(
          WireMock.get(urlPathEqualTo("/v2/rates"))
              .withQueryParam("base", equalTo(base))
              .willReturn(answer.apply(base)));
    }
  }

  private Map<String, Function<String, ResponseDefinitionBuilder>> hostileAnswers() {
    String today = today().toString();
    Map<String, Function<String, ResponseDefinitionBuilder>> answers = new LinkedHashMap<>();
    answers.put(
        "redirect to another host",
        base -> {
          FRANKFURTER.stubFor(
              WireMock.get(urlPathEqualTo("/evil/rates"))
                  .withQueryParam("base", equalTo(base))
                  .willReturn(json(200, rows(base, today, null, false))));
          return aResponse()
              .withStatus(302)
              .withHeader("Location", FRANKFURTER.baseUrl() + "/evil/rates?base=" + base);
        });
    answers.put(
        "5 MB body",
        base -> {
          String valid = rows(base, today, null, false);
          return json(
              200, valid.substring(0, valid.length() - 1) + " ".repeat(5 * 1024 * 1024) + "]");
        });
    answers.put(
        "slow body", base -> json(200, rows(base, today, null, false)).withFixedDelay(2500));
    answers.put("NaN rate", base -> json(200, rows(base, today, "NaN", true)));
    answers.put("Infinity rate", base -> json(200, rows(base, today, "Infinity", true)));
    answers.put("negative rate", base -> json(200, rows(base, today, "-0.5", true)));
    answers.put("zero rate", base -> json(200, rows(base, today, "0", true)));
    answers.put("1e400 rate", base -> json(200, rows(base, today, "1e400", true)));
    answers.put("rate rounding to zero", base -> json(200, rows(base, today, "0.000000001", true)));
    answers.put("rate of 1e10", base -> json(200, rows(base, today, "10000000000", true)));
    answers.put("string rate", base -> json(200, rows(base, today, "\"0.5\"", true)));
    answers.put("null rate", base -> json(200, rows(base, today, "null", true)));
    answers.put(
        "wrong base",
        base ->
            json(
                200,
                rows(base, today, null, true).replace("\"base\":\"" + base, "\"base\":\"XAU")));
    answers.put(
        "date in two days",
        base -> json(200, rows(base, today().plusDays(2).toString(), null, true)));
    answers.put(
        "date next year",
        base -> json(200, rows(base, today().plusDays(400).toString(), null, true)));
    answers.put(
        "mixed dates",
        base -> {
          String body = rows(base, today, null, true);
          return json(
              200,
              body.replaceFirst(
                  "\"date\":\"" + today + "\"", "\"date\":\"" + today().minusDays(3) + "\""));
        });
    answers.put("invalid date", base -> json(200, rows(base, "2026-13-45", null, true)));
    answers.put(
        "HTML page with 200",
        base ->
            aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/html")
                .withBody("<html><body>" + MARKER + "<script>alert(1)</script></body></html>"));
    answers.put(
        "HTML error page",
        base ->
            aResponse()
                .withStatus(500)
                .withHeader("Content-Type", "text/html")
                .withBody("<html><body>Internal error " + MARKER + "</body></html>"));
    answers.put(
        "JSON object instead of array",
        base -> json(200, "{\"rates\":" + rows(base, today, null, true) + "}"));
    answers.put(
        "duplicated quote",
        base -> {
          String body = rows(base, today, null, true);
          String first = body.substring(1, body.indexOf('}') + 1);
          return json(200, "[" + first + "," + body.substring(1));
        });
    answers.put(
        "lower-case quotes",
        base -> json(200, rows(base, today, null, true).replace("\"quote\":\"", "\"quote\":\"x")));
    answers.put(
        "unrequested quote",
        base -> {
          String body = rows(base, today, null, true);
          return json(
              200,
              body.substring(0, body.length() - 1)
                  + ","
                  + row(today, base, "XAU", "1.5", true)
                  + "]");
        });
    answers.put("empty array", base -> json(200, "[]"));
    answers.put("truncated JSON", base -> json(200, "[{\"date\":\"" + today + "\",\"base\""));
    answers.put("trailing tokens", base -> json(200, rows(base, today, null, true) + "[]"));
    answers.put(
        "JSON with the wrong content type",
        base ->
            aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/xml")
                .withBody(rows(base, today, null, true)));
    answers.put(
        "422 naming every quote",
        base ->
            json(
                422,
                "{\"status\":422,\"message\":\"invalid currency: "
                    + String.join(" ", currencies.all())
                    + " "
                    + MARKER
                    + "\"}"));
    answers.put("deeply nested JSON", base -> json(200, "[".repeat(5000) + "]".repeat(5000)));
    return answers;
  }

  @Test
  void hostileProviderAnswersNeverChangeStoredRatesOrReachAClient() {
    stubAllBases(today().minusDays(1));
    job.run();
    List<String> stored = storedRows();
    assertThat(stored).hasSize(pairCount());
    List<String> failures = new ArrayList<>();
    for (Map.Entry<String, Function<String, ResponseDefinitionBuilder>> scenario :
        hostileAnswers().entrySet()) {
      stubEveryBase(scenario.getValue());
      Resp refresh = send("POST", API + "/admin/fx/refresh", admin);
      String label = scenario.getKey();
      if (refresh.status() != 200) {
        failures.add(label + ": refresh answered " + refresh);
        continue;
      }
      assertThat(refresh.body()).as(label).doesNotContain(MARKER).doesNotContain("<script");
      AttackClient.assertNoLeak(refresh);
      if (!storedRows().equals(stored)) {
        failures.add(label + ": stored rates changed to " + storedRows());
        clearRates();
        stubAllBases(today().minusDays(1));
        job.run();
        stored = storedRows();
      }
      JsonNode result = refresh.json();
      if (result.get("upsertedRows").asInt() != 0) {
        failures.add(label + ": upserted " + result);
      }
      if (result.get("failedBases").size() != currencies.all().size()) {
        failures.add(label + ": failed bases " + result.get("failedBases"));
      }
      for (String path :
          List.of(API + "/fx/rates?base=TRY", API + "/fx/currencies", API + "/health")) {
        Resp read = send("GET", path, null);
        assertThat(read.status()).as(label + " " + path).isEqualTo(200);
        assertThat(read.body()).as(label + " " + path).doesNotContain(MARKER);
      }
      Resp health = send("GET", API + "/admin/health", admin);
      assertThat(health.body()).as(label).doesNotContain(MARKER).doesNotContain("<script");
    }
    assertThat(failures).as("hostile provider answers that got through").isEmpty();
  }

  @Test
  void extraProviderFieldsAreIgnoredAndNeverExposed() {
    LocalDate date = today().minusDays(1);
    stubEveryBase(base ->
        json(
            200,
            rows(base, date.toString(), null, true)
                .replace(
                    "}",
                    ",\"__proto__\":{\"admin\":true},\"source\":\"EVIL\",\"as_of\":\"2099-01-01\"}")));
    Resp refresh = send("POST", API + "/admin/fx/refresh", admin);
    assertThat(refresh.status()).as(refresh.toString()).isEqualTo(200);
    assertThat(refresh.json().get("upsertedRows").asInt()).isEqualTo(pairCount());
    assertThat(storedRows()).allMatch(r -> r.endsWith("|FRANKFURTER") && r.contains("@" + date));
    Resp rates = send("GET", API + "/fx/rates?base=TRY", null);
    ContractSchemas.assertConforms("FxRateList", rates.body());
    assertThat(rates.body()).doesNotContain(MARKER, "EVIL", "__proto__", "2099");
  }

  // ---------- Hostile parameters ----------

  @Test
  void hostileBaseValuesGetTheContractAnswers() {
    storeRate("TRY", "USD", today().minusDays(1), TRY_USD);
    for (String base :
        List.of(
            "%27%20OR%201%3D1",
            "%27%20OR%20%271%27%3D%271",
            "x".repeat(10_000),
            "try",
            "Try",
            "TR",
            "TRYY",
            "",
            "%00TRY",
            "TRY%00",
            "%D0%A2RY",
            "TRY%20",
            "TRY&base=USD",
            "TRY,USD")) {
      Resp response = send("GET", API + "/fx/rates?base=" + base, null);
      AttackClient.assertProblem(response, 422, "validation_failed");
      assertThat(response.body()).contains("\"base\"");
      AttackClient.assertApiSecurityHeaders(response);
    }
    AttackClient.assertProblem(send("GET", API + "/fx/rates", null), 422, "validation_failed");
    for (String base : List.of("XYZ", "XAU", "BTC", "AAA")) {
      Resp response = send("GET", API + "/fx/rates?base=" + base, null);
      AttackClient.assertProblem(response, 422, "fx_unsupported_currency");
    }
    Resp valid = send("GET", API + "/fx/rates?base=TRY", null);
    assertThat(valid.status()).isEqualTo(200);
    assertThat(valid.body()).contains("0.02413700");
  }

  @Test
  void hostileDisplayCurrenciesAreRejectedOnEveryMoneyEndpoint() {
    storeRate("TRY", "USD", today().minusDays(1), TRY_USD);
    String product = productId("GOLD-A").toString();
    List<String> malformed =
        List.of("usd", "US", "USDD", "%27%20OR%201%3D1", "x".repeat(10_000), "", "U%00D", "US%20");
    List<String> unsupported = List.of("XYZ", "XAU", "JPY");
    List<String> getPaths =
        List.of(
            API + "/products?country=TR&displayCurrency=",
            API + "/products/" + product + "?country=TR&displayCurrency=",
            API + "/plans?displayCurrency=",
            API + "/me/saved-products?country=TR&displayCurrency=");
    List<String> problems = new ArrayList<>();
    for (String path : getPaths) {
      for (String value : malformed) {
        check(send("GET", path + value, user), path + value, "validation_failed", problems);
      }
      for (String value : unsupported) {
        check(send("GET", path + value, user), path + value, "fx_unsupported_currency", problems);
      }
    }
    String compareProducts =
        "\"baselineProductId\":\""
            + product
            + "\",\"candidateProductId\":\""
            + productId("GOLD-B")
            + "\"";
    for (String endpoint : List.of("/recommendations/query", "/recommendations/compare")) {
      String prefix =
          "{\"country\":\"TR\","
              + (endpoint.endsWith("compare") ? compareProducts + "," : "")
              + "\"displayCurrency\":";
      for (String value :
          List.of(
              "\"usd\"",
              "\"US\"",
              "\"' OR 1=1\"",
              "\"" + "x".repeat(10_000) + "\"",
              "\"\"",
              "123",
              "[\"USD\"]",
              "{\"$ne\":null}",
              "true")) {
        check(
            sendJson(API + endpoint, user, prefix + value + "}"),
            endpoint + value,
            "validation_failed",
            problems);
      }
      check(
          sendJson(API + endpoint, user, prefix + "\"XYZ\"}"),
          endpoint + " XYZ",
          "fx_unsupported_currency",
          problems);
    }
    assertThat(problems).isEmpty();
    assertThat(
            SCORING.findAll(
                WireMock.postRequestedFor(
                    WireMock.urlPathMatching("/internal/v1/(score|compare)"))))
        .as("rejected before scoring")
        .isEmpty();
  }

  private static void check(Resp response, String label, String code, List<String> problems) {
    String shortLabel = label.length() > 120 ? label.substring(0, 120) + "..." : label;
    if (response.status() != 422
        || !response.body().contains("https://vitadelta.dev/problems/" + code)) {
      problems.add(shortLabel + " -> " + response.status() + " " + abbreviate(response.body()));
      return;
    }
    AttackClient.assertNoLeak(response);
    AttackClient.assertApiSecurityHeaders(response);
    assertThat(response.body()).doesNotContain("x".repeat(200));
  }

  private static String abbreviate(String body) {
    return body.length() > 300 ? body.substring(0, 300) + "..." : body;
  }

  // ---------- Conversion invariance ----------

  private record Scored(CatalogProduct product, double price, double cost, double content) {}

  private void stubScore(long seed) {
    List<CatalogProduct> candidates =
        catalog.loadCountry("TR").orElseThrow().products().stream()
            .filter(p -> prices.resolve(p.offers(), StockFilter.IN_STOCK_ONLY).isPriced())
            .toList();
    Random random = new Random(seed);
    List<Scored> scored = new ArrayList<>();
    for (CatalogProduct p : candidates) {
      double price =
          prices
              .resolve(p.offers(), StockFilter.IN_STOCK_ONLY)
              .price()
              .orElseThrow()
              .price()
              .doubleValue();
      double days =
          ((double) p.unitsPerContainer() / (double) p.unitsPerServing())
              / p.servingsPerDay().doubleValue();
      scored.add(new Scored(p, price, price / days, 0.5 + random.nextDouble() * 3));
    }
    scored.sort(
        Comparator.comparingDouble((Scored s) -> -s.content() / s.cost())
            .thenComparing(s -> s.product().id().toString()));
    ObjectNode answer = JSON.createObjectNode();
    ArrayNode items = answer.putArray("items");
    int rank = 1;
    for (Scored s : scored) {
      CatalogProduct p = s.product();
      double servings = (double) p.unitsPerContainer() / (double) p.unitsPerServing();
      ObjectNode item = items.addObject();
      item.put("product_id", p.id().toString());
      item.put("rank", rank++);
      ObjectNode cost = item.putObject("cost");
      cost.put("effective_price", s.price());
      cost.put("currency", "TRY");
      cost.put("servings_per_container", servings);
      cost.put("days_supply", servings / p.servingsPerDay().doubleValue());
      cost.put("cost_per_day", s.cost());
      cost.put("price_stale", false);
      cost.put("out_of_stock", false);
      item.put("content_score", s.content());
      item.put("content_score_display", 50.0);
      item.put("value_score", s.content() / s.cost());
      item.put("value_score_display", 50.0);
      item.putNull("custom_score");
      item.putArray("flags");
      item.putArray("breakdown");
      item.putArray("top_contributors");
    }
    ObjectNode meta = answer.putObject("meta");
    meta.put("m_ref", 1.0 / 3.0);
    meta.put("market_size", candidates.size());
    ObjectNode excluded = meta.putObject("excluded");
    excluded.put("required_nutrients", 0);
    excluded.put("exceeds_ul", 0);
    SCORING.resetAll();
    SCORING.stubFor(
        WireMock.post(urlEqualTo("/internal/v1/score")).willReturn(json(200, answer.toString())));
  }

  private static List<String> column(JsonNode items, String field) {
    List<String> values = new ArrayList<>();
    items.forEach(i -> values.add(i.get(field).toString()));
    return values;
  }

  @Test
  void anyStoredRateKeepsTheRankingAndNeverConvertsTheValueScore() {
    Random random = new Random(20261125L);
    List<BigDecimal> rates =
        new ArrayList<>(
            List.of(
                new BigDecimal("0.00000001"),
                new BigDecimal("99999999.00000000"),
                new BigDecimal("1.00000000"),
                new BigDecimal("0.00000003")));
    for (int i = 0; i < 4; i++) {
      rates.add(
          BigDecimal.valueOf(random.nextDouble() * 1000)
              .setScale(8, java.math.RoundingMode.HALF_EVEN)
              .add(new BigDecimal("0.00000001")));
    }
    List<String> problems = new ArrayList<>();
    for (int i = 0; i < rates.size(); i++) {
      BigDecimal rate = rates.get(i);
      clearRates();
      caches.clear();
      storeRate("TRY", "USD", today().minusDays(1), rate);
      stubScore(i);
      String body = "{\"country\":\"TR\",\"pageSize\":100";
      Resp inTry = sendJson(API + "/recommendations/query", user, body + "}");
      Resp inUsd =
          sendJson(API + "/recommendations/query", user, body + ",\"displayCurrency\":\"USD\"}");
      assertThat(inTry.status()).as(inTry.toString()).isEqualTo(200);
      assertThat(inUsd.status()).as("rate %s: %s", rate, abbreviate(inUsd.body())).isEqualTo(200);
      JsonNode a = inTry.json().get("items");
      JsonNode b = inUsd.json().get("items");
      assertThat(column(b, "productId")).as("rate %s", rate).isEqualTo(column(a, "productId"));
      assertThat(column(b, "rank")).isEqualTo(column(a, "rank"));
      assertThat(column(b, "valueScore"))
          .as("valueScore at rate %s", rate)
          .isEqualTo(column(a, "valueScore"));
      assertThat(column(b, "valueScoreDisplay")).isEqualTo(column(a, "valueScoreDisplay"));
      assertThat(column(b, "contentScore")).isEqualTo(column(a, "contentScore"));
      assertThat(inUsd.json().get("meta").get("fx").get("rate").decimalValue())
          .isEqualByComparingTo(rate);
      try {
        ContractSchemas.assertConforms("RecommendationQueryResponse", inUsd.body());
      } catch (AssertionError e) {
        String message = e.getMessage();
        problems.add(
            "rate " + rate + ": " + message.substring(Math.max(0, message.length() - 700)));
      }
      if (!inUsd.body().contains("\"rate\":" + rate.toPlainString())) {
        problems.add("rate " + rate + " not written with its 8 stored decimals in meta.fx");
      }
      Resp listed = send("GET", API + "/fx/rates?base=TRY", null);
      if (!listed.body().contains("\"rate\":" + rate.toPlainString())) {
        problems.add(
            "rate "
                + rate
                + " not written with its 8 stored decimals in /fx/rates: "
                + listed.body());
      }
      Resp listTry = send("GET", API + "/products?country=TR&pageSize=100", user);
      Resp listUsd =
          send("GET", API + "/products?country=TR&pageSize=100&displayCurrency=USD", user);
      assertThat(column(listUsd.json().get("items"), "id"))
          .isEqualTo(column(listTry.json().get("items"), "id"));
    }
    assertThat(problems).as("converted responses outside the contract").isEmpty();
  }

  // ---------- Admin refresh ----------

  @Test
  void theRefreshIsAdminOnlyAndRunsOnceAtATime() throws Exception {
    stubAllBases(today().minusDays(1));
    AttackClient.assertProblem(send("POST", API + "/admin/fx/refresh", null), 401, "unauthorized");
    AttackClient.assertProblem(send("POST", API + "/admin/fx/refresh", user), 403, "forbidden");
    String forged =
        AdversarialIntegrationTest.sign(
            "not-the-secret-of-this-server-0123456789"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
            null,
            com.nimbusds.jose.JWSAlgorithm.HS256,
            AdversarialIntegrationTest.claims(
                java.util.UUID.randomUUID().toString(),
                "ADMIN",
                true,
                java.time.Instant.now(),
                java.time.Duration.ofMinutes(5)));
    AttackClient.assertProblem(
        send("POST", API + "/admin/fx/refresh", forged), 401, "unauthorized");
    for (String method : List.of("GET", "PUT", "DELETE")) {
      Resp wrong = send(method, API + "/admin/fx/refresh", user);
      assertThat(wrong.status()).as(method + " " + wrong).isIn(403, 405);
      AttackClient.assertNoLeak(wrong);
    }
    assertThat(providerRequests()).as("no provider call for a rejected caller").isZero();

    FRANKFURTER.resetAll();
    for (String base : currencies.all()) {
      SortedSet<String> quotes = quotesFor(base);
      FRANKFURTER.stubFor(
          WireMock.get(urlPathEqualTo("/v2/rates"))
              .withQueryParam("base", equalTo(base))
              .willReturn(
                  json(200, verifiedBody(base, quotes, today().minusDays(1))).withFixedDelay(600)));
    }
    ExecutorService executor = Executors.newFixedThreadPool(6);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<Resp>> futures = new ArrayList<>();
      for (int i = 0; i < 6; i++) {
        Callable<Resp> call =
            () -> {
              start.await();
              return send("POST", API + "/admin/fx/refresh", admin);
            };
        futures.add(executor.submit(call));
      }
      start.countDown();
      List<Integer> statuses = new ArrayList<>();
      for (Future<Resp> future : futures) {
        Resp r = future.get(120, TimeUnit.SECONDS);
        statuses.add(r.status());
        if (r.status() == 409) {
          AttackClient.assertProblem(r, 409, "conflict_retry");
        }
      }
      assertThat(statuses.stream().filter(s -> s == 200)).as(statuses.toString()).hasSize(1);
      assertThat(statuses.stream().filter(s -> s == 409)).hasSize(5);
    } finally {
      executor.shutdownNow();
    }
    assertThat(providerRequests())
        .as("one run, one call per base")
        .isEqualTo(currencies.all().size());
    assertThat(storedRowCount()).isEqualTo(pairCount());
  }

  // ---------- Engine parameters ----------

  private void scoringHealth(String config) {
    SCORING.resetAll();
    SCORING.stubFor(
        WireMock.get(urlEqualTo("/internal/v1/health"))
            .willReturn(json(200, "{\"status\":\"UP\",\"config\":" + config + "}")));
  }

  @Test
  void weightDefaultsRepublishOnlyTheFourDocumentedEngineNumbers() {
    List<String> problems = new ArrayList<>();
    for (String config :
        List.of(
            "{\"diminishing_beta\":0.35,\"utility_ceiling\":1.5,\"comparison_epsilon\":1e-9,"
                + "\"verdict_worth_it_max_ratio\":1.0,\"verdict_marginal_max_ratio\":1.5,"
                + "\"database_url\":\"jdbc:postgresql://db.internal/"
                + MARKER
                + "\","
                + "\"version\":\""
                + MARKER
                + "\"}",
            "{\"diminishing_beta\":\""
                + MARKER
                + "\",\"utility_ceiling\":1.5,"
                + "\"comparison_epsilon\":1e-9,\"verdict_worth_it_max_ratio\":1.0,"
                + "\"verdict_marginal_max_ratio\":1.5}",
            "{\"diminishing_beta\":-1,\"utility_ceiling\":0.5,\"comparison_epsilon\":1e-9,"
                + "\"verdict_worth_it_max_ratio\":0,\"verdict_marginal_max_ratio\":-3}")) {
      scoringHealth(config);
      Resp health = send("GET", API + "/admin/health", admin);
      assertThat(health.status()).isEqualTo(200);
      assertThat(health.body()).doesNotContain(MARKER).doesNotContain("db.internal");
      Resp defaults = send("GET", API + "/weights/defaults", null);
      assertThat(defaults.status()).isEqualTo(200);
      assertThat(defaults.body()).doesNotContain(MARKER).doesNotContain("db.internal");
      AttackClient.assertApiSecurityHeaders(defaults);
      JsonNode engine = defaults.json().get("engine");
      if (!engine.isNull()) {
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : engine.properties()) {
          keys.add(e.getKey());
        }
        assertThat(keys)
            .containsExactlyInAnyOrder(
                "diminishingBeta",
                "utilityCeiling",
                "verdictWorthItMaxRatio",
                "verdictMarginalMaxRatio");
      }
      try {
        ContractSchemas.assertConforms("DefaultWeights", defaults.body());
      } catch (AssertionError e) {
        problems.add(config + " -> " + defaults.body());
      }
    }
    scoringHealth(
        "{\"diminishing_beta\":0.35,\"utility_ceiling\":1.5,\"comparison_epsilon\":1e-9,"
            + "\"verdict_worth_it_max_ratio\":1.0,\"verdict_marginal_max_ratio\":1.5}");
    send("GET", API + "/admin/health", admin);
    assertThat(problems).as("engine parameters outside the public contract").isEmpty();
  }
}
