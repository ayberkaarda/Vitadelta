package com.vitadelta.api.catalog.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.identity.ContractSchemas;
import com.vitadelta.api.identity.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;

/**
 * Public reference and product endpoints against the seed data (sections 7.1 to 7.4, 9.1): contract
 * shapes, availability, effective price, flags, display currency, validation and the anonymous rate
 * limit.
 */
class ProductApiTest extends CatalogApiTest {

  private static List<String> texts(JsonNode array, String field) {
    List<String> values = new ArrayList<>();
    array.forEach(n -> values.add(n.get(field).asString()));
    return values;
  }

  private static JsonNode flag(JsonNode flags, String code) {
    for (JsonNode flag : flags) {
      if (flag.get("code").asString().equals(code)) {
        return flag;
      }
    }
    return null;
  }

  @Test
  void referenceDataIsPublicAndMatchesTheContract() {
    EntityExchangeResult<String> countries = get("/api/v1/countries", null);
    assertStatus(countries, 200);
    ContractSchemas.assertConforms("CountryList", countries.getResponseBody());
    JsonNode items = json(countries).get("items");
    assertThat(texts(items, "code")).containsSubsequence("DE", "GB", "TR", "US").isSorted();
    JsonNode tr = items.get(texts(items, "code").indexOf("TR"));
    assertThat(tr.get("currency").asString()).isEqualTo("TRY");
    assertThat(tr.get("referenceSetCode").asString()).isEqualTo("EU_NRV");
    assertThat(tr.get("ulSetCode").asString()).isEqualTo("EFSA");
    assertThat(tr.get("defaultLocale").asString()).isEqualTo("tr");

    EntityExchangeResult<String> nutrients = get("/api/v1/nutrients", null);
    assertStatus(nutrients, 200);
    ContractSchemas.assertConforms("NutrientList", nutrients.getResponseBody());
    JsonNode registry = json(nutrients).get("items");
    int count = jdbc.sql("SELECT count(*) FROM nutrient").query(Integer.class).single();
    assertThat(registry.size()).isEqualTo(count);
    List<Integer> sortOrders = new ArrayList<>();
    registry.forEach(n -> sortOrders.add(n.get("sortOrder").asInt()));
    assertThat(sortOrders).isSorted();
    for (JsonNode n : registry) {
      switch (n.get("code").asString()) {
        case "VIT_D" -> {
          assertThat(texts(n.get("forms"), "code"))
              .containsExactly("D2_ERGOCALCIFEROL", "D3_CHOLECALCIFEROL");
          assertThat(n.get("referenceSetCodes").toString()).isEqualTo("[\"EU_NRV\",\"US_DV\"]");
          assertThat(n.get("canonicalUnit").asString()).isEqualTo("mcg");
        }
        case "CHOLINE" ->
            assertThat(n.get("referenceSetCodes").toString()).isEqualTo("[\"US_DV\"]");
        case "COQ10" -> {
          assertThat(n.get("referenceSetCodes").isEmpty()).isTrue();
          assertThat(texts(n.get("forms"), "code")).containsExactly("UBIQUINOL", "UBIQUINONE");
          assertThat(n.get("category").asString()).isEqualTo("COENZYME");
        }
        default -> assertThat(n.get("forms").isArray()).isTrue();
      }
    }

    EntityExchangeResult<String> weights = get("/api/v1/weights/defaults", null);
    assertStatus(weights, 200);
    ContractSchemas.assertConforms("DefaultWeights", weights.getResponseBody());
    assertThat(json(weights).toString())
        .isEqualTo(
            "{\"categories\":{\"VITAMIN\":1.0,\"MINERAL\":1.0,\"COENZYME\":0.8,\"OTHER\":0.5},"
                + "\"categoryRange\":{\"min\":0.0,\"max\":2.0},\"overrideDefault\":1.0,"
                + "\"overrideRange\":{\"min\":0.0,\"max\":3.0},\"lambdaDefault\":0.5,"
                + "\"lambdaRange\":{\"min\":0.0,\"max\":1.0},\"engine\":null}");

    EntityExchangeResult<String> legal = get("/api/v1/legal", null);
    assertStatus(legal, 200);
    ContractSchemas.assertConforms("LegalInfo", legal.getResponseBody());
    assertThat(json(legal).get("privacyContact").asString()).isEqualTo("privacy@vitadelta.test");
  }

