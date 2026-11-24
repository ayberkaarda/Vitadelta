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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;

/**
 * {@code POST /recommendations/compare} with a stubbed scoring service answering the GT-1 values
 * (section 6.11) for the seeded GOLD-A / GOLD-B pair: request mapping (validated against the
 * scoring contract), payload mapping and rounding, money conversion including the explanation
 * parameters, the quota exemption and the request errors.
 */
class CompareApiTest extends RecommendationApiTestBase {

  private static final double DELTA_COST = 1.666666666666666;
  private static final double DELTA_CONTENT = 0.03738545600618881;
  private static final double M_REF = 2.6817473528400697;
  private static final double MARGINAL_RATE = 44.580616226555186;
  private static final double RATIO = 16.623719672676348;

  private UUID goldA() {
    return productId("GOLD-A");
  }

  private UUID goldB() {
    return productId("GOLD-B");
  }

  private String pair(UUID baseline, UUID candidate, String extra) {
    return "{\"country\":\"TR\",\"baselineProductId\":\""
        + baseline
        + "\",\"candidateProductId\":\""
        + candidate
        + "\""
        + extra
        + "}";
  }

  private static String side(UUID id, double price, double costPerDay, double content) {
    return "{\"product_id\":\""
        + id
        + "\",\"cost\":{\"effective_price\":"
        + price
        + ",\"currency\":\"TRY\",\"servings_per_container\":60.0,\"days_supply\":60.0,"
        + "\"cost_per_day\":"
        + costPerDay
        + ",\"price_stale\":false,\"out_of_stock\":false},\"content_score\":"
        + content
        + ",\"value_score\":"
        + (content / costPerDay)
        + ",\"flags\":[],\"breakdown\":[]}";
  }

  private static String row(
      String code, String category, double a, double b, String unit, String flags) {
    return "{\"code\":\""
        + code
        + "\",\"category\":\""
        + category
        + "\",\"amount_A\":"
        + a
        + ",\"amount_B\":"
        + b
        + ",\"delta_amount\":"
        + (b - a)
        + ",\"unit\":\""
        + unit
        + "\",\"r_A\":"
        + (a / 80.0)
        + ",\"r_B\":"
        + (b / 80.0)
        + ",\"u_A\":1.4626145439938119,\"u_B\":1.5,\"weight\":1.0,"
        + "\"delta_contribution\":0.037385456006188145,\"flags\":"
        + flags
        + "}";
  }

