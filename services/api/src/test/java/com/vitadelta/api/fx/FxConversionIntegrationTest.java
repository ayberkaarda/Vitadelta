package com.vitadelta.api.fx;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.vitadelta.api.catalog.price.EffectivePriceResolver;
import com.vitadelta.api.catalog.price.StockFilter;
import com.vitadelta.api.catalog.read.CatalogReadService;
import com.vitadelta.api.catalog.read.CatalogViews.CatalogProduct;
import com.vitadelta.api.identity.ContractSchemas;
import com.vitadelta.api.identity.Role;
import com.vitadelta.api.recommendation.RecommendationCaches;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Display conversion with the real FX converter over stored rates on every money-bearing response
 * (section 7.7): recommendation query and compare, product list and detail, plans.
 */
class FxConversionIntegrationTest extends FxApiTestBase {

  private static final String TR_QUERY = "{\"country\":\"TR\",\"pageSize\":100}";

  @Autowired CatalogReadService catalog;
  @Autowired EffectivePriceResolver prices;
  @Autowired RecommendationCaches caches;

  private String userToken;

  @BeforeEach
  void prepare() {
    caches.clear();
    userToken = token(Role.USER);
  }

  // ---------- Scoring stubs ----------

  private record Scored(CatalogProduct product, double price, double cost, double content) {}