  @Test
  void searchFindsInMarketProductsByCaseInsensitiveSubstring() {
    EntityExchangeResult<String> result = get("/api/v1/products?country=TR&q=gOlD", null);
    assertStatus(result, 200);
    ContractSchemas.assertConforms("ProductPage", result.getResponseBody());
    JsonNode body = json(result);
    assertThat(texts(body.get("items"), "code")).containsExactly("GOLD-A", "GOLD-B");
    JsonNode meta = body.get("meta");
    assertThat(meta.get("country").asString()).isEqualTo("TR");
    assertThat(meta.get("nativeCurrency").asString()).isEqualTo("TRY");
    assertThat(meta.get("currency").asString()).isEqualTo("TRY");
    assertThat(meta.get("fx").get("status").asString()).isEqualTo("OK");
    assertThat(meta.get("fx").get("rate").decimalValue()).isEqualByComparingTo("1");
    assertThat(meta.get("total").asInt()).isEqualTo(2);
    assertThat(meta.get("page").asInt()).isEqualTo(1);
    assertThat(meta.get("pageSize").asInt()).isEqualTo(20);

    JsonNode goldA = body.get("items").get(0);
    assertThat(goldA.get("brand").asString()).isEqualTo("Fixture Labs");
    assertThat(goldA.get("provenance").get("synthetic").asBoolean()).isTrue();
    JsonNode cost = goldA.get("cost");
    assertThat(cost.get("effectivePrice").decimalValue()).isEqualByComparingTo("800.0000");
    assertThat(cost.get("effectivePriceNative").decimalValue()).isEqualByComparingTo("800");
    assertThat(cost.get("costPerDay").toString()).isEqualTo("13.3333");
    assertThat(cost.get("costPerDayNative").toString()).isEqualTo("13.3333");
    assertThat(cost.get("servingsPerContainer").decimalValue()).isEqualByComparingTo("60");
    assertThat(cost.get("daysSupply").decimalValue()).isEqualByComparingTo("60");
    assertThat(cost.get("priceStale").asBoolean()).isFalse();
    assertThat(cost.get("outOfStock").asBoolean()).isFalse();
    assertThat(goldA.get("flags").isEmpty()).isTrue();

    assertThat(
            json(get("/api/v1/products?country=TR&q=gold-b", null))
                .get("meta")
                .get("total")
                .asInt())
        .isEqualTo(1);
    assertThat(json(get("/api/v1/products?country=DE&q=gold", null)).get("items").isEmpty())
        .isTrue();
    assertThat(json(get("/api/v1/products?country=TR&q=%25", null)).get("items").isEmpty())
        .isTrue();
  }

  @Test
  void listingsContainOnlyAvailableProductsInBrandNameCodeOrderAcrossPages() {
    int available =
        jdbc.sql(
                "SELECT count(*) FROM product_availability WHERE country_code = 'TR'"
                    + " AND status = 'AVAILABLE'")
            .query(Integer.class)
            .single();
    List<String> codes = new ArrayList<>();
    List<String> sortKeys = new ArrayList<>();
    for (int page = 1; page <= 10; page++) {
      JsonNode body = json(get("/api/v1/products?country=TR&pageSize=4&page=" + page, null));
      assertThat(body.get("meta").get("total").asInt()).isEqualTo(available);
      if (body.get("items").isEmpty()) {
        break;
      }
      assertThat(body.get("items").size()).isLessThanOrEqualTo(4);
      body.get("items")
          .forEach(i -> {
            codes.add(i.get("code").asString());
            sortKeys.add(
                i.get("brand").asString()
                    + "\u0000"
                    + i.get("name").asString()
                    + "\u0000"
                    + i.get("code").asString());
          });
    }
    assertThat(codes).hasSize(available).doesNotHaveDuplicates();
    assertThat(codes).contains("GOLD-A", "ZN40").doesNotContain("MV-ACTIVE-50", "RD-ADULT");
    assertThat(sortKeys).isSorted();
    JsonNode beyond = json(get("/api/v1/products?country=TR&pageSize=100&page=9", null));
    assertThat(beyond.get("items").isEmpty()).isTrue();
    assertThat(beyond.get("meta").get("total").asInt()).isEqualTo(available);
  }

