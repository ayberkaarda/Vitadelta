package com.vitadelta.api.recommendation;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.catalog.price.StockFilter;
import com.vitadelta.api.catalog.read.CatalogViews.CatalogProduct;
import com.vitadelta.api.identity.ContractSchemas;
import com.vitadelta.api.identity.Role;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code POST /recommendations/query} against the seeded catalog with a stubbed scoring service:
 * request mapping (validated against the scoring contract), response shape (validated against the
 * public contract), rounding and display currency, cache, entitlement gate, truncation, paging and
 * validation.
 */
class QueryApiTest extends RecommendationApiTestBase {

  private static final String TR_QUERY = "{\"country\":\"TR\"}";

  private List<CatalogProduct> inStockTr() {
    return expectedCandidates("TR", StockFilter.IN_STOCK_ONLY);
  }

  private int inMarketTr() {
    return catalog.loadCountry("TR").orElseThrow().products().size();
  }

  @Test
  void theScoringRequestCarriesTheNormalizedQueryAndConformsToTheScoringContract() {
    List<CatalogProduct> candidates = inStockTr();
    stubScore(candidates, StockFilter.IN_STOCK_ONLY);
    TestUser user = user(Role.USER);
    String body =
        "{\"country\":\"TR\",\"ranking\":{\"mode\":\"VALUE\"},"
            + "\"weights\":{\"categories\":{\"VITAMIN\":1.5},\"overrides\":{\"VIT_C\":2}},"
            + "\"requiredNutrients\":[{\"code\":\"VIT_D\",\"minAmountPerDay\":400,\"unit\":\"IU\"},"
            + "{\"code\":\"ZINC\"},{\"code\":\"MAGNESIUM\",\"minAmountPerDay\":0.1,\"unit\":\"g\"}],"
            + "\"excludeExceedingUl\":true}";

    EntityExchangeResult<String> result = query(body, user.token(), null);

    assertStatus(result, 200);
    ContractSchemas.assertConforms("RecommendationQueryResponse", result.getResponseBody());
    JsonNode sent = onlyScoringRequest("score");
    ScoringContractSchemas.assertConforms("ScoreRequest", sent.toString());
    JsonNode categories = sent.get("weights").get("category_weights");
    assertThat(categories.get("VITAMIN").decimalValue()).isEqualByComparingTo("1.5");
    assertThat(categories.get("MINERAL").decimalValue()).isEqualByComparingTo("1.0");
    assertThat(categories.get("COENZYME").decimalValue()).isEqualByComparingTo("0.8");
    assertThat(categories.get("OTHER").decimalValue()).isEqualByComparingTo("0.5");
    assertThat(sent.get("weights").get("overrides").toString()).isEqualTo("{\"VIT_C\":2}");
    JsonNode required = sent.get("required_nutrients");
    assertThat(required.size()).isEqualTo(3);
    assertThat(required.get(0).get("code").asString()).isEqualTo("VIT_D");
    assertThat(required.get(0).get("min_amount_per_day").decimalValue()).isEqualByComparingTo("10");
    assertThat(required.get(0).get("unit").asString()).isEqualTo("mcg");
    assertThat(required.get(1).get("code").asString()).isEqualTo("ZINC");
    assertThat(required.get(1).has("min_amount_per_day")).isFalse();
    assertThat(required.get(1).get("unit").asString()).isEqualTo("mg");
    assertThat(required.get(2).get("min_amount_per_day").decimalValue())
        .isEqualByComparingTo("100");
    assertThat(sent.get("exclude_exceeding_ul").asBoolean()).isTrue();
    assertThat(sent.get("ranking").toString()).isEqualTo("{\"mode\":\"VALUE\"}");
    List<String> sentIds = new ArrayList<>();
    sent.get("candidates").forEach(c -> sentIds.add(c.get("product_id").asString()));
    assertThat(sentIds)
        .containsExactlyElementsOf(candidates.stream().map(p -> p.id().toString()).toList());
    assertThat(sent.get("market_profile").get("catalog_version").asLong())
        .isEqualTo(catalogVersion());
    assertThat(sent.get("market_profile").get("products").size()).isEqualTo(inMarketTr());
    int registry = jdbc.sql("SELECT count(*) FROM nutrient").query(Integer.class).single();
    assertThat(sent.get("nutrients").size()).isEqualTo(registry);
    assertThat(sent.get("upper_limits").toString()).doesNotContain("note");
    assertThat(scoringRequestIds("score"))
        .containsExactly(result.getResponseHeaders().getFirst("X-Request-Id"));

    JsonNode meta = json(result).get("meta");
    assertThat(
            meta.get("excluded").get("noStock").asInt()
                + meta.get("excluded").get("noOffer").asInt())
        .isEqualTo(inMarketTr() - candidates.size());
    assertThat(meta.get("excluded").get("noStock").asInt()).isPositive();
    assertThat(meta.get("principal").toString()).isEqualTo("{\"type\":\"USER\",\"plan\":\"FREE\"}");
    assertThat(meta.get("marketSize").asInt()).isEqualTo(candidates.size());
    assertThat(meta.get("catalogVersion").asLong()).isEqualTo(catalogVersion());
  }