  /** A score answer for the TR in-stock candidates, ranked by value (content / cost). */
  private List<Scored> stubScore() {
    List<CatalogProduct> candidates =
        catalog.loadCountry("TR").orElseThrow().products().stream()
            .filter(p -> prices.resolve(p.offers(), StockFilter.IN_STOCK_ONLY).isPriced())
            .toList();
    List<Scored> scored = new ArrayList<>();
    for (int i = 0; i < candidates.size(); i++) {
      CatalogProduct p = candidates.get(i);
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
      scored.add(new Scored(p, price, price / days, 1.0 + (i % 5) / 7.0));
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
    SCORING.stubFor(
        WireMock.post(urlEqualTo("/internal/v1/score"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(answer.toString())));
    return scored;
  }

  // ---------- Recommendation query ----------

  @Test
  void theRankingInUsdEqualsTheRankingInTryItemForItem() {
    storeRate("TRY", "USD", today().minusDays(1), TRY_USD);
    stubScore();

    JsonNode inTry = exact(post("/api/v1/recommendations/query", TR_QUERY, userToken));
    EntityExchangeResult<String> usd =
        post(
            "/api/v1/recommendations/query",
            "{\"country\":\"TR\",\"pageSize\":100,\"displayCurrency\":\"USD\"}",
            userToken);

    assertStatus(usd, 200);
    ContractSchemas.assertConforms("RecommendationQueryResponse", usd.getResponseBody());
    JsonNode inUsd = exact(usd);
    assertThat(inTry.get("items").size()).isGreaterThan(2);
    assertThat(ids(inUsd.get("items"), "productId"))
        .containsExactlyElementsOf(ids(inTry.get("items"), "productId"));
    assertThat(ids(inUsd.get("items"), "rank"))
        .containsExactlyElementsOf(ids(inTry.get("items"), "rank"));
    for (int i = 0; i < inTry.get("items").size(); i++) {
      JsonNode a = inTry.get("items").get(i);
      JsonNode b = inUsd.get("items").get(i);
      assertThat(b.get("valueScore").toString()).isEqualTo(a.get("valueScore").toString());
      assertThat(b.get("contentScore").toString()).isEqualTo(a.get("contentScore").toString());
      assertThat(b.get("cost").get("costPerDayNative").toString())
          .isEqualTo(a.get("cost").get("costPerDay").toString());
    }
  }

  @Test
  void everyMoneyFieldIsConvertedOnceFromTheUnroundedNativeValue() {
    storeRate("TRY", "USD", today().minusDays(1), TRY_USD);
    List<Scored> scored = stubScore();

    EntityExchangeResult<String> result =
        post(
            "/api/v1/recommendations/query",
            "{\"country\":\"TR\",\"pageSize\":100,\"displayCurrency\":\"USD\"}",
            userToken);

    assertStatus(result, 200);
    JsonNode body = exact(result);
    JsonNode meta = body.get("meta");
    assertThat(meta.get("currency").asString()).isEqualTo("USD");
    assertThat(meta.get("nativeCurrency").asString()).isEqualTo("TRY");
    assertThat(meta.get("fx").toString())
        .isEqualTo(
            "{\"base\":\"TRY\",\"quote\":\"USD\",\"rate\":0.02413700,\"asOf\":\""
                + today().minusDays(1)
                + "\",\"source\":\"FRANKFURTER\",\"stale\":false,\"status\":\"OK\"}");
    assertThat(meta.get("mRef").decimalValue()).isEqualTo(converted(1.0 / 3.0, TRY_USD));
    for (int i = 0; i < scored.size(); i++) {
      Scored s = scored.get(i);
      JsonNode item = body.get("items").get(i);
      assertThat(item.get("productId").asString()).isEqualTo(s.product().id().toString());
      JsonNode cost = item.get("cost");
      assertThat(cost.get("currency").asString()).isEqualTo("USD");
      assertThat(cost.get("effectivePrice").decimalValue())
          .isEqualTo(converted(s.price(), TRY_USD));
      assertThat(cost.get("costPerDay").decimalValue()).isEqualTo(converted(s.cost(), TRY_USD));
      assertThat(cost.get("costPerDayNative").decimalValue())
          .isEqualTo(new BigDecimal(s.cost()).setScale(4, RoundingMode.HALF_EVEN));
      assertThat(item.get("valueScore").decimalValue())
          .isEqualTo(new BigDecimal(s.content() / s.cost()).setScale(4, RoundingMode.HALF_EVEN));
      assertThat(item.get("flags").toString()).doesNotContain("FX_");
    }
  }

  @Test
  void aRoundedNativeValueTimesTheRateDiffersFromTheReportedValueWhereRoundingMatters() {
    double unrounded = 12.345649999;
    BigDecimal rate = new BigDecimal("10.00000000");
    assertThat(converted(unrounded, rate)).isEqualTo(new BigDecimal("123.4565"));
    assertThat(converted(new BigDecimal("12.3456"), rate)).isEqualTo(new BigDecimal("123.4560"));
  }

  @Test
  void aStaleRateFlagsEveryItemWithFxStale() {
    storeRate("TRY", "USD", today().minusDays(6), TRY_USD);
    stubScore();

    JsonNode body =
        exact(
            post(
                "/api/v1/recommendations/query",
                "{\"country\":\"TR\",\"displayCurrency\":\"USD\"}",
                userToken));

    assertThat(body.get("meta").get("fx").get("stale").asBoolean()).isTrue();
    assertThat(body.get("meta").get("fx").get("status").asString()).isEqualTo("OK");
    for (JsonNode item : body.get("items")) {
      assertThat(item.get("flags").toString())
          .endsWith("{\"code\":\"FX_STALE\",\"nutrientCodes\":[]}]");
    }
  }

  @Test
  void aPairWithoutRateIsAnsweredInTheNativeCurrency() {
    storeRate("USD", "TRY", today(), new BigDecimal("41.43000000"));
    stubScore();

    EntityExchangeResult<String> result =
        post(
            "/api/v1/recommendations/query",
            "{\"country\":\"TR\",\"displayCurrency\":\"USD\"}",
            userToken);

    assertStatus(result, 200);
    ContractSchemas.assertConforms("RecommendationQueryResponse", result.getResponseBody());
    JsonNode meta = exact(result).get("meta");
    assertThat(meta.get("currency").asString()).isEqualTo("TRY");
    assertThat(meta.get("fx").get("status").asString()).isEqualTo("UNAVAILABLE");
    assertThat(meta.get("fx").get("rate").isNull()).isTrue();
    for (JsonNode item : exact(result).get("items")) {
      assertThat(item.get("cost").get("costPerDay").toString())
          .isEqualTo(item.get("cost").get("costPerDayNative").toString());
      assertThat(item.get("flags").toString())
          .endsWith("{\"code\":\"FX_UNAVAILABLE\",\"nutrientCodes\":[]}]");
    }
  }

  @Test
  void anUnsupportedCurrencyIsRejectedEverywhere() {
    assertProblem(
        post(
            "/api/v1/recommendations/query",
            "{\"country\":\"TR\",\"displayCurrency\":\"XYZ\"}",
            userToken),
        422,
        "fx_unsupported_currency");
    assertProblem(
        get("/api/v1/products?country=TR&displayCurrency=XYZ", userToken),
        422,
        "fx_unsupported_currency");
    assertProblem(get("/api/v1/plans?displayCurrency=XYZ", null), 422, "fx_unsupported_currency");
  }

  @Test
  void theExtraCurrencyIsSupportedAndNativeUntilARateExists() {
    EntityExchangeResult<String> result = get("/api/v1/plans?displayCurrency=" + EXTRA, null);

    assertStatus(result, 200);
    assertThat(exact(result).get("meta").get("fx").get("status").asString())
        .isEqualTo("UNAVAILABLE");
    assertThat(currencies.isSupported(EXTRA)).isTrue();
    assertThat(currencies.isSupported("XYZ")).isFalse();
    assertThat(currencies.isSupported(null)).isFalse();
  }

  // ---------- Compare ----------

  @Test
  void compareMoneyAndExplanationParametersAreConvertedAndTheCurrencyRewritten() {
    storeRate("TRY", "EUR", today().minusDays(1), TRY_EUR);
    UUID a = productId("GOLD-A");
    UUID b = productId("GOLD-B");
    double deltaCost = 1.666666666666666;
    double marginalRate = 44.580616226555186;
    double mRef = 2.6817473528400697;
    String answer =
        "{\"a\":"
            + side(a, 800.0, 13.333333333333334, 4.971882723856459)
            + ",\"b\":"
            + side(b, 900.0, 15.0, 5.009268179862648)
            + ",\"swapped\":false,\"currency\":\"TRY\",\"delta_cost\":"
            + deltaCost
            + ",\"delta_content\":0.03738545600618881,\"m_ref\":"
            + mRef
            + ",\"market_size\":2,\"marginal_rate\":"
            + marginalRate
            + ",\"ratio\":16.623719672676348,\"verdict\":\"NOT_WORTH_IT\",\"nutrient_deltas\":[],"
            + "\"explanation\":{\"key\":\"explain.verdict.not_worth_it\",\"params\":"
            + "{\"currency\":\"TRY\",\"deltaCostPerDay\":"
            + deltaCost
            + ",\"deltaContent\":0.03738545600618881,\"marginalRate\":"
            + marginalRate
            + ",\"mRef\":"
            + mRef
            + ",\"ratio\":16.623719672676348}}}";
    SCORING.stubFor(
        WireMock.post(urlEqualTo("/internal/v1/compare"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(answer)));

    EntityExchangeResult<String> result =
        post(
            "/api/v1/recommendations/compare",
            "{\"country\":\"TR\",\"baselineProductId\":\""
                + a
                + "\",\"candidateProductId\":\""
                + b
                + "\",\"displayCurrency\":\"EUR\"}",
            userToken);

    assertStatus(result, 200);
    ContractSchemas.assertConforms("CompareResponse", result.getResponseBody());
    JsonNode body = exact(result);
    assertThat(body.get("meta").get("fx").get("rate").toString()).isEqualTo("0.02087500");
    assertThat(body.get("deltaCost").decimalValue()).isEqualTo(converted(deltaCost, TRY_EUR));
    assertThat(body.get("marginalRate").decimalValue()).isEqualTo(converted(marginalRate, TRY_EUR));
    assertThat(body.get("mRef").decimalValue()).isEqualTo(converted(mRef, TRY_EUR));
    assertThat(body.get("productB").get("cost").get("costPerDay").decimalValue())
        .isEqualTo(converted(15.0, TRY_EUR));
    assertThat(body.get("productB").get("valueScore").toString())
        .isEqualTo(
            new BigDecimal(5.009268179862648 / 15.0)
                .setScale(4, RoundingMode.HALF_EVEN)
                .toString());
    JsonNode params = body.get("explanation").get("params");
    assertThat(params.get("currency").asString()).isEqualTo("EUR");
    assertThat(params.get("deltaCostPerDay").decimalValue())
        .isEqualTo(converted(deltaCost, TRY_EUR));
    assertThat(params.get("marginalRate").decimalValue())
        .isEqualTo(converted(marginalRate, TRY_EUR));
    assertThat(params.get("mRef").decimalValue()).isEqualTo(converted(mRef, TRY_EUR));
    assertThat(params.get("ratio").toString()).isEqualTo("16.6237");
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

  // ---------- Products and plans ----------

  @Test
  void productListAndDetailIncludingOffersAreConverted() {
    storeRate("TRY", "USD", today().minusDays(1), TRY_USD);
    UUID gold = productId("GOLD-A");

    EntityExchangeResult<String> detail =
        get("/api/v1/products/" + gold + "?country=TR&displayCurrency=USD", userToken);

    assertStatus(detail, 200);
    ContractSchemas.assertConforms("ProductDetailResponse", detail.getResponseBody());
    JsonNode body = exact(detail);
    assertThat(body.get("meta").get("fx").get("rate").toString()).isEqualTo("0.02413700");
    JsonNode offers = body.get("product").get("offers");
    assertThat(offers.size()).isPositive();
    for (JsonNode offer : offers) {
      assertThat(offer.get("currency").asString()).isEqualTo("USD");
      assertThat(offer.get("price").decimalValue())
          .isEqualTo(converted(offer.get("priceNative").decimalValue(), TRY_USD));
    }
    JsonNode cost = body.get("product").get("cost");
    assertThat(cost.get("effectivePrice").decimalValue())
        .isEqualTo(converted(cost.get("effectivePriceNative").decimalValue(), TRY_USD));

    EntityExchangeResult<String> list =
        get("/api/v1/products?country=TR&displayCurrency=USD", userToken);
    assertStatus(list, 200);
    ContractSchemas.assertConforms("ProductPage", list.getResponseBody());
    JsonNode listBody = exact(list);
    assertThat(listBody.get("meta").get("currency").asString()).isEqualTo("USD");
    for (JsonNode item : listBody.get("items")) {
      if (!item.get("cost").isNull()) {
        assertThat(item.get("cost").get("currency").asString()).isEqualTo("USD");
      }
    }
  }

  @Test
  void planPricesAreConvertedWithTheStoredRate() {
    storeRate("TRY", "USD", today().minusDays(1), TRY_USD);

    EntityExchangeResult<String> result = get("/api/v1/plans?displayCurrency=USD", null);

    assertStatus(result, 200);
    ContractSchemas.assertConforms("PlanList", result.getResponseBody());
    JsonNode body = exact(result);
    assertThat(body.get("meta").get("currency").asString()).isEqualTo("USD");
    assertThat(body.get("meta").get("fx").get("rate").toString()).isEqualTo("0.02413700");
    JsonNode price = null;
    for (JsonNode plan : body.get("items")) {
      if (!plan.get("price").isNull()) {
        price = plan.get("price");
      }
    }
    assertThat(price).isNotNull();
    assertThat(price.get("currency").asString()).isEqualTo("USD");
    assertThat(price.get("amount").decimalValue())
        .isEqualTo(converted(price.get("amountNative").decimalValue(), TRY_USD));
    assertThat(price.get("amount").decimalValue())
        .isEqualTo(new BigDecimal("199.00").multiply(TRY_USD).setScale(4, RoundingMode.HALF_EVEN));
  }
}