  @Test
  void detailListsOffersAndTheSelectedOffer() {
    UUID id = productId("GOLD-A");
    EntityExchangeResult<String> result = get("/api/v1/products/" + id + "?country=TR", null);
    assertStatus(result, 200);
    ContractSchemas.assertConforms("ProductDetailResponse", result.getResponseBody());
    JsonNode product = json(result).get("product");
    assertThat(product.get("id").asString()).isEqualTo(id.toString());
    assertThat(product.get("availability").get("status").asString()).isEqualTo("AVAILABLE");
    assertThat(product.get("saved").asBoolean()).isFalse();
    assertThat(product.get("alerts").isEmpty()).isTrue();
    assertThat(product.get("synthetic").asBoolean()).isTrue();
    assertThat(texts(product.get("offers"), "retailer"))
        .containsExactly("Fixture Pharmacy", "Anatolia Wellness Depot");
    assertThat(product.get("offers").get(1).get("price").decimalValue())
        .isEqualByComparingTo("845.50");
    assertThat(product.get("selectedOffer").get("retailer").asString())
        .isEqualTo("Fixture Pharmacy");
    assertThat(product.get("selectedOffer").get("url").asString())
        .isEqualTo("https://fixture-pharmacy.example/tr/p/gold-a");
    assertThat(texts(product.get("nutrients"), "code"))
        .containsExactly("MAGNESIUM", "VIT_B12", "VIT_C", "VIT_D", "ZINC");
    JsonNode vitD = product.get("nutrients").get(3);
    assertThat(vitD.get("formCode").asString()).isEqualTo("D3_CHOLECALCIFEROL");
    assertThat(vitD.get("unit").asString()).isEqualTo("mcg");
    assertThat(vitD.get("amountPerDay").decimalValue()).isEqualByComparingTo("10");
    assertThat(product.get("nutrients").get(2).get("formCode").asString()).isEqualTo("UNKNOWN");
    assertThat(product.get("nutrients").get(2).get("formLabel").asString())
        .isEqualTo("ascorbic acid");

    // A signed-in user gets the same Phase 4 answer.
    JsonNode asUser = json(get("/api/v1/products/" + id + "?country=TR", token(Role.USER)));
    assertThat(asUser.get("product").get("saved").asBoolean()).isFalse();
  }

  @Test
  void iuAmountsArePresentedInCanonicalUnits() {
    UUID id = productId("MV-UBIQ-200");
    JsonNode product = json(get("/api/v1/products/" + id + "?country=TR", null)).get("product");
    JsonNode vitE = null;
    for (JsonNode n : product.get("nutrients")) {
      if (n.get("code").asString().equals("VIT_E")) {
        vitE = n;
      }
    }
    assertThat(vitE).isNotNull();
    assertThat(vitE.get("unit").asString()).isEqualTo("mg");
    assertThat(vitE.get("conversionAssumed").asBoolean()).isFalse();
    assertThat(vitE.get("amountPerDay").decimalValue())
        .isEqualByComparingTo(
            vitE.get("amountPerServing").decimalValue().multiply(new java.math.BigDecimal("2")));
  }

