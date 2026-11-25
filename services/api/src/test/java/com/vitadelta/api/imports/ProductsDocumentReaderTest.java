package com.vitadelta.api.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.catalog.AvailabilityStatus;
import com.vitadelta.api.catalog.DosageForm;
import com.vitadelta.api.catalog.ProductImport;
import com.vitadelta.api.catalog.unit.Unit;
import com.vitadelta.api.config.properties.ImportLimitsProperties;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProductsDocumentReaderTest {

  private static final Instant NOW = Instant.parse("2026-11-24T00:00:00Z");

  private final CatalogRegistry registry = TestRegistry.create();

  private static final String NUTRIENTS =
      "\"nutrients\": [{\"nutrient_code\": \"VIT_C\", \"amount_per_serving\": 80, \"unit\": \"mg\"},"
          + " {\"nutrient_code\": \"VIT_D\", \"amount_per_serving\": 400, \"unit\": \"iu\","
          + " \"form_code\": \"D3_CHOLECALCIFEROL\", \"form_label\": \"D3\"},"
          + " {\"nutrient_code\": \"VIT_A\", \"amount_per_serving\": 900, \"unit\": \"µg\","
          + " \"ul_amount_per_serving\": 300, \"form_code\": \"RETINOL\"}]";

  private static String product(String code, String extra) {
    return "{\"code\": \""
        + code
        + "\", \"brand\": \"Fixture Labs\", \"name\": \"Daily\", \"dosage_form\": \"TABLET\","
        + " \"units_per_container\": 60, \"units_per_serving\": 1, \"servings_per_day\": 1.5,"
        + " \"synthetic\": true, "
        + NUTRIENTS
        + ", \"availability\": [{\"country_code\": \"TR\", \"status\": \"AVAILABLE\"}]"
        + extra
        + "}";
  }

  private static String document(String... products) {
    return "{\"schema_version\": 1, \"products\": [" + String.join(",", products) + "]}";
  }

  private ProductsDocumentReader.Result read(String json) {
    return ProductsDocumentReader.read(json, registry, NOW);
  }

  private void rejected(String json, String code) {
    ProductsDocumentReader.Result result = read(json);
    assertThat(result.valid()).isFalse();
    assertThat(result.products()).isEmpty();
    assertThat(result.problems()).extracting(ImportProblem::code).contains(code);
  }

  @Test
  void readsAValidDocument() {
    ProductsDocumentReader.Result result =
        read(
            document(
                product(
                    "GOLD-A",
                    ", \"gtin\": \"12345678\", \"offers\": [{\"country_code\": \"TR\","
                        + " \"retailer\": \"Shop\", \"price\": 800.00, \"currency\": \"TRY\","
                        + " \"in_stock\": true, \"url\": \"https://a.example/x\","
                        + " \"checked_at\": \"2026-11-20T09:00:00Z\"}]")));
    assertThat(result.problems()).isEmpty();
    ProductImport product = result.products().getFirst();
    assertThat(product.details().code()).isEqualTo("GOLD-A");
    assertThat(product.details().dosageForm()).isEqualTo(DosageForm.TABLET);
    assertThat(product.details().servingsPerDay()).isEqualByComparingTo("1.5");
    assertThat(product.details().synthetic()).isTrue();
    assertThat(product.details().gtin()).isEqualTo("12345678");
    assertThat(product.nutrients()).hasSize(3);
    assertThat(product.nutrients().get(1).unit()).isEqualTo(Unit.IU);
    assertThat(product.nutrients().get(2).unit()).isEqualTo(Unit.MCG);
    assertThat(product.nutrients().get(2).ulAmountPerServing()).isEqualByComparingTo("300");
    assertThat(product.nutrients().get(0).formCode()).isEqualTo("UNKNOWN");
    assertThat(product.availability().getFirst().status()).isEqualTo(AvailabilityStatus.AVAILABLE);
    assertThat(product.offers())
        .singleElement()
        .satisfies(o -> {
          assertThat(o.productCode()).isEqualTo("GOLD-A");
          assertThat(o.price()).isEqualByComparingTo("800");
        });
  }

  @Test
  void readsARealProductWithProvenance() {
    String json =
        document(
            product(
                    "DSLD-1",
                    ", \"source\": \"DSLD\", \"source_ref\": \"271607\","
                        + " \"source_entry_date\": \"2024-03-01\"")
                .replace("\"synthetic\": true", "\"synthetic\": false"));
    ProductImport product = read(json).products().getFirst();
    assertThat(product.details().synthetic()).isFalse();
    assertThat(product.details().sourceRef()).isEqualTo("271607");
    assertThat(product.details().sourceEntryDate()).isEqualTo(LocalDate.of(2024, 3, 1));
  }

  @Test
  void realProductWithoutProvenanceIsRejected() {
    rejected(
        document(product("DSLD-2", "").replace("\"synthetic\": true", "\"synthetic\": false")),
        "missing_provenance");
  }

  @Test
  void syntheticDefaultsToFalse() {
    rejected(
        document(product("DSLD-3", "").replace("\"synthetic\": true, ", "")), "missing_provenance");
  }

  @Test
  void parserFailuresHaveAFixedMessageWithLineAndColumnOnly() {
    for (String json :
        List.of(
            "{\"schema_version\": 1,\n \"products\": [SECRET-FRAGMENT",
            "{\"schema_version\": 1, \"schema_version\": 1, \"SECRET-FRAGMENT\": []}")) {
      assertThat(read(json).problems())
          .singleElement()
          .satisfies(p -> {
            assertThat(p.code()).isEqualTo("invalid_json");
            assertThat(p.location()).isEqualTo("$");
            assertThat(p.message())
                .matches("the document is not valid JSON \\(line [0-9]+, column [0-9]+\\)")
                .doesNotContain("SECRET");
          });
    }
    assertThat(read("{\"products\": [" + "[".repeat(100) + "]".repeat(100) + "]}").problems())
        .singleElement()
        .satisfies(p ->
            assertThat(p.message())
                .startsWith(
                    "the document exceeds a parser limit (nesting depth or string length)"));
  }

  @Test
  void aStringLongerThanTheFieldLimitFailsTheDocumentWithoutEchoingIt() {
    String huge = "SECRET".repeat(1000);
    assertThat(read(document(product("GOLD-A", "").replace("Daily", huge))).problems())
        .singleElement()
        .satisfies(p -> {
          assertThat(p.code()).isEqualTo("invalid_json");
          assertThat(p.message()).doesNotContain("SECRET");
        });
  }

  @Test
  void moreProductsThanTheLimitFailTheDocumentBeforeValidation() {
    ImportLimitsProperties limits = new ImportLimitsProperties(10, 2, 2048, 100);
    String two = document(product("GOLD-A", ""), product("GOLD-B", ""));
    assertThat(ProductsDocumentReader.read(two, registry, NOW, limits).valid()).isTrue();
    ProductsDocumentReader.Result three =
        ProductsDocumentReader.read(
            document(product("GOLD-A", ""), product("GOLD-B", ""), "{\"bad\": 1}"),
            registry,
            NOW,
            limits);
    assertThat(three.problems())
        .extracting(ImportProblem::code)
        .containsExactly("too_many_records");
    assertThat(three.entries()).isEqualTo(3);
  }

  @Test
  void validationStopsAfterTheErrorBoundWithOneSummaryError() {
    ImportLimitsProperties limits = new ImportLimitsProperties(10, 50_000, 2048, 3);
    List<String> products = new java.util.ArrayList<>();
    for (int i = 0; i < 10_000; i++) {
      products.add("{\"bad\": " + i + "}");
    }
    ProductsDocumentReader.Result result =
        ProductsDocumentReader.read(
            document(products.toArray(String[]::new)), registry, NOW, limits);
    assertThat(result.problems()).hasSize(4);
    assertThat(result.problems().get(3).code()).isEqualTo("too_many_errors");
    assertThat(result.entries()).isEqualTo(10_000);
  }

  @Test
  void readsBytes() {
    byte[] bytes = document(product("GOLD-A", "")).getBytes(StandardCharsets.UTF_8);
    assertThat(ProductsDocumentReader.read(bytes, registry, NOW).valid()).isTrue();
    assertThat(ProductsDocumentReader.read(new byte[] {(byte) 0xFF}, registry, NOW).problems())
        .extracting(ImportProblem::code)
        .containsExactly("invalid_encoding");
  }

  @ParameterizedTest(name = "{1}")
  @CsvSource(
      delimiter = '|',
      value = {
        "not json|invalid_json",
        "[]|schema_violation",
        "{\"schema_version\": 2, \"products\": []}|schema_violation",
        "{\"products\": [], \"extra\": 1}|schema_violation",
        "{\"schema_version\": 1, \"products\": []}|schema_violation",
        "{\"schema_version\": 1, \"products\": {}}|schema_violation",
        "{\"schema_version\": 1, \"products\": [1]}|schema_violation",
        "{\"schema_version\": 1, \"schema_version\": 1, \"products\": []}|invalid_json"
      })
  void rejectsStructurallyInvalidDocuments(String json, String code) {
    rejected(json, code);
  }

  @ParameterizedTest(name = "{1}: {0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "\"code\": \"GOLD-A\"|\"code\": \"gold-a\"|schema_violation",
        "\"brand\": \"Fixture Labs\"|\"brand\": \"\"|schema_violation",
        "\"brand\": \"Fixture Labs\"|\"brand\": 5|schema_violation",
        "\"dosage_form\": \"TABLET\"|\"dosage_form\": \"PILL\"|schema_violation",
        "\"units_per_container\": 60|\"units_per_container\": 0|schema_violation",
        "\"units_per_container\": 60|\"units_per_container\": 1.5|schema_violation",
        "\"units_per_serving\": 1|\"units_per_serving\": 61|units_per_container_too_small",
        "\"servings_per_day\": 1.5|\"servings_per_day\": 0|schema_violation",
        "\"servings_per_day\": 1.5|\"servings_per_day\": 1.555|schema_violation",
        "\"servings_per_day\": 1.5|\"servings_per_day\": \"1\"|schema_violation",
        "\"synthetic\": true|\"synthetic\": \"yes\"|schema_violation",
        "\"synthetic\": true|\"synthetic\": true, \"gtin\": \"123\"|schema_violation",
        "\"synthetic\": true|\"synthetic\": true, \"source_entry_date\": \"2024-13-01\"|schema_violation",
        "\"synthetic\": true|\"synthetic\": true, \"colour\": \"red\"|schema_violation",
        "\"nutrient_code\": \"VIT_C\"|\"nutrient_code\": \"VIT_X\"|unknown_nutrient",
        "\"nutrient_code\": \"VIT_C\"|\"nutrient_code\": \"vit_c\"|schema_violation",
        "\"nutrient_code\": \"VIT_C\"|\"nutrient_code\": \"VIT_D\"|duplicate_nutrient",
        "\"unit\": \"mg\"|\"unit\": \"kg\"|invalid_unit",
        "\"unit\": \"mg\"|\"unit\": \"IU\"|iu_not_allowed",
        "µg\", \"ul_amount_per_serving\": 300, \"form_code\": \"RETINOL\"|IU\", \"form_code\": \"BETA_CAROTENE\"|unsupported_iu_form",
        "\"form_code\": \"RETINOL\"|\"form_code\": \"ANY\"|invalid_form_code",
        "\"form_code\": \"RETINOL\"|\"form_code\": \"retinol\"|schema_violation",
        "\"ul_amount_per_serving\": 300|\"ul_amount_per_serving\": 901|invalid_ul_amount",
        "\"amount_per_serving\": 80|\"amount_per_serving\": 0|invalid_amount",
        "\"amount_per_serving\": 80|\"amount_per_serving\": \"80\"|schema_violation",
        "\"amount_per_serving\": 80, |\"colour\": 1, \"amount_per_serving\": 80, |schema_violation",
        "\"country_code\": \"TR\"|\"country_code\": \"FR\"|unknown_country",
        "\"status\": \"AVAILABLE\"|\"status\": \"SOLD\"|schema_violation",
        "\"status\": \"AVAILABLE\"}|\"status\": \"AVAILABLE\"}, {\"country_code\": \"TR\", \"status\": \"NOT_SOLD\"}|duplicate_availability",
        "\"status\": \"AVAILABLE\"}|\"status\": \"AVAILABLE\", \"x\": 1}|schema_violation",
        "\"nutrients\": [|\"nutrients\": {}, \"n\": [|schema_violation",
        "\"availability\": [|\"availability\": [], \"a\": [|schema_violation"
      })
  void rejectsInvalidProducts(String search, String replacement, String code) {
    String original = document(product("GOLD-A", ""));
    assertThat(original).contains(search);
    rejected(original.replace(search, replacement), code);
  }

  @ParameterizedTest(name = "{1}: {0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "\"price\": 800.00|\"price\": 0|invalid_price",
        "\"price\": 800.00|\"price\": 800.001|invalid_price",
        "\"currency\": \"TRY\"|\"currency\": \"USD\"|currency_mismatch",
        "\"url\": \"https://a.example/x\"|\"url\": \"ftp://a.example/x\"|invalid_offer_url",
        "\"checked_at\": \"2026-11-20T09:00:00Z\"|\"checked_at\": \"2027-01-01T00:00:00Z\"|checked_at_in_future",
        "\"checked_at\": \"2026-11-20T09:00:00Z\"|\"checked_at\": \"yesterday\"|invalid_checked_at",
        "\"retailer\": \"Shop\"|\"retailer\": \" Shop\"|schema_violation",
        "\"in_stock\": true, |\"in_stock\": true, \"extra\": 1, |schema_violation",
        "\"in_stock\": true, |\"in_stock\": 1, |schema_violation",
        "\"retailer\": \"Shop\", |\"retailer\": \"Shop\", \"country_code\": \"XX\", |invalid_json"
      })
  void rejectsInvalidOffers(String search, String replacement, String code) {
    String offer =
        ", \"offers\": [{\"country_code\": \"TR\", \"retailer\": \"Shop\", \"price\": 800.00,"
            + " \"currency\": \"TRY\", \"in_stock\": true, \"url\": \"https://a.example/x\","
            + " \"checked_at\": \"2026-11-20T09:00:00Z\"}]";
    assertThat(offer).contains(search);
    rejected(document(product("GOLD-A", offer.replace(search, replacement))), code);
  }

  @Test
  void duplicateOfferKeyIsRejected() {
    String offer =
        "{\"country_code\": \"TR\", \"retailer\": \"Shop\", \"price\": 800.00,"
            + " \"currency\": \"TRY\", \"in_stock\": true, \"url\": \"https://a.example/x\","
            + " \"checked_at\": \"2026-11-20T09:00:00Z\"}";
    rejected(
        document(product("GOLD-A", ", \"offers\": [" + offer + "," + offer + "]")),
        "duplicate_offer_key");
    rejected(document(product("GOLD-A", ", \"offers\": {}")), "schema_violation");
  }

  @Test
  void duplicateProductCodeIsRejected() {
    ProductsDocumentReader.Result result =
        read(document(product("GOLD-A", ""), product("GOLD-A", "")));
    assertThat(result.problems())
        .singleElement()
        .satisfies(p -> {
          assertThat(p.code()).isEqualTo("duplicate_product_code");
          assertThat(p.location()).isEqualTo("products[1].code");
        });
  }

  @Test
  void missingRequiredPropertiesAreReportedWithTheirPath() {
    ProductsDocumentReader.Result result =
        read(document(product("GOLD-A", "").replace("\"brand\": \"Fixture Labs\", ", "")));
    assertThat(result.problems())
        .extracting(ImportProblem::location)
        .containsExactly("products[0].brand");
  }

  @Test
  void nonObjectNestedItemsAreRejected() {
    rejected(
        document(product("GOLD-A", "").replace("\"nutrients\": [", "\"nutrients\": [1, ")),
        "schema_violation");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {"4294967297", "-4294967295", "1.5", "\"1\"", "true"})
  void schemaVersionMustBeExactlyOne(String version) {
    rejected(
        document(product("GOLD-A", ""))
            .replace("\"schema_version\": 1", "\"schema_version\": " + version),
        "schema_violation");
  }

  @ParameterizedTest(name = "explicit null for {0}")
  @CsvSource({
    "offers",
    "source_ref",
    "source_entry_date",
    "synthetic",
    "code",
    "brand",
    "dosage_form",
    "units_per_container",
    "servings_per_day",
    "nutrients",
    "availability"
  })
  void explicitNullIsRejectedWhereTheSchemaDoesNotAllowIt(String property) {
    String json = document(product("GOLD-A", ", \"" + property + "\": null"));
    // A duplicate key would be a JSON error; remove the original property first when present.
    ProductsDocumentReader.Result result = read(withoutOriginal(json, property));
    assertThat(result.valid()).as(property).isFalse();
    assertThat(result.problems())
        .extracting(ImportProblem::location)
        .contains("products[0]." + property);
  }

  @ParameterizedTest(name = "explicit null for {0}")
  @CsvSource({"nutrient_code", "amount_per_serving", "unit"})
  void explicitNullIsRejectedForNutrientFields(String property) {
    String json =
        document(product("GOLD-A", ""))
            .replaceFirst("\"" + property + "\": [^,}]+", "\"" + property + "\": null");
    assertThat(read(json).valid()).as(property).isFalse();
  }

  @Test
  void explicitNullIsAcceptedWhereTheSchemaAllowsIt() {
    String json =
        document(product("GOLD-A", ", \"gtin\": null, \"source\": null"))
            .replace(
                "{\"nutrient_code\": \"VIT_C\", \"amount_per_serving\": 80, \"unit\": \"mg\"}",
                "{\"nutrient_code\": \"VIT_C\", \"amount_per_serving\": 80, \"unit\": \"mg\","
                    + " \"ul_amount_per_serving\": null, \"form_code\": null, \"form_label\": null}")
            .replace("\"status\": \"AVAILABLE\"}", "\"status\": \"AVAILABLE\", \"note\": null}");
    assertThat(read(json).problems()).isEmpty();
  }

  @Test
  void explicitNullIsRejectedForOfferFields() {
    String offer =
        ", \"offers\": [{\"country_code\": \"TR\", \"retailer\": \"Shop\", \"price\": 800.00,"
            + " \"currency\": \"TRY\", \"in_stock\": null, \"url\": \"https://a.example/x\","
            + " \"checked_at\": \"2026-11-20T09:00:00Z\"}]";
    rejected(document(product("GOLD-A", offer)), "schema_violation");
  }

  private static String withoutOriginal(String json, String property) {
    String key = "\"" + property + "\": ";
    int first = json.indexOf(key);
    int last = json.lastIndexOf(key);
    if (first == last) {
      return json;
    }
    int end = first + key.length();
    char open = json.charAt(end);
    if (open == '[') {
      int depth = 0;
      int i = end;
      do {
        char c = json.charAt(i);
        if (c == '[') {
          depth++;
        } else if (c == ']') {
          depth--;
        }
        i++;
      } while (depth > 0);
      end = i;
    } else {
      while (json.charAt(end) != ',' && json.charAt(end) != '}') {
        end++;
      }
    }
    if (json.charAt(end) == ',') {
      end++;
    }
    return json.substring(0, first) + json.substring(end).stripLeading();
  }
}