  @Test
  void customRankingSendsLambdaWithItsDefault() {
    stubScore(inStockTr(), StockFilter.IN_STOCK_ONLY);
    TestUser user = user(Role.USER);
    assertStatus(
        query("{\"country\":\"TR\",\"ranking\":{\"mode\":\"CUSTOM\"}}", user.token(), null), 200);
    assertThat(onlyScoringRequest("score").get("ranking").toString())
        .isEqualTo("{\"mode\":\"CUSTOM\",\"lambda\":0.5}");
  }

  @Test
  void scoresAndMoneyAreRoundedOnceFromTheUnroundedValues() {
    ObjectNode answer = stubScore(inStockTr(), StockFilter.IN_STOCK_ONLY);
    TestUser user = user(Role.USER);
    JsonNode first = answer.get("items").get(0);
    double costPerDay = first.get("cost").get("cost_per_day").asDouble();
    double content = first.get("content_score").asDouble();
    double value = first.get("value_score").asDouble();

    JsonNode body = json(query(TR_QUERY, user.token(), null));
    JsonNode item = body.get("items").get(0);
    assertThat(item.get("productId").asString()).isEqualTo(first.get("product_id").asString());
    assertThat(item.get("cost").get("costPerDay").decimalValue())
        .isEqualByComparingTo(round(costPerDay));
    assertThat(item.get("cost").get("costPerDayNative").decimalValue())
        .isEqualByComparingTo(round(costPerDay));
    assertThat(item.get("contentScore").decimalValue()).isEqualByComparingTo(round(content));
    assertThat(item.get("valueScore").decimalValue()).isEqualByComparingTo(round(value));
    assertThat(item.get("breakdown").get(0).get("utility").decimalValue())
        .isEqualByComparingTo("0.3333");
    assertThat(body.get("meta").get("mRef").decimalValue()).isEqualByComparingTo("0.6667");
    assertThat(body.get("meta").get("fx").get("status").asString()).isEqualTo("OK");

    EntityExchangeResult<String> euro =
        query("{\"country\":\"TR\",\"displayCurrency\":\"EUR\"}", user.token(), null);
    assertStatus(euro, 200);
    ContractSchemas.assertConforms("RecommendationQueryResponse", euro.getResponseBody());
    JsonNode euroBody = json(euro);
    JsonNode euroItem = euroBody.get("items").get(0);
    BigDecimal expected =
        new BigDecimal(costPerDay).multiply(TRY_EUR).setScale(4, RoundingMode.HALF_EVEN);
    assertThat(euroItem.get("cost").get("costPerDay").decimalValue())
        .isEqualByComparingTo(expected);
    assertThat(euroItem.get("cost").get("costPerDayNative").decimalValue())
        .isEqualByComparingTo(round(costPerDay));
    assertThat(euroItem.get("cost").get("currency").asString()).isEqualTo("EUR");
    assertThat(euroItem.get("valueScore").decimalValue()).isEqualByComparingTo(round(value));
    assertThat(euroBody.get("meta").get("mRef").decimalValue())
        .isEqualByComparingTo(
            new BigDecimal(2.0 / 3.0).multiply(TRY_EUR).setScale(4, RoundingMode.HALF_EVEN));
    assertThat(euroBody.get("meta").get("currency").asString()).isEqualTo("EUR");
    assertThat(euroBody.get("meta").get("nativeCurrency").asString()).isEqualTo("TRY");
    assertThat(euroBody.get("meta").get("fx").get("rate").decimalValue())
        .isEqualByComparingTo(TRY_EUR);
    assertThat(itemIds(euroBody)).isEqualTo(itemIds(body));
    assertThat(scoringRequests("score")).hasSize(1);
  }