  private void stubGoldenCompare(boolean swapped) {
    String answer =
        "{\"a\":"
            + side(goldA(), 800.0, 13.333333333333334, 4.971882723856459)
            + ",\"b\":"
            + side(goldB(), 900.0, 15.0, 5.009268179862648)
            + ",\"swapped\":"
            + swapped
            + ",\"currency\":\"TRY\",\"delta_cost\":"
            + DELTA_COST
            + ",\"delta_content\":"
            + DELTA_CONTENT
            + ",\"m_ref\":"
            + M_REF
            + ",\"market_size\":2,\"marginal_rate\":"
            + MARGINAL_RATE
            + ",\"ratio\":"
            + RATIO
            + ",\"verdict\":\"NOT_WORTH_IT\",\"nutrient_deltas\":["
            + row(
                "VIT_C",
                "VITAMIN",
                300.0,
                350.0,
                "mg",
                "[\"DIMINISHING\",\"ABOVE_CEILING_NO_CREDIT\"]")
            + ","
            + "{\"code\":\"COQ10\",\"category\":\"COENZYME\",\"amount_A\":0,\"amount_B\":30.0,"
            + "\"delta_amount\":30.0,\"unit\":\"mg\",\"r_A\":null,\"r_B\":null,\"u_A\":0,"
            + "\"u_B\":0.5,\"weight\":0.8,\"delta_contribution\":0.4,\"flags\":[\"NEW_IN_B\"]}"
            + "],\"explanation\":{\"key\":\"explain.verdict.not_worth_it\",\"params\":"
            + "{\"currency\":\"TRY\",\"deltaCostPerDay\":"
            + DELTA_COST
            + ",\"deltaContent\":"
            + DELTA_CONTENT
            + ",\"marginalRate\":"
            + MARGINAL_RATE
            + ",\"mRef\":"
            + M_REF
            + ",\"ratio\":"
            + RATIO
            + "}}}";
    scoring()
        .stubFor(
            post(urlEqualTo("/internal/v1/compare"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(answer)));
  }

  @Test
  void theGoldenPairIsComparedAgainstTheWholeMarketAndMappedToTheContract() {
    stubGoldenCompare(false);
    EntityExchangeResult<String> result = compare(pair(goldA(), goldB(), ""), null);
    assertStatus(result, 200);
    ContractSchemas.assertConforms("CompareResponse", result.getResponseBody());
    assertThat(result.getResponseHeaders().get("Set-Cookie")).isNull();

    JsonNode sent = onlyScoringRequest("compare");
    ScoringContractSchemas.assertConforms("CompareRequest", sent.toString());
    assertThat(sent.get("baseline").get("product_id").asString()).isEqualTo(goldA().toString());
    assertThat(sent.get("candidate").get("product_id").asString()).isEqualTo(goldB().toString());
    assertThat(sent.get("baseline").get("effective_price").decimalValue())
        .isEqualByComparingTo("800");
    List<String> market = new ArrayList<>();
    sent.get("market").forEach(c -> market.add(c.get("product_id").asString()));
    assertThat(market)
        .containsExactlyElementsOf(
            expectedCandidates("TR", StockFilter.IN_STOCK_ONLY).stream()
                .map(p -> p.id().toString())
                .toList())
        .contains(goldA().toString(), goldB().toString());
    assertThat(sent.get("market_profile").get("products").size())
        .isEqualTo(catalog.loadCountry("TR").orElseThrow().products().size());
    assertThat(sent.get("weights").get("category_weights").get("COENZYME").decimalValue())
        .isEqualByComparingTo("0.8");
    assertThat(scoringRequestIds("compare"))
        .containsExactly(result.getResponseHeaders().getFirst("X-Request-Id"));

    JsonNode body = json(result);
    assertThat(body.get("verdict").asString()).isEqualTo("NOT_WORTH_IT");
    assertThat(body.get("swapped").asBoolean()).isFalse();
    assertThat(body.get("deltaCost").toString()).isEqualTo("1.6667");
    assertThat(body.get("deltaCostNative").toString()).isEqualTo("1.6667");
    assertThat(body.get("deltaContent").toString()).isEqualTo("0.0374");
    assertThat(body.get("marginalRate").toString()).isEqualTo("44.5806");
    assertThat(body.get("mRef").toString()).isEqualTo("2.6817");
    assertThat(body.get("ratio").toString()).isEqualTo("16.6237");
    assertThat(body.get("meta").get("marketSize").asInt()).isEqualTo(2);
    assertThat(body.get("meta").get("catalogVersion").asLong()).isEqualTo(catalogVersion());
    JsonNode a = body.get("productA");
    assertThat(a.get("code").asString()).isEqualTo("GOLD-A");
    assertThat(a.get("brand").asString()).isEqualTo("Fixture Labs");
    assertThat(a.get("cost").get("costPerDay").toString()).isEqualTo("13.3333");
    assertThat(a.get("contentScore").toString()).isEqualTo("4.9719");
    assertThat(a.get("valueScore").toString()).isEqualTo("0.3729");
    assertThat(a.get("selectedOffer").get("retailer").asString()).isEqualTo("Fixture Pharmacy");
    assertThat(a.get("provenance").get("synthetic").asBoolean()).isTrue();
    assertThat(body.get("productB").get("code").asString()).isEqualTo("GOLD-B");
    JsonNode vitC = body.get("rows").get(0);
    assertThat(vitC.get("flags").toString())
        .isEqualTo("[\"DIMINISHING\",\"ABOVE_CEILING_NO_CREDIT\"]");
    assertThat(result.getResponseBody()).contains("\"rA\":3.7500,").contains("\"amountA\":0.0000,");
    assertThat(vitC.get("uA").toString()).isEqualTo("1.4626");
    JsonNode coq10 = body.get("rows").get(1);
    assertThat(coq10.get("rA").isNull()).isTrue();
    assertThat(body.get("explanation").toString())
        .isEqualTo(
            "{\"key\":\"explain.verdict.not_worth_it\",\"params\":{\"currency\":\"TRY\","
                + "\"deltaCostPerDay\":1.6667,\"deltaContent\":0.0374,\"marginalRate\":44.5806,"
                + "\"mRef\":2.6817,\"ratio\":16.6237}}");
  }

  @Test
  void moneyIncludingTheExplanationParametersIsConvertedFromUnroundedValues() {
    stubGoldenCompare(true);
    TestUser user = user(Role.USER);
    EntityExchangeResult<String> result =
        compare(pair(goldB(), goldA(), ",\"displayCurrency\":\"EUR\""), user.token());
    assertStatus(result, 200);
    ContractSchemas.assertConforms("CompareResponse", result.getResponseBody());
    JsonNode body = json(result);
    assertThat(body.get("swapped").asBoolean()).isTrue();
    assertThat(body.get("meta").get("currency").asString()).isEqualTo("EUR");
    assertThat(body.get("deltaCost").decimalValue()).isEqualByComparingTo(eur(DELTA_COST));
    assertThat(body.get("deltaCostNative").toString()).isEqualTo("1.6667");
    assertThat(body.get("mRef").decimalValue()).isEqualByComparingTo(eur(M_REF));
    assertThat(body.get("marginalRate").decimalValue()).isEqualByComparingTo(eur(MARGINAL_RATE));
    assertThat(body.get("ratio").toString()).isEqualTo("16.6237");
    assertThat(body.get("productA").get("cost").get("costPerDay").decimalValue())
        .isEqualByComparingTo(eur(13.333333333333334));
    assertThat(body.get("productA").get("valueScore").toString()).isEqualTo("0.3729");
    JsonNode params = body.get("explanation").get("params");
    assertThat(params.get("currency").asString()).isEqualTo("EUR");
    assertThat(params.get("deltaCostPerDay").decimalValue()).isEqualByComparingTo(eur(DELTA_COST));
    assertThat(params.get("marginalRate").decimalValue()).isEqualByComparingTo(eur(MARGINAL_RATE));
    assertThat(params.get("mRef").decimalValue()).isEqualByComparingTo(eur(M_REF));
    assertThat(params.get("deltaContent").toString()).isEqualTo("0.0374");
    assertThat(params.get("ratio").toString()).isEqualTo("16.6237");

    EntityExchangeResult<String> usd =
        compare(pair(goldA(), goldB(), ",\"displayCurrency\":\"USD\""), user.token());
    ContractSchemas.assertConforms("CompareResponse", usd.getResponseBody());
    JsonNode usdBody = json(usd);
    assertThat(usdBody.get("meta").get("fx").get("status").asString()).isEqualTo("UNAVAILABLE");
    assertThat(usdBody.get("explanation").get("params").get("currency").asString())
        .isEqualTo("TRY");
    assertThat(usdBody.get("productA").get("flags").toString())
        .isEqualTo("[{\"code\":\"FX_UNAVAILABLE\",\"nutrientCodes\":[]}]");
  }

  @Test
  void absentExplanationParametersStayAbsent() {
    String answer =
        "{\"a\":"
            + side(goldA(), 800.0, 13.333333333333334, 0.0)
            + ",\"b\":"
            + side(goldB(), 900.0, 15.0, 1.0)
            + ",\"swapped\":false,\"currency\":\"TRY\",\"delta_cost\":"
            + DELTA_COST
            + ",\"delta_content\":1.0,\"m_ref\":null,\"market_size\":2,\"marginal_rate\":null,"
            + "\"ratio\":null,\"verdict\":\"INSUFFICIENT_DATA\",\"nutrient_deltas\":[],"
            + "\"explanation\":{\"key\":\"explain.verdict.insufficient_data\",\"params\":"
            + "{\"currency\":\"TRY\",\"deltaCostPerDay\":"
            + DELTA_COST
            + ",\"deltaContent\":1.0}}}";
    scoring()
        .stubFor(
            post(urlEqualTo("/internal/v1/compare"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(answer)));
    EntityExchangeResult<String> result = compare(pair(goldA(), goldB(), ""), null);
    assertStatus(result, 200);
    ContractSchemas.assertConforms("CompareResponse", result.getResponseBody());
    JsonNode body = json(result);
    assertThat(body.get("mRef").isNull()).isTrue();
    assertThat(body.get("ratio").isNull()).isTrue();
    assertThat(result.getResponseBody())
        .contains(
            "\"params\":{\"currency\":\"TRY\",\"deltaCostPerDay\":1.6667,\"deltaContent\":1.0000}");
  }

  @Test
  void comparingIsNotCountedAgainstTheAnonymousQuota() {
    stubGoldenCompare(false);
    String cookie = anonymousCookie();
    for (int i = 0; i < 5; i++) {
      EntityExchangeResult<String> result =
          postJson("/api/v1/recommendations/compare", pair(goldA(), goldB(), ""), null, cookie);
      assertStatus(result, 200);
    }
    assertThat(queriesUsed(cookie)).isZero();
  }

  @Test
  void requestErrorsFollowTheContract() {
    stubGoldenCompare(false);
    assertValidationCode(
        compare(pair(goldA(), goldA(), ""), null), "candidateProductId", "same_product");
    assertValidationCode(
        compare(
            "{\"country\":\"TR\",\"baselineProductId\":\"x\",\"candidateProductId\":\""
                + goldB()
                + "\"}",
            null),
        "baselineProductId",
        "invalid_format");
    assertValidationCode(
        compare(pair(goldA(), goldB(), ",\"weights\":{\"overrides\":{\"NOPE_X\":1}}"), null),
        "weights.overrides.NOPE_X",
        "unknown_nutrient_code");
    assertValidationCode(
        compare(
            pair(
                goldA(),
                goldB(),
                ",\"weights\":{\"categories\":{\"VITAMIN\":0,\"MINERAL\":0,\"COENZYME\":0,"
                    + "\"OTHER\":0}}"),
            null),
        "weights",
        "weights_all_zero");
    assertProblem(compare(pair(UUID.randomUUID(), goldB(), ""), null), 404, "product_not_found");
    UUID usOnly =
        jdbc.sql(
                "SELECT p.id FROM product p WHERE NOT EXISTS (SELECT 1 FROM product_availability a"
                    + " WHERE a.product_id = p.id AND a.country_code = 'TR'"
                    + " AND a.status = 'AVAILABLE') ORDER BY p.id LIMIT 1")
            .query(UUID.class)
            .single();
    assertProblem(
        compare(pair(goldA(), usOnly, ""), null), 404, "product_not_available_in_country");
    assertProblem(
        compare(pair(goldA(), goldB(), "").replace("TR", "ZZ"), null), 422, "country_not_found");
    assertProblem(
        compare(pair(goldA(), goldB(), ",\"displayCurrency\":\"XYZ\""), null),
        422,
        "fx_unsupported_currency");
    assertThat(scoringRequests("compare")).isEmpty();
  }

  @Test
  void aProductWithoutEffectivePriceIsRejectedWithNoStockOrNoOffer() {
    stubGoldenCompare(false);
    CatalogProduct outOfStock =
        catalog.loadCountry("TR").orElseThrow().products().stream()
            .filter(p -> !p.offers().isEmpty() && p.offers().stream().noneMatch(o -> o.inStock()))
            .findFirst()
            .orElseThrow();
    assertValidationCode(
        compare(pair(goldA(), outOfStock.id(), ""), null), "candidateProductId", "no_stock");
    assertStatus(
        compare(pair(outOfStock.id(), goldA(), ",\"stockFilter\":\"INCLUDE_OUT_OF_STOCK\""), null),
        200);

    UUID withoutOffers =
        jdbc.sql(
                "SELECT p.id FROM product p WHERE NOT EXISTS (SELECT 1 FROM offer o"
                    + " WHERE o.product_id = p.id AND o.country_code = 'TR')"
                    + " AND NOT EXISTS (SELECT 1 FROM product_availability a"
                    + " WHERE a.product_id = p.id AND a.country_code = 'TR') ORDER BY p.id LIMIT 1")
            .query(UUID.class)
            .single();
    jdbc.sql(
            "INSERT INTO product_availability (product_id, country_code, status)"
                + " VALUES (?, 'TR', 'AVAILABLE')")
        .param(withoutOffers)
        .update();
    try {
      assertValidationCode(
          compare(pair(withoutOffers, goldA(), ",\"stockFilter\":\"INCLUDE_OUT_OF_STOCK\""), null),
          "baselineProductId",
          "no_offer");
    } finally {
      jdbc.sql("DELETE FROM product_availability WHERE product_id = ? AND country_code = 'TR'")
          .param(withoutOffers)
          .update();
    }
  }

  @Test
  void anUnavailableScoringServiceIs503() {
    scoring()
        .stubFor(post(urlEqualTo("/internal/v1/compare")).willReturn(aResponse().withStatus(502)));
    assertProblem(compare(pair(goldA(), goldB(), ""), null), 503, "scoring_unavailable");
  }

  private static BigDecimal eur(double nativeValue) {
    return new BigDecimal(nativeValue).multiply(TRY_EUR).setScale(4, RoundingMode.HALF_EVEN);
  }

  private static void assertValidationCode(
      EntityExchangeResult<String> result, String field, String code) {
    assertProblem(result, 422, "validation_failed");
    List<String> pairs = new ArrayList<>();
    json(result)
        .get("errors")
        .forEach(e -> pairs.add(e.get("field").asString() + ":" + e.get("code").asString()));
    assertThat(pairs).contains(field + ":" + code);
  }
}