  @Test
  void productsOutsideTheMarketAreNotAvailable() {
    String goldA = productId("GOLD-A").toString();
    EntityExchangeResult<String> noRow = get("/api/v1/products/" + goldA + "?country=US", null);
    assertProblem(noRow, 404, "product_not_available_in_country");
    assertThat(json(noRow).get("detail").asString()).isEqualTo("The product is not sold in US.");
    assertProblem(
        get("/api/v1/products/" + productId("MV-ACTIVE-50") + "?country=TR", null),
        404,
        "product_not_available_in_country");
    assertProblem(
        get("/api/v1/products/" + productId("RD-MEN-50") + "?country=GB", null),
        404,
        "product_not_available_in_country");
    assertProblem(
        get("/api/v1/products/" + UUID.randomUUID() + "?country=TR", null),
        404,
        "product_not_found");
    assertProblem(get("/api/v1/products/GOLD-A?country=TR", null), 404, "product_not_found");
  }

  @Test
  void outOfStockAndStaleOffersAreFlagged() {
    JsonNode gummy =
        json(get("/api/v1/products/" + productId("BH-FAMILY-GUMMY") + "?country=TR", null))
            .get("product");
    assertThat(gummy.get("cost").get("outOfStock").asBoolean()).isTrue();
    assertThat(gummy.get("cost").get("priceStale").asBoolean()).isTrue();
    assertThat(gummy.get("cost").get("effectivePrice").decimalValue())
        .isEqualByComparingTo("329.90");
    assertThat(texts(gummy.get("flags"), "code")).containsExactly("PRICE_STALE", "OUT_OF_STOCK");
    assertThat(gummy.get("selectedOffer").get("retailer").asString()).isEqualTo("Fixture Pharmacy");

    JsonNode iron =
        json(get("/api/v1/products/" + productId("FL-IRON") + "?country=TR", null)).get("product");
    assertThat(iron.get("cost").get("outOfStock").asBoolean()).isFalse();
    assertThat(iron.get("cost").get("priceStale").asBoolean()).isTrue();
    assertThat(texts(iron.get("flags"), "code")).containsExactly("PRICE_STALE");
    assertThat(iron.get("offers").get(0).get("priceStale").asBoolean()).isTrue();
    JsonNode ironGb =
        json(get("/api/v1/products/" + productId("FL-IRON") + "?country=GB", null)).get("product");
    assertThat(ironGb.get("offers").get(0).get("priceStale").asBoolean()).isFalse();
    assertThat(ironGb.get("offers").get(0).get("currency").asString()).isEqualTo("GBP");
  }

  @Test
  void aSupportedForeignCurrencyKeepsNativeMoneyUntilRatesExist() {
    EntityExchangeResult<String> result =
        get("/api/v1/products?country=TR&q=gold&displayCurrency=USD", null);
    assertStatus(result, 200);
    ContractSchemas.assertConforms("ProductPage", result.getResponseBody());
    JsonNode body = json(result);
    JsonNode fx = body.get("meta").get("fx");
    assertThat(body.get("meta").get("currency").asString()).isEqualTo("TRY");
    assertThat(fx.get("base").asString()).isEqualTo("TRY");
    assertThat(fx.get("quote").asString()).isEqualTo("USD");
    assertThat(fx.get("rate").isNull()).isTrue();
    assertThat(fx.get("status").asString()).isEqualTo("UNAVAILABLE");
    JsonNode item = body.get("items").get(0);
    assertThat(item.get("cost").get("currency").asString()).isEqualTo("TRY");
    assertThat(item.get("cost").get("effectivePrice").decimalValue()).isEqualByComparingTo("800");
    assertThat(flag(item.get("flags"), "FX_UNAVAILABLE")).isNotNull();

    EntityExchangeResult<String> detail =
        get("/api/v1/products/" + productId("GOLD-A") + "?country=TR&displayCurrency=EUR", null);
    ContractSchemas.assertConforms("ProductDetailResponse", detail.getResponseBody());
    assertThat(json(detail).get("meta").get("fx").get("status").asString())
        .isEqualTo("UNAVAILABLE");
    assertThat(texts(json(detail).get("product").get("flags"), "code"))
        .containsExactly("FX_UNAVAILABLE");

    JsonNode identity = json(get("/api/v1/products?country=TR&q=gold&displayCurrency=TRY", null));
    assertThat(identity.get("meta").get("fx").get("status").asString()).isEqualTo("OK");
  }

