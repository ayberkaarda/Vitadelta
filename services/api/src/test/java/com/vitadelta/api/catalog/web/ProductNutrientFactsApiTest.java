package com.vitadelta.api.catalog.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.identity.ContractSchemas;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;

/**
 * The regulatory facts of every nutrient row of {@code GET /products/{id}} ({@code reference} and
 * {@code upperLimit}, contract schemas {@code NutrientReference} and {@code NutrientUpperLimit})
 * against the seed data: the requested country's reference set and UL set, the UL matching rule,
 * {@code null} when no value applies, one rounding to 4 decimals and no {@code EXCEEDS_UL} flag.
 */
class ProductNutrientFactsApiTest extends CatalogApiTest {

  private EntityExchangeResult<String> detail(String code, String country) {
    EntityExchangeResult<String> result =
        get("/api/v1/products/" + productId(code) + "?country=" + country, null);
    assertStatus(result, 200);
    ContractSchemas.assertConforms("ProductDetailResponse", result.getResponseBody());
    return result;
  }

  private static JsonNode nutrient(JsonNode product, String code) {
    for (JsonNode n : product.get("nutrients")) {
      if (n.get("code").asString().equals(code)) {
        return n;
      }
    }
    throw new AssertionError("no nutrient " + code + " in " + product.get("code"));
  }

  private String note(String set, String nutrient, String form) {
    return jdbc.sql(
            "SELECT note FROM upper_limit WHERE ul_set_code = ? AND nutrient_code = ?"
                + " AND form_code = ?")
        .params(set, nutrient, form)
        .query(String.class)
        .single();
  }

  @Test
  void zincAboveTheUpperLimitShowsTheReferencePercentAndTheExceededLimit() {
    EntityExchangeResult<String> result = detail("ZN40", "TR");
    // Written with the 4 decimals of the single rounding (the parsed tree drops trailing zeros).
    assertThat(result.getResponseBody())
        .contains("\"percentOfReference\":400.0000")
        .contains("\"ulAmountPerDay\":40.0000");
    JsonNode product = json(result).get("product");
    JsonNode zinc = nutrient(product, "ZINC");

    JsonNode reference = zinc.get("reference");
    assertThat(reference.get("amount").decimalValue()).isEqualByComparingTo("10.0000");
    assertThat(reference.get("unit").asString()).isEqualTo("mg");
    assertThat(reference.get("setCode").asString()).isEqualTo("EU_NRV");
    assertThat(reference.get("source").asString()).isEqualTo("REGULATORY");
    assertThat(reference.get("percentOfReference").decimalValue()).isEqualByComparingTo("400.0000");

    JsonNode limit = zinc.get("upperLimit");
    assertThat(limit.get("amount").decimalValue()).isEqualByComparingTo("25.0000");
    assertThat(limit.get("unit").asString()).isEqualTo("mg");
    assertThat(limit.get("basis").asString()).isEqualTo("TOTAL");
    assertThat(limit.get("setCode").asString()).isEqualTo("EFSA");
    assertThat(limit.get("formCode").asString()).isEqualTo("ANY");
    assertThat(limit.get("note").asString()).isEqualTo(note("EFSA", "ZINC", "ANY"));
    assertThat(limit.get("ulAmountPerDay").decimalValue()).isEqualByComparingTo("40.0000");
    assertThat(limit.get("exceeded").asBoolean()).isTrue();

    // Product detail never emits EXCEEDS_UL; the scoring service's flag is not derived here.
    assertThat(product.get("flags").toString()).doesNotContain("EXCEEDS_UL");
  }

  @Test
  void aNutrientWithoutReferenceOrLimitCarriesExplicitNulls() {
    JsonNode product = json(detail("NN-GUMMY", "TR")).get("product");
    JsonNode choline = nutrient(product, "CHOLINE");
    assertThat(choline.has("reference")).isTrue();
    assertThat(choline.get("reference").isNull()).as("CHOLINE has no EU_NRV value").isTrue();
    assertThat(choline.has("upperLimit")).isTrue();
    assertThat(choline.get("upperLimit").isNull()).as("EFSA sets no choline UL").isTrue();

    JsonNode vitaminA = nutrient(product, "VIT_A");
    assertThat(vitaminA.get("reference").get("percentOfReference").decimalValue())
        .isEqualByComparingTo("37.5000");
    assertThat(vitaminA.get("upperLimit").get("formCode").asString()).isEqualTo("RETINYL_ACETATE");
    assertThat(vitaminA.get("upperLimit").get("exceeded").asBoolean()).isFalse();
  }