  @Test
  void aCurrencyWithoutRateKeepsNativeMoneyAndFlagsEveryItem() {
    stubScore(inStockTr(), StockFilter.IN_STOCK_ONLY);
    TestUser user = user(Role.USER);
    EntityExchangeResult<String> result =
        query("{\"country\":\"TR\",\"displayCurrency\":\"USD\"}", user.token(), null);
    assertStatus(result, 200);
    ContractSchemas.assertConforms("RecommendationQueryResponse", result.getResponseBody());
    JsonNode meta = json(result).get("meta");
    assertThat(meta.get("currency").asString()).isEqualTo("TRY");
    assertThat(meta.get("fx").get("quote").asString()).isEqualTo("USD");
    assertThat(meta.get("fx").get("status").asString()).isEqualTo("UNAVAILABLE");
    assertThat(meta.get("fx").get("rate").isNull()).isTrue();
    for (JsonNode item : json(result).get("items")) {
      assertThat(item.get("cost").get("currency").asString()).isEqualTo("TRY");
      assertThat(item.get("cost").get("costPerDay").decimalValue())
          .isEqualByComparingTo(item.get("cost").get("costPerDayNative").decimalValue());
      assertThat(item.get("flags").toString())
          .endsWith("{\"code\":\"FX_UNAVAILABLE\",\"nutrientCodes\":[]}]");
    }
  }

  @Test
  void equivalentQueriesAreServedFromTheCacheUntilTheCatalogVersionChanges() {
    stubScore(inStockTr(), StockFilter.IN_STOCK_ONLY);
    TestUser user = user(Role.USER);
    JsonNode first = json(query(TR_QUERY, user.token(), null));
    String explicitDefaults =
        "{\"country\":\"TR\",\"stockFilter\":\"IN_STOCK_ONLY\",\"ranking\":{\"mode\":\"VALUE\"},"
            + "\"weights\":{\"categories\":{\"VITAMIN\":1.0,\"MINERAL\":1,\"COENZYME\":0.80,"
            + "\"OTHER\":0.5},\"overrides\":{}},\"requiredNutrients\":[],"
            + "\"excludeExceedingUl\":false,\"page\":2,\"pageSize\":5,\"displayCurrency\":\"EUR\"}";
    assertStatus(query(explicitDefaults, user.token(), null), 200);
    assertStatus(query(TR_QUERY, null, anonymousCookie()), 200);
    assertThat(scoringRequests("score")).hasSize(1);

    query("{\"country\":\"TR\",\"excludeExceedingUl\":true}", user.token(), null);
    assertThat(scoringRequests("score")).hasSize(2);

    jdbc.sql("UPDATE catalog_version SET version = version + 1 WHERE id = 1").update();
    JsonNode afterBump = json(query(TR_QUERY, user.token(), null));
    assertThat(scoringRequests("score")).hasSize(3);
    assertThat(afterBump.get("meta").get("catalogVersion").asLong())
        .isEqualTo(first.get("meta").get("catalogVersion").asLong() + 1);
    assertThat(onlyLast().get("market_profile").get("catalog_version").asLong())
        .isEqualTo(catalogVersion());
  }

