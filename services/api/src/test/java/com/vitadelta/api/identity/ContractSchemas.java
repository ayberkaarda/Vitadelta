package com.vitadelta.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.vitadelta.api.support.TestData;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Validates real response bodies against the component schemas of {@code
 * contracts/public-api.openapi.yaml} (OpenAPI 3.1 schemas are JSON Schema 2020-12). The component
 * schemas are copied into {@code $defs} and each check validates against {@code
 * {"$ref":"#/$defs/<name>"}}.
 */
public final class ContractSchemas {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Map<String, Schema> SCHEMAS = new ConcurrentHashMap<>();
  private static final String DEFINITIONS = loadDefinitions();

  private ContractSchemas() {}

  private static String loadDefinitions() {
    Path contract = TestData.dataDir().getParent().resolve("contracts/public-api.openapi.yaml");
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

  /** Schema violations of {@code json} against component schema {@code name}. */
  public static List<?> violations(String name, String json) {
    return schema(name).validate(JSON.readTree(json));
  }

  /** Asserts that {@code json} conforms to component schema {@code name}. */
  public static void assertConforms(String name, String json) {
    assertThat(violations(name, json)).as("%s conforms to %s", json, name).isEmpty();
  }
}