  @Test
  void anExemptFormHasNoUpperLimitButKeepsItsReference() {
    JsonNode product = json(detail("FL-VISION-30", "TR")).get("product");
    JsonNode vitaminA = nutrient(product, "VIT_A");
    assertThat(vitaminA.get("formCode").asString()).isEqualTo("BETA_CAROTENE");
    assertThat(vitaminA.get("upperLimit").isNull()).as("beta-carotene row is exempt").isTrue();
    assertThat(vitaminA.get("reference").get("amount").decimalValue())
        .isEqualByComparingTo("800.0000");
    assertThat(vitaminA.get("reference").get("unit").asString()).isEqualTo("mcg");
    assertThat(vitaminA.get("reference").get("percentOfReference").decimalValue())
        .isEqualByComparingTo("50.0000");

    JsonNode coq10 = nutrient(product, "COQ10");
    assertThat(coq10.get("reference").isNull()).isTrue();
    assertThat(coq10.get("upperLimit").isNull()).isTrue();
  }

  @Test
  void theUlRelevantAmountIsComparedWhenItDiffersFromTheScoredAmount() {
    JsonNode product = json(detail("NN-ESSENTIAL", "TR")).get("product");
    JsonNode vitaminA = nutrient(product, "VIT_A");
    assertThat(vitaminA.get("amountPerDay").decimalValue()).isEqualByComparingTo("900.0000");
    JsonNode limit = vitaminA.get("upperLimit");
    assertThat(limit.get("formCode").asString()).isEqualTo("RETINYL_ACETATE");
    assertThat(limit.get("amount").decimalValue()).isEqualByComparingTo("3000.0000");
    assertThat(limit.get("unit").asString()).isEqualTo("mcg");
    assertThat(limit.get("ulAmountPerDay").decimalValue()).isEqualByComparingTo("300.0000");
    assertThat(limit.get("exceeded").asBoolean()).isFalse();
    assertThat(limit.get("note").asString()).isEqualTo(note("EFSA", "VIT_A", "RETINYL_ACETATE"));
    assertThat(vitaminA.get("reference").get("percentOfReference").decimalValue())
        .isEqualByComparingTo("112.5000");
  }

  @Test
  void everyNutrientRowOfEveryProductInTheMarketCarriesBothKeys() {
    List<String> checked = new ArrayList<>();
    for (String country : List.of("TR", "DE", "GB", "US")) {
      List<String> ids =
          jdbc.sql(
                  "SELECT p.code FROM product p JOIN product_availability a ON a.product_id = p.id"
                      + " WHERE a.country_code = ? AND a.status = 'AVAILABLE' AND p.synthetic"
                      + " ORDER BY p.code LIMIT 5")
              .param(country)
              .query(String.class)
              .list();
      for (String code : ids) {
        JsonNode product = json(detail(code, country)).get("product");
        String setCode =
            jdbc.sql("SELECT reference_set_code FROM country WHERE code = ?")
                .param(country)
                .query(String.class)
                .single();
        String ulSet =
            jdbc.sql("SELECT ul_set_code FROM country WHERE code = ?")
                .param(country)
                .query(String.class)
                .single();
        for (JsonNode n : product.get("nutrients")) {
          assertThat(n.has("reference") && n.has("upperLimit")).as(code).isTrue();
          if (!n.get("reference").isNull()) {
            assertThat(n.get("reference").get("setCode").asString()).isEqualTo(setCode);
            assertThat(n.get("reference").get("unit").asString())
                .isEqualTo(n.get("unit").asString());
          }
          if (!n.get("upperLimit").isNull()) {
            assertThat(n.get("upperLimit").get("setCode").asString()).isEqualTo(ulSet);
            assertThat(n.get("upperLimit").get("unit").asString())
                .isEqualTo(n.get("unit").asString());
          }
        }
        checked.add(country + ":" + code);
      }
    }
    assertThat(checked).hasSizeGreaterThan(4);
  }
}