  private JsonNode onlyLast() {
    var requests = scoringRequests("score");
    return JSON.readTree(requests.getLast().getBodyAsString());
  }

  @Test
  void anonymousCallersGetThreeQueriesAndTheFourthIsRejected() {
    stubScore(inStockTr(), StockFilter.IN_STOCK_ONLY);
    String cookie = anonymousCookie();
    for (int i = 1; i <= 3; i++) {
      EntityExchangeResult<String> result = query(TR_QUERY, null, cookie);
      assertStatus(result, 200);
      assertThat(json(result).get("meta").get("principal").toString())
          .isEqualTo("{\"type\":\"ANONYMOUS\",\"queriesUsed\":" + i + ",\"queriesLimit\":3}");
    }
    EntityExchangeResult<String> fourth = query(TR_QUERY, null, cookie);
    assertProblem(fourth, 403, "quota_exceeded");
    ContractSchemas.assertConforms("QuotaExceededProblem", fourth.getResponseBody());
    JsonNode problem = json(fourth);
    assertThat(problem.get("limit").asInt()).isEqualTo(3);
    assertThat(problem.get("used").asInt()).isEqualTo(3);
    assertThat(problem.get("action").asString()).isEqualTo("SIGN_UP");
    assertThat(problem.get("upgradeHint").get("ctaPath").asString()).isEqualTo("/auth/register");
    assertThat(queriesUsed(cookie)).isEqualTo(3);
  }

  @Test
  void aQueryWithoutCookieCreatesTheAnonymousIdentity() {
    stubScore(inStockTr(), StockFilter.IN_STOCK_ONLY);
    EntityExchangeResult<String> result = query(TR_QUERY, null, null);
    assertStatus(result, 200);
    String setCookie = result.getResponseHeaders().getFirst("Set-Cookie");
    assertThat(setCookie).startsWith("vd_anon=");
    String cookie = setCookie.substring(0, setCookie.indexOf(';'));
    assertThat(queriesUsed(cookie)).isEqualTo(1);
  }

