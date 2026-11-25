package com.vitadelta.api.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.vitadelta.api.support.TestData;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Validates JSON documents against the component schemas of {@code
 * contracts/scoring-api.openapi.yaml} (JSON Schema 2020-12), for example the requests the API sent
 * to the scoring stub.
 */
final class ScoringContractSchemas {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Map<String, Schema> SCHEMAS = new ConcurrentHashMap<>();
  private static final String DEFINITIONS = loadDefinitions();

  private ScoringContractSchemas() {}

  private static String loadDefinitions() {
    Path contract = TestData.dataDir().getParent().resolve("contracts/scoring-api.openapi.yaml");
    try {
      JsonNode document = YAMLMapper.builder().build().readTree(Files.readString(contract));
      JsonNode schemas = document.get("components").get("schemas");
      return JSON.writeValueAsString(schemas).replace("#/components/schemas/", "#/$defs/");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Schema schema(String name) {
    return SCHEMAS.computeIfAbsent(
        name,
        key ->
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(
                    "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"$defs\":"
                        + DEFINITIONS
                        + ",\"$ref\":\"#/$defs/"
                        + key
                        + "\"}"));
  }

  /** Asserts that {@code json} conforms to scoring component schema {@code name}. */
  static void assertConforms(String name, String json) {
    assertThat(schema(name).validate(JSON.readTree(json)))
        .as("scoring %s conforms to %s", name, json)
        .isEmpty();
  }
}
