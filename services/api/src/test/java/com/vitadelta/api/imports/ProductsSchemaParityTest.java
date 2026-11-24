package com.vitadelta.api.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.vitadelta.api.support.TestData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Java products reader and a JSON Schema 2020-12 validator applied to {@code
 * contracts/schemas/import-products.schema.json} agree: every invalid sample is rejected by both,
 * and the fixture document is accepted by both.
 */
class ProductsSchemaParityTest {

  private static final Instant NOW = Instant.parse("2026-11-24T00:00:00Z");
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static Schema schema;
  private static Path repositoryRoot;

  @BeforeAll
  static void loadSchema() throws IOException {
    repositoryRoot = TestData.dataDir().toAbsolutePath().getParent();
    String text =
        Files.readString(repositoryRoot.resolve("contracts/schemas/import-products.schema.json"));
    schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(text);
  }

  private static final String VALID_PRODUCT =
      "{\"code\": \"GOLD-A\", \"brand\": \"Fixture Labs\", \"name\": \"Daily\","
          + " \"dosage_form\": \"TABLET\", \"units_per_container\": 60, \"units_per_serving\": 1,"
          + " \"servings_per_day\": 1, \"synthetic\": true,"
          + " \"nutrients\": [{\"nutrient_code\": \"VIT_C\", \"amount_per_serving\": 80,"
          + " \"unit\": \"mg\"}],"
          + " \"availability\": [{\"country_code\": \"TR\", \"status\": \"AVAILABLE\"}],"
          + " \"offers\": [{\"country_code\": \"TR\", \"retailer\": \"Shop\", \"price\": 800,"
          + " \"currency\": \"TRY\", \"in_stock\": true, \"url\": \"https://a.example/x\","
          + " \"checked_at\": \"2026-11-20T09:00:00Z\"}]}";

  private static String document(String product) {
    return "{\"schema_version\": 1, \"products\": [" + product + "]}";
  }

  static Stream<Arguments> invalidSamples() {
    String valid = document(VALID_PRODUCT);
    return Stream.of(
        Arguments.of(
            "schema_version overflow",
            valid.replace("\"schema_version\": 1", "\"schema_version\": 4294967297")),
        Arguments.of(
            "schema_version 2", valid.replace("\"schema_version\": 1", "\"schema_version\": 2")),
        Arguments.of(
            "unknown top-level property",
            valid.replace("{\"schema_version\"", "{\"x\": 1, \"schema_version\"")),
        Arguments.of("empty products", "{\"schema_version\": 1, \"products\": []}"),
        Arguments.of("offers null", valid.replace("\"offers\": [", "\"offers\": null, \"o\": [")),
        Arguments.of(
            "source_ref null",
            valid.replace("\"synthetic\": true", "\"synthetic\": true, \"source_ref\": null")),
        Arguments.of(
            "source_entry_date null",
            valid.replace(
                "\"synthetic\": true", "\"synthetic\": true, \"source_entry_date\": null")),
        Arguments.of("synthetic null", valid.replace("\"synthetic\": true", "\"synthetic\": null")),
        Arguments.of(
            "unknown product property",
            valid.replace("\"synthetic\": true", "\"synthetic\": true, \"colour\": \"red\"")),
        Arguments.of("code pattern", valid.replace("GOLD-A", "gold-a")),
        Arguments.of("brand empty", valid.replace("\"Fixture Labs\"", "\"\"")),
        Arguments.of("dosage form", valid.replace("TABLET", "PILL")),
        Arguments.of(
            "units_per_container 0",
            valid.replace("\"units_per_container\": 60", "\"units_per_container\": 0")),
        Arguments.of(
            "servings_per_day 0",
            valid.replace("\"servings_per_day\": 1", "\"servings_per_day\": 0")),
        Arguments.of(
            "gtin", valid.replace("\"synthetic\": true", "\"synthetic\": true, \"gtin\": \"123\"")),
        Arguments.of(
            "nutrients empty",
            valid.replaceFirst("\"nutrients\": \\[[^\\]]*\\]", "\"nutrients\": []")),
        Arguments.of("unit enum", valid.replace("\"unit\": \"mg\"", "\"unit\": \"kg\"")),
        Arguments.of(
            "amount 0", valid.replace("\"amount_per_serving\": 80", "\"amount_per_serving\": 0")),
        Arguments.of(
            "form ANY",
            valid.replace("\"unit\": \"mg\"", "\"unit\": \"mg\", \"form_code\": \"ANY\"")),
        Arguments.of(
            "nutrient_code null",
            valid.replace("\"nutrient_code\": \"VIT_C\"", "\"nutrient_code\": null")),
        Arguments.of("availability status", valid.replace("AVAILABLE", "SOLD")),
        Arguments.of("price 0", valid.replace("\"price\": 800", "\"price\": 0")),
        Arguments.of("retailer whitespace", valid.replace("\"Shop\"", "\" Shop\"")),
        Arguments.of("in_stock null", valid.replace("\"in_stock\": true", "\"in_stock\": null")),
        Arguments.of("url scheme", valid.replace("https://a.example/x", "ftp://a.example/x")),
        Arguments.of("currency pattern", valid.replace("\"TRY\"", "\"try\"")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidSamples")
  void bothRejectInvalidSamples(String name, String json) {
    assertThat(schema.validate(MAPPER.readTree(json))).as("schema: " + name).isNotEmpty();
    assertThat(ProductsDocumentReader.read(json, TestRegistry.create(), NOW).valid())
        .as("reader: " + name)
        .isFalse();
  }

  @Test
  void bothAcceptTheValidSample() {
    String json = document(VALID_PRODUCT);
    assertThat(schema.validate(MAPPER.readTree(json))).isEmpty();
    assertThat(ProductsDocumentReader.read(json, TestRegistry.create(), NOW).problems()).isEmpty();
  }

  @Test
  void schemaAcceptsTheFixtureAndRealDocuments() throws IOException {
    for (String file :
        new String[] {"fixtures/products.synthetic.json", "real/products.dsld.json"}) {
      String text = Files.readString(TestData.dataDir().resolve(file));
      assertThat(schema.validate(MAPPER.readTree(text))).as(file).isEmpty();
    }
  }
}