  @Test
  void tenParallelAnonymousQueriesYieldExactlyThreeSuccesses() throws Exception {
    stubScore(inStockTr(), StockFilter.IN_STOCK_ONLY);
    String cookie = anonymousCookie();
    ExecutorService pool = Executors.newFixedThreadPool(10);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        Callable<Integer> call =
            () -> {
              start.await();
              return query(TR_QUERY, null, cookie).getStatus().value();
            };
        results.add(pool.submit(call));
      }
      start.countDown();
      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> result : results) {
        statuses.add(result.get(60, TimeUnit.SECONDS));
      }
      assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(3);
      assertThat(statuses.stream().filter(s -> s == 403).count()).isEqualTo(7);
    } finally {
      pool.shutdownNow();
    }
    assertThat(queriesUsed(cookie)).isEqualTo(3);
  }

  @Test
  void anonymousResultsAreTruncatedToTheTopThreeAndLockedItemsAreNeverSent() {
    ObjectNode answer = stubScore(inStockTr(), StockFilter.IN_STOCK_ONLY);
    String cookie = anonymousCookie();
    EntityExchangeResult<String> result = query(TR_QUERY, null, cookie);
    assertStatus(result, 200);
    ContractSchemas.assertConforms("RecommendationQueryResponse", result.getResponseBody());
    JsonNode body = json(result);
    int total = answer.get("items").size();
    assertThat(total).isGreaterThan(3);
    List<String> top = new ArrayList<>();
    List<String> locked = new ArrayList<>();
    for (JsonNode item : answer.get("items")) {
      (top.size() < 3 ? top : locked).add(item.get("product_id").asString());
    }
    assertThat(itemIds(body).stream().map(UUID::toString).toList()).containsExactlyElementsOf(top);
    assertThat(body.get("meta").get("visibility").toString())
        .isEqualTo(
            "{\"shown\":3,\"total\":"
                + total
                + ",\"locked\":"
                + (total - 3)
                + ",\"reason\":\"SIGN_UP_REQUIRED\"}");
    assertThat(body.get("meta").get("total").asInt()).isEqualTo(total);
    String raw = result.getResponseBody();
    Map<String, String> codes =
        catalog.loadCountry("TR").orElseThrow().products().stream()
            .collect(Collectors.toMap(p -> p.id().toString(), CatalogProduct::code));
    for (String id : locked) {
      assertThat(raw).doesNotContain(id).doesNotContain("\"code\":\"" + codes.get(id) + "\"");
    }

    EntityExchangeResult<String> second = query("{\"country\":\"TR\",\"page\":2}", null, cookie);
    assertStatus(second, 200);
    ContractSchemas.assertConforms("RecommendationQueryResponse", second.getResponseBody());
    JsonNode secondBody = json(second);
    assertThat(secondBody.get("items").isEmpty()).isTrue();
    assertThat(secondBody.get("meta").get("visibility"))
        .isEqualTo(body.get("meta").get("visibility"));
    assertThat(secondBody.get("meta").get("page").asInt()).isEqualTo(2);
    for (String id : locked) {
      assertThat(second.getResponseBody()).doesNotContain(id);
    }
  }

  @Test
  void freeAndProUsersSeeTheFullPagedRanking() {
    ObjectNode answer = stubScore(inStockTr(), StockFilter.IN_STOCK_ONLY);
    int total = answer.get("items").size();
    List<String> ranking = new ArrayList<>();
    answer.get("items").forEach(i -> ranking.add(i.get("product_id").asString()));
    for (TestUser user : List.of(user(Role.USER), proUser(), user(Role.ADMIN))) {
      JsonNode page2 =
          json(query("{\"country\":\"TR\",\"page\":2,\"pageSize\":5}", user.token(), null));
      assertThat(itemIds(page2).stream().map(UUID::toString).toList())
          .containsExactlyElementsOf(ranking.subList(5, Math.min(10, total)));
      assertThat(page2.get("items").get(0).get("rank").asInt()).isEqualTo(6);
      assertThat(page2.get("meta").get("visibility").toString())
          .isEqualTo(
              "{\"shown\":"
                  + Math.min(5, total - 5)
                  + ",\"total\":"
                  + total
                  + ",\"locked\":0,\"reason\":null}");
      JsonNode all = json(query("{\"country\":\"TR\",\"pageSize\":100}", user.token(), null));
      assertThat(all.get("items").size()).isEqualTo(total);
      JsonNode beyond = json(query("{\"country\":\"TR\",\"page\":50}", user.token(), null));
      assertThat(beyond.get("items").isEmpty()).isTrue();
      assertThat(beyond.get("meta").get("visibility").get("shown").asInt()).isZero();
    }
    JsonNode pro = json(query(TR_QUERY, proUser().token(), null));
    assertThat(pro.get("meta").get("principal").toString())
        .isEqualTo("{\"type\":\"USER\",\"plan\":\"PRO\"}");
    JsonNode admin = json(query(TR_QUERY, user(Role.ADMIN).token(), null));
    assertThat(admin.get("meta").get("principal").get("type").asString()).isEqualTo("ADMIN");
  }

  @Test
  void invalidRequestsAreRejectedBeforeTheQuotaIsReserved() {
    String cookie = anonymousCookie();
    assertValidation(
        query("{\"country\":\"TR\",\"ranking\":{\"mode\":\"VALUE\",\"lambda\":0.3}}", null, cookie),
        "ranking.lambda",
        "lambda_requires_custom");
    assertValidation(
        query(
            "{\"country\":\"TR\",\"weights\":{\"categories\":{\"VITAMIN\":0,\"MINERAL\":0,"
                + "\"COENZYME\":0,\"OTHER\":0}}}",
            null,
            cookie),
        "weights",
        "weights_all_zero");
    assertValidation(
        query(
            "{\"country\":\"TR\",\"requiredNutrients\":[{\"code\":\"VIT_C\",\"minAmountPerDay\":5,"
                + "\"unit\":\"IU\"}]}",
            null,
            cookie),
        "requiredNutrients[0].unit",
        "unit_requires_form");
    assertValidation(
        query(
            "{\"country\":\"TR\",\"requiredNutrients\":[{\"code\":\"VIT_A\",\"minAmountPerDay\":5,"
                + "\"unit\":\"IU\"}]}",
            null,
            cookie),
        "requiredNutrients[0].unit",
        "unit_requires_form");
    assertValidation(
        query("{\"country\":\"TR\",\"weights\":{\"overrides\":{\"NOPE_X\":1}}}", null, cookie),
        "weights.overrides.NOPE_X",
        "unknown_nutrient_code");
    assertValidation(
        query(
            "{\"country\":\"TR\",\"requiredNutrients\":[{\"code\":\"ZINC\"},{\"code\":\"ZINC\"}]}",
            null,
            cookie),
        "requiredNutrients[1].code",
        "duplicate_nutrient");
    assertValidation(
        query("{\"country\":\"TR\",\"page\":0,\"pageSize\":101}", null, cookie),
        "page",
        "out_of_range");
    assertValidation(
        query("{\"country\":\"TR\",\"weights\":{\"categories\":{\"VITAMIN\":2.5}}}", null, cookie),
        "weights.categories.VITAMIN",
        "out_of_range");
    assertValidation(
        query("{\"country\":\"TR\",\"stockFilter\":\"ALL\"}", null, cookie),
        "stockFilter",
        "invalid_format");
    assertValidation(
        query(
            "{\"country\":\"TR\",\"requiredNutrients\":[{\"code\":\"ZINC\",\"minAmountPerDay\":3}]}",
            null,
            cookie),
        "requiredNutrients[0].unit",
        "required");
    assertValidation(query("{}", null, cookie), "country", "required");
    assertValidation(
        query("{\"country\":\"TR\",\"displayCurrency\":\"usd\"}", null, cookie),
        "displayCurrency",
        "invalid_format");
    assertProblem(query("{\"country\":\"ZZ\"}", null, cookie), 422, "country_not_found");
    assertProblem(
        query("{\"country\":\"TR\",\"displayCurrency\":\"XYZ\"}", null, cookie),
        422,
        "fx_unsupported_currency");
    assertThat(queriesUsed(cookie)).isZero();
    assertThat(scoringRequests("score")).isEmpty();
  }

  @Test
  void aBodyThatIsNotJsonIsRejected() {
    EntityExchangeResult<String> result =
        client
            .post()
            .uri(java.net.URI.create("http://localhost:" + port + "/api/v1/recommendations/query"))
            .contentType(MediaType.TEXT_PLAIN)
            .header("X-Forwarded-For", ip)
            .body(TR_QUERY)
            .exchange()
            .returnResult(String.class);
    assertProblem(result, 415, "about:blank");
    assertProblem(query("{\"country\":", null, null), 422, "validation_failed");
    String large = "{\"country\":\"TR\",\"pad\":\"" + "x".repeat(70_000) + "\"}";
    assertProblem(query(large, null, null), 413, "validation_failed");
  }

  @Test
  void clientCausedScoringRejectionsAre422AndRefundTheQuota() {
    scoring()
        .stubFor(
            post(urlEqualTo("/internal/v1/score"))
                .willReturn(
                    aResponse()
                        .withStatus(422)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody(
                            "{\"type\":\"https://vitadelta.dev/problems/validation_failed\","
                                + "\"title\":\"Validation failed\",\"status\":422,\"requestId\":\"a1\","
                                + "\"errors\":[{\"field\":\"required_nutrients[0].min_amount_per_day\","
                                + "\"code\":\"value_out_of_range\",\"message\":\"too large\"},"
                                + "{\"field\":\"weights.category_weights.VITAMIN\","
                                + "\"code\":\"out_of_range\",\"message\":\"bad\"}]}")));
    String cookie = anonymousCookie();
    EntityExchangeResult<String> result =
        query(
            "{\"country\":\"TR\",\"requiredNutrients\":[{\"code\":\"ZINC\","
                + "\"minAmountPerDay\":1e300,\"unit\":\"mg\"}]}",
            null,
            cookie);
    assertProblem(result, 422, "validation_failed");
    JsonNode errors = json(result).get("errors");
    assertThat(errors.get(0).get("field").asString())
        .isEqualTo("requiredNutrients[0].minAmountPerDay");
    assertThat(errors.get(0).get("code").asString()).isEqualTo("value_out_of_range");
    assertThat(errors.get(1).get("field").asString()).isEqualTo("weights.categories.VITAMIN");
    assertThat(queriesUsed(cookie)).isZero();
  }

  @Test
  void apiCausedScoringRejectionsAndOther4xxAreInternalErrorsAndRefundTheQuota() {
    scoring()
        .stubFor(
            post(urlEqualTo("/internal/v1/score"))
                .willReturn(
                    aResponse()
                        .withStatus(422)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody(
                            "{\"type\":\"https://vitadelta.dev/problems/validation_failed\","
                                + "\"title\":\"Validation failed\",\"status\":422,\"requestId\":\"a1\","
                                + "\"errors\":[{\"field\":\"candidates[0].nutrients[1].unit\","
                                + "\"code\":\"unit_mismatch\",\"message\":\"Unit IU\"}]}")));
    String cookie = anonymousCookie();
    EntityExchangeResult<String> rejected = query(TR_QUERY, null, cookie);
    assertProblem(rejected, 500, "internal_error");
    assertThat(rejected.getResponseBody())
        .doesNotContain("unit_mismatch")
        .doesNotContain("candidates");

    scoring().resetAll();
    scoring()
        .stubFor(post(urlEqualTo("/internal/v1/score")).willReturn(aResponse().withStatus(404)));
    assertProblem(query(TR_QUERY, null, cookie), 500, "internal_error");

    scoring().resetAll();
    scoring()
        .stubFor(
            post(urlEqualTo("/internal/v1/score"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"items\":\"not a list\"}")));
    assertProblem(query(TR_QUERY, null, cookie), 500, "internal_error");
    assertThat(queriesUsed(cookie)).isZero();
    assertThat(
            circuitBreakers
                .circuitBreaker(ScoringGateway.INSTANCE)
                .getMetrics()
                .getNumberOfFailedCalls())
        .isZero();
  }

  @Test
  void includeOutOfStockScoresTheOutOfStockProductsToo() {
    List<CatalogProduct> all = expectedCandidates("TR", StockFilter.INCLUDE_OUT_OF_STOCK);
    assertThat(all.size()).isGreaterThan(inStockTr().size());
    stubScore(all, StockFilter.INCLUDE_OUT_OF_STOCK);
    TestUser user = user(Role.USER);
    JsonNode body =
        json(
            query(
                "{\"country\":\"TR\",\"stockFilter\":\"INCLUDE_OUT_OF_STOCK\"}",
                user.token(),
                null));
    assertThat(body.get("meta").get("excluded").get("noStock").asInt()).isZero();
    JsonNode sent = onlyScoringRequest("score");
    Set<Boolean> outOfStock = new java.util.HashSet<>();
    sent.get("candidates").forEach(c -> outOfStock.add(c.get("out_of_stock").asBoolean()));
    assertThat(outOfStock).containsExactlyInAnyOrder(true, false);
  }

  private static BigDecimal round(double value) {
    return new BigDecimal(value).setScale(4, RoundingMode.HALF_EVEN);
  }

  private static void assertValidation(
      EntityExchangeResult<String> result, String field, String code) {
    assertProblem(result, 422, "validation_failed");
    JsonNode errors = json(result).get("errors");
    List<String> pairs = new ArrayList<>();
    errors.forEach(e -> pairs.add(e.get("field").asString() + ":" + e.get("code").asString()));
    assertThat(pairs).contains(field + ":" + code);
  }
}