  @Test
  void theUserPreferenceIsTheDefaultDisplayCurrency() {
    String token = token(Role.USER);
    jdbc.sql("UPDATE app_user SET display_currency = 'GBP' WHERE email LIKE ?")
        .param("%@" + DOMAIN)
        .update();
    JsonNode meta = json(get("/api/v1/products?country=TR&q=gold", token)).get("meta");
    assertThat(meta.get("fx").get("quote").asString()).isEqualTo("GBP");
    assertThat(meta.get("fx").get("status").asString()).isEqualTo("UNAVAILABLE");
    JsonNode explicit =
        json(get("/api/v1/products?country=TR&q=gold&displayCurrency=TRY", token)).get("meta");
    assertThat(explicit.get("fx").get("status").asString()).isEqualTo("OK");
  }

  @Test
  void invalidParametersAreRejected() {
    assertProblem(
        get("/api/v1/products?country=TR&displayCurrency=QQQ", null),
        422,
        "fx_unsupported_currency");
    assertProblem(
        get("/api/v1/products/" + productId("GOLD-A") + "?country=TR&displayCurrency=QQQ", null),
        422,
        "fx_unsupported_currency");
    EntityExchangeResult<String> lower =
        get("/api/v1/products?country=tr&displayCurrency=usd", null);
    assertProblem(lower, 422, "validation_failed");
    assertThat(texts(json(lower).get("errors"), "field"))
        .containsExactly("country", "displayCurrency");
    EntityExchangeResult<String> missing = get("/api/v1/products", null);
    assertProblem(missing, 422, "validation_failed");
    assertThat(json(missing).get("errors").get(0).get("code").asString()).isEqualTo("required");
    assertProblem(get("/api/v1/products?country=XX", null), 422, "country_not_found");
    assertProblem(
        get("/api/v1/products/" + productId("GOLD-A") + "?country=XX", null),
        422,
        "country_not_found");
    assertProblem(get("/api/v1/products/" + productId("GOLD-A"), null), 422, "validation_failed");
    assertProblem(get("/api/v1/products?country=TR&q=%00x", null), 422, "validation_failed");
    assertProblem(get("/api/v1/products?country=TR&q=", null), 422, "validation_failed");
    assertProblem(
        get("/api/v1/products?country=TR&q=" + "a".repeat(201), null), 422, "validation_failed");
    assertProblem(get("/api/v1/products?country=TR&pageSize=101", null), 422, "validation_failed");
    assertProblem(get("/api/v1/products?country=TR&page=0", null), 422, "validation_failed");
    assertProblem(get("/api/v1/products?country=TR&page=x", null), 422, "validation_failed");
  }

  @Test
  void anonymousProductReadsAreLimitedPerClientIp() {
    String path = "/api/v1/products?country=TR&q=gold";
    // 120 per minute, refilled gradually: the first 120 pass, a burst soon after is limited.
    int passed = 0;
    EntityExchangeResult<String> limited = null;
    for (int i = 0; i < 400 && limited == null; i++) {
      EntityExchangeResult<String> result = get(path, null);
      if (result.getStatus().value() == 429) {
        limited = result;
      } else {
        assertStatus(result, 200);
        passed++;
      }
    }
    assertThat(passed).isGreaterThanOrEqualTo(120);
    assertThat(limited).isNotNull();
    assertProblem(limited, 429, "rate_limited");
    assertThat(limited.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
    assertProblem(
        get("/api/v1/products/" + productId("GOLD-A") + "?country=TR", null), 429, "rate_limited");
    // Signed-in callers are not limited by the anonymous policy, other addresses keep their own.
    assertStatus(get(path, token(Role.USER)), 200);
    ip = randomIp();
    assertStatus(get(path, null), 200);
  }
}
