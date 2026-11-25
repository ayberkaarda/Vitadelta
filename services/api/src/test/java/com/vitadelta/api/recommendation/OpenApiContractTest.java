package com.vitadelta.api.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.support.TestData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The OpenAPI description generated from the code (springdoc, {@code GET /api/v1/openapi.json})
 * against the authoritative {@code contracts/public-api.openapi.yaml}.
 *
 * <ul>
 *   <li>Operations: the implemented operations (method and path) are exactly the contract's minus
 *       {@link #NOT_YET_IMPLEMENTED}; the list shrinks phase by phase.
 *   <li>For every implemented operation: query and path parameters (name, location, required), the
 *       success status, and the request and success response bodies compared structurally after
 *       resolving references and {@code allOf}: property names, {@code required} lists,
 *       enumerations ({@code const} counts as a one-value enumeration), array items, map values and
 *       {@code oneOf} alternatives, recursively.
 *   <li>Every difference must be listed in {@link #KNOWN_GAPS} with the reason, and every listed
 *       gap must still exist, so a fixed gap fails the test until it is removed from the list.
 * </ul>
 *
 * Not compared: numeric bounds, patterns, formats, nullability, descriptions, examples, headers
 * (for example {@code X-Request-Id}, set by a filter) and error responses (rendered by the global
 * exception handler, validated against the contract by the API tests).
 */
class OpenApiContractTest extends RecommendationApiTestBase {

  /** Contract operations that later phases implement. */
  static final Set<String> NOT_YET_IMPLEMENTED =
      Set.of(
          "GET /admin/health",
          "GET /fx/currencies",
          "GET /fx/rates",
          "POST /admin/fx/refresh",
          "GET /admin/outbox/stats",
          "GET /me/saved-products",
          "POST /me/saved-products",
          "DELETE /me/saved-products/{productId}",
          "GET /me/alerts",
          "POST /me/alerts",
          "PATCH /me/alerts/{id}",
          "DELETE /me/alerts/{id}",
          "GET /me/notifications",
          "POST /me/notifications/{id}/read",
          "POST /me/notifications/read-all",
          "GET /me/notifications/unread-count");

  /** Routes that exist only in the test sources (entitlement probes of the Phase 3 tests). */
  private static final String TEST_ONLY_SEGMENT = "/entitlement-probe/";

  /** Schemas of the test-only probe routes (they share this application context). */
  private static final Set<String> TEST_ONLY_SCHEMAS = Set.of();

  /**
   * Accepted differences between the generated description and the contract, by operation, with the
   * reason. Each needs a change in code that the recommendation module does not own (requested in
   * the Phase 4 shared diffs) or belongs to a later phase.
   */
  static final Map<String, Set<String>> KNOWN_GAPS = new TreeMap<>();

  static {
    // recommendation: fx.status: PriceConversion.Fx.status (entitlement) is a String without
    // allowableValues; alerts: AlertSubscription items arrive with Phase 5.
    gap(
        "POST /recommendations/compare",
        "response.meta.fx.status: enum contract=[OK, UNAVAILABLE] code=[]");
    gap(
        "POST /recommendations/query",
        "response.items[].alerts[]: kind contract=object code=scalar",
        "response.meta.fx.status: enum contract=[OK, UNAVAILABLE] code=[]");
    // catalog: catalog controllers validate country themselves (required = false for the 422
    // body); unit Strings without allowableValues; weights defaults
    // as Map; forms always serialized; alerts arrive with Phase 5; Fx.status as above.
    gap("GET /countries", "response.items[].defaultLocale: enum contract=[en, tr] code=[]");
    gap(
        "GET /nutrients",
        "response.items[].canonicalUnit: enum contract=[mcg, mg] code=[]",
        "response.items[]: required contract=[canonicalUnit, category, code, nameEn, presenceFloorPerDay, referenceSetCodes, sortOrder] code=[canonicalUnit, category, code, forms, nameEn, presenceFloorPerDay, referenceSetCodes, sortOrder]");
    // The served document has one ProductFlag schema (the recommendation flag with the code
    // enumeration), so the catalog flags no longer differ.
    gap(
        "GET /products",
        "parameter query:country required contract=true code=false",
        "response.meta.fx.status: enum contract=[OK, UNAVAILABLE] code=[]");
    gap(
        "GET /products/{id}",
        "parameter query:country required contract=true code=false",
        "response.meta.fx.status: enum contract=[OK, UNAVAILABLE] code=[]",
        "response.product.alerts[]: kind contract=object code=scalar",
        "response.product.nutrients[].unit: enum contract=[mcg, mg] code=[]");
    gap(
        "GET /weights/defaults",
        "response.categories: map values contract=false code=true",
        "response.categories: properties missing in code [COENZYME, MINERAL, OTHER, VITAMIN]",
        "response.categories: required contract=[COENZYME, MINERAL, OTHER, VITAMIN] code=[]");
    // imports: imports controllers answer 202 through ResponseEntity without @ResponseStatus; the
    // multipart file part and ImportJobError.code are not annotated.
    gap(
        "GET /admin/imports/{jobId}",
        "response.errors[]: required contract=[message, rowNumber] code=[code, message, rowNumber]");
    gap(
        "POST /admin/imports/offers",
        "request: required contract=[file] code=[]",
        "success status contract=202 code=200");
    gap("POST /admin/imports/products", "success status contract=202 code=200");
    // entitlement/billing: status codes through ResponseEntity without @ResponseStatus; plan,
    // status and source Strings without allowableValues; expiresAt nullable but sent; Fx.status as
    // above.
    gap("DELETE /admin/promo-codes/{code}", "success status contract=204 code=200");
    gap(
        "GET /plans",
        "response.items[].code: enum contract=[FREE, PRO] code=[]",
        "response.items[].price.billingPeriod: enum contract=[MONTHLY] code=[]",
        "response.meta.fx.status: enum contract=[OK, UNAVAILABLE] code=[]");
    gap(
        "POST /admin/promo-codes",
        "request.planCode: enum contract=[PRO] code=[]",
        "request: required contract=[durationDays, expiresAt, maxRedemptions, planCode] code=[durationDays, maxRedemptions, planCode]",
        "success status contract=201 code=200");
    gap(
        "POST /admin/users/{id}/subscription",
        "request.planCode: enum contract=[PRO] code=[]",
        "response.planCode: enum contract=[FREE, PRO] code=[]",
        "response.source: enum contract=[MANUAL, PROMO, PROVIDER] code=[]",
        "response.status: enum contract=[ACTIVE, CANCELED, EXPIRED] code=[]");
    gap(
        "POST /me/subscription/promo",
        "response.plan: enum contract=[FREE, PRO] code=[]",
        "response.subscription.source: enum contract=[MANUAL, PROMO, PROVIDER] code=[]",
        "response.subscription.status: enum contract=[ACTIVE, CANCELED, EXPIRED] code=[]");
    // identity: status codes through ResponseEntity without @ResponseStatus; enum-valued Strings
    // without allowableValues; cookie-scoped bodies; GET /session/quota returns Object (oneOf not
    // declared); PATCH /me binds a JSON tree.
    gap("DELETE /me", "success status contract=204 code=200");
    gap("GET /admin/users", "response.items[].plan: enum contract=[FREE, PRO] code=[]");
    gap(
        "GET /me",
        "response.locale: enum contract=[en, tr] code=[]",
        "response.plan.code: enum contract=[FREE, PRO] code=[]",
        "response.subscription.source: enum contract=[MANUAL, PROMO, PROVIDER] code=[]",
        "response.subscription.status: enum contract=[ACTIVE, CANCELED, EXPIRED] code=[]");
    gap(
        "GET /session/quota",
        "response: alternatives contract=2 code=0",
        "response: kind contract=scalar code=object");
    gap(
        "PATCH /me",
        "request: kind contract=object code=scalar",
        "response.locale: enum contract=[en, tr] code=[]",
        "response.plan.code: enum contract=[FREE, PRO] code=[]",
        "response.subscription.source: enum contract=[MANUAL, PROMO, PROVIDER] code=[]",
        "response.subscription.status: enum contract=[ACTIVE, CANCELED, EXPIRED] code=[]");
    gap("POST /admin/users/{id}/status", "response.plan: enum contract=[FREE, PRO] code=[]");
    gap("POST /auth/forgot-password", "success status contract=202 code=200");
    gap(
        "POST /auth/login",
        "response.tokenType: enum contract=[Bearer] code=[]",
        "response.user.locale: enum contract=[en, tr] code=[]");
    gap(
        "POST /auth/logout",
        "request: schema contract=true code=false",
        "success status contract=204 code=200");
    gap(
        "POST /auth/refresh",
        "request: schema contract=true code=false",
        "response.tokenType: enum contract=[Bearer] code=[]",
        "response.user.locale: enum contract=[en, tr] code=[]");
    gap(
        "POST /auth/register",
        "request.locale: enum contract=[en, tr] code=[]",
        "success status contract=202 code=200");
    gap("POST /auth/resend-verification", "success status contract=202 code=200");
    gap("POST /auth/reset-password", "success status contract=204 code=200");
    gap("POST /auth/verify-email", "success status contract=204 code=200");
  }

  private static void gap(String operation, String... differences) {
    KNOWN_GAPS.put(operation, new TreeSet<>(List.of(differences)));
  }

  private static final YAMLMapper YAML = YAMLMapper.builder().build();

  private JsonNode contract;
  private JsonNode generated;

  @BeforeEach
  void loadDocuments() throws IOException {
    Path file = TestData.dataDir().getParent().resolve("contracts/public-api.openapi.yaml");
    contract = YAML.readTree(Files.readString(file));
    EntityExchangeResult<String> result = get("/api/v1/openapi.json", null);
    assertStatus(result, 200);
    generated = json(result);
  }

  @Test
  void theDescriptionIsServedPubliclyAsOpenApi31() {
    assertThat(generated.get("openapi").asString()).startsWith("3.1");
    assertThat(generated.get("info").get("title").asString()).isEqualTo("Vitadelta Public API");
    assertThat(generated.toString()).doesNotContain("localhost");
  }

  @Test
  void schemasCarryTheContractsNamesAndNoInternalTypeNames() {
    Set<String> contractNames = new TreeSet<>();
    contract.get("components").get("schemas").propertyNames().forEach(contractNames::add);
    Set<String> generatedNames = new TreeSet<>();
    generated.get("components").get("schemas").propertyNames().forEach(generatedNames::add);
    generatedNames.removeIf(name -> TEST_ONLY_SCHEMAS.contains(name));
    assertThat(generatedNames).as("schema names outside the contract").isSubsetOf(contractNames);
    String document = generated.toString();
    for (String marker : List.of("com.vitadelta", "java.", "jakarta.", "springframework")) {
      assertThat(document).as("the description reveals %s", marker).doesNotContain(marker);
    }
  }

  @Test
  void implementedOperationsAreTheContractsMinusTheNotYetImplementedOnes() {
    Set<String> contractOperations = operations(contract, "");
    assertThat(contractOperations).containsAll(NOT_YET_IMPLEMENTED);
    Set<String> expected = new TreeSet<>(contractOperations);
    expected.removeAll(NOT_YET_IMPLEMENTED);
    assertThat(operations(generated, "/api/v1")).containsExactlyInAnyOrderElementsOf(expected);
  }

  @Test
  void implementedOperationsMatchTheContractStructurally() {
    Map<String, Set<String>> differences = new TreeMap<>();
    for (String operation : operations(generated, "/api/v1")) {
      List<String> found = new ArrayList<>();
      compareOperation(operation, found);
      if (!found.isEmpty()) {
        differences.put(operation, new TreeSet<>(found));
      }
    }
    StringBuilder report = new StringBuilder();
    differences.forEach((operation, diffs) ->
        diffs.forEach(d -> report.append(operation).append(" | ").append(d).append('\n')));
    assertThat(differences).as(report.toString()).isEqualTo(KNOWN_GAPS);
  }

  // ---------- Operations ----------

  private static Set<String> operations(JsonNode document, String prefix) {
    Set<String> operations = new TreeSet<>();
    document
        .get("paths")
        .properties()
        .forEach(path -> {
          if (path.getKey().contains(TEST_ONLY_SEGMENT)) {
            return;
          }
          path.getValue()
              .properties()
              .forEach(method -> {
                if (Set.of("get", "post", "put", "patch", "delete").contains(method.getKey())) {
                  operations.add(
                      method.getKey().toUpperCase(Locale.ROOT)
                          + " "
                          + path.getKey().substring(prefix.length()));
                }
              });
        });
    return operations;
  }

  private void compareOperation(String operation, List<String> diffs) {
    String method = operation.substring(0, operation.indexOf(' ')).toLowerCase(Locale.ROOT);
    String path = operation.substring(operation.indexOf(' ') + 1);
    JsonNode expected = contract.get("paths").get(path).get(method);
    JsonNode actual = generated.get("paths").get("/api/v1" + path).get(method);

    Map<String, Boolean> expectedParameters = parameters(contract, expected);
    Map<String, Boolean> actualParameters = parameters(generated, actual);
    if (!expectedParameters.keySet().equals(actualParameters.keySet())) {
      diffs.add(
          "parameters contract="
              + expectedParameters.keySet()
              + " code="
              + actualParameters.keySet());
    } else {
      expectedParameters.forEach((name, required) -> {
        if (!required.equals(actualParameters.get(name))) {
          diffs.add(
              "parameter "
                  + name
                  + " required contract="
                  + required
                  + " code="
                  + actualParameters.get(name));
        }
      });
    }

    JsonNode expectedBody = bodySchema(contract, expected.get("requestBody"));
    JsonNode actualBody = bodySchema(generated, actual.get("requestBody"));
    if (expectedBody != null || actualBody != null) {
      compare("request", expectedBody, actualBody, diffs, new HashSet<>());
    }

    String expectedStatus = successStatus(expected);
    String actualStatus = successStatus(actual);
    if (!expectedStatus.equals(actualStatus)) {
      diffs.add("success status contract=" + expectedStatus + " code=" + actualStatus);
    }
    JsonNode expectedResponse = responseSchema(contract, expected, expectedStatus);
    JsonNode actualResponse = responseSchema(generated, actual, actualStatus);
    if (expectedResponse != null || actualResponse != null) {
      compare("response", expectedResponse, actualResponse, diffs, new HashSet<>());
    }
  }

  /** {@code in:name} of the query and path parameters, mapped to {@code required}. */
  private static Map<String, Boolean> parameters(JsonNode document, JsonNode operation) {
    Map<String, Boolean> parameters = new TreeMap<>();
    JsonNode list = operation.get("parameters");
    if (list == null) {
      return parameters;
    }
    for (JsonNode parameter : list) {
      JsonNode resolved = resolve(document, parameter);
      String in = resolved.get("in").asString();
      if (in.equals("query") || in.equals("path")) {
        JsonNode required = resolved.get("required");
        parameters.put(
            in + ":" + resolved.get("name").asString(), required != null && required.asBoolean());
      }
    }
    return parameters;
  }

  private static String successStatus(JsonNode operation) {
    List<String> codes = new ArrayList<>();
    operation.get("responses").properties().forEach(e -> codes.add(e.getKey()));
    return codes.stream().filter(c -> c.startsWith("2")).sorted().findFirst().orElse("none");
  }

  /** The schema of a request body or response (its JSON media type, else its only one). */
  private static JsonNode bodySchema(JsonNode document, JsonNode body) {
    if (body == null) {
      return null;
    }
    JsonNode content = resolve(document, body).get("content");
    if (content == null || content.isEmpty()) {
      return null;
    }
    JsonNode media = content.get("application/json");
    if (media == null) {
      media = content.properties().iterator().next().getValue();
    }
    return media.get("schema");
  }

  private static JsonNode responseSchema(JsonNode document, JsonNode operation, String status) {
    JsonNode response = operation.get("responses").get(status);
    return response == null ? null : bodySchema(document, response);
  }

  // ---------- Structural comparison ----------

  /** A schema with references and {@code allOf} resolved one level. */
  private record Shape(
      Map<String, JsonNode> properties,
      Set<String> required,
      Set<String> enumeration,
      JsonNode items,
      JsonNode mapValues,
      List<JsonNode> alternatives,
      String kind,
      String identity) {}

  private Shape shape(JsonNode document, JsonNode schema) {
    Map<String, JsonNode> properties = new LinkedHashMap<>();
    Set<String> required = new TreeSet<>();
    Set<String> enumeration = new TreeSet<>();
    List<JsonNode> alternatives = new ArrayList<>();
    JsonNode[] items = new JsonNode[1];
    JsonNode[] mapValues = new JsonNode[1];
    StringBuilder identity = new StringBuilder();
    boolean[] object = new boolean[1];
    collect(
        document,
        schema,
        properties,
        required,
        enumeration,
        alternatives,
        items,
        mapValues,
        identity,
        object);
    String kind =
        items[0] != null
            ? "array"
            : (!properties.isEmpty() || object[0] || mapValues[0] != null) ? "object" : "scalar";
    return new Shape(
        properties,
        required,
        enumeration,
        items[0],
        mapValues[0],
        alternatives,
        kind,
        identity.toString());
  }

  private void collect(
      JsonNode document,
      JsonNode schema,
      Map<String, JsonNode> properties,
      Set<String> required,
      Set<String> enumeration,
      List<JsonNode> alternatives,
      JsonNode[] items,
      JsonNode[] mapValues,
      StringBuilder identity,
      boolean[] object) {
    if (schema == null || !schema.isObject()) {
      return;
    }
    if (schema.has("$ref")) {
      identity.append(schema.get("$ref").asString()).append(' ');
      collect(
          document,
          resolve(document, schema),
          properties,
          required,
          enumeration,
          alternatives,
          items,
          mapValues,
          identity,
          object);
    }
    if (schema.has("allOf")) {
      for (JsonNode part : schema.get("allOf")) {
        collect(
            document,
            part,
            properties,
            required,
            enumeration,
            alternatives,
            items,
            mapValues,
            identity,
            object);
      }
    }
    for (String key : List.of("oneOf", "anyOf")) {
      if (schema.has(key)) {
        // A null alternative only makes the value nullable, which is not compared.
        List<JsonNode> nonNull = new ArrayList<>();
        schema
            .get(key)
            .forEach(a -> {
              if (!(a.has("type") && a.get("type").asString("").equals("null") && a.size() == 1)) {
                nonNull.add(a);
              }
            });
        if (nonNull.size() == 1) {
          collect(
              document,
              nonNull.getFirst(),
              properties,
              required,
              enumeration,
              alternatives,
              items,
              mapValues,
              identity,
              object);
        } else {
          alternatives.addAll(nonNull);
        }
      }
    }
    if (schema.has("properties")) {
      schema
          .get("properties")
          .properties()
          .forEach(e -> properties.putIfAbsent(e.getKey(), e.getValue()));
    }
    if (schema.has("required")) {
      schema.get("required").forEach(r -> required.add(r.asString()));
    }
    if (schema.has("enum")) {
      schema
          .get("enum")
          .forEach(v -> {
            if (!v.isNull()) {
              enumeration.add(v.asString());
            }
          });
    }
    if (schema.has("const")) {
      enumeration.add(schema.get("const").asString());
    }
    if (schema.has("items")) {
      items[0] = schema.get("items");
    }
    if (schema.has("additionalProperties") && schema.get("additionalProperties").isObject()) {
      mapValues[0] = schema.get("additionalProperties");
    }
    JsonNode type = schema.get("type");
    if (type != null && (type.toString().contains("object"))) {
      object[0] = true;
    }
  }

  private void compare(
      String path,
      JsonNode expectedSchema,
      JsonNode actualSchema,
      List<String> diffs,
      Set<String> visiting) {
    if (expectedSchema == null || actualSchema == null) {
      diffs.add(
          path
              + ": schema contract="
              + (expectedSchema != null)
              + " code="
              + (actualSchema != null));
      return;
    }
    Shape expected = shape(contract, expectedSchema);
    Shape actual = shape(generated, actualSchema);
    String key = expected.identity() + "|" + actual.identity();
    if (!expected.identity().isEmpty() && !visiting.add(key)) {
      return;
    }
    if (!expected.alternatives().isEmpty() || !actual.alternatives().isEmpty()) {
      compareAlternatives(path, expected, actual, diffs, visiting);
    }
    if (!expected.kind().equals(actual.kind())) {
      diffs.add(path + ": kind contract=" + expected.kind() + " code=" + actual.kind());
      return;
    }
    if (!expected.enumeration().equals(actual.enumeration())) {
      diffs.add(
          path + ": enum contract=" + expected.enumeration() + " code=" + actual.enumeration());
    }
    switch (expected.kind()) {
      case "array" -> compare(path + "[]", expected.items(), actual.items(), diffs, visiting);
      case "object" -> {
        Set<String> missing = new TreeSet<>(expected.properties().keySet());
        missing.removeAll(actual.properties().keySet());
        Set<String> extra = new TreeSet<>(actual.properties().keySet());
        extra.removeAll(expected.properties().keySet());
        if (!missing.isEmpty()) {
          diffs.add(path + ": properties missing in code " + missing);
        }
        if (!extra.isEmpty()) {
          diffs.add(path + ": properties not in contract " + extra);
        }
        if (!expected.required().equals(actual.required())) {
          diffs.add(
              path + ": required contract=" + expected.required() + " code=" + actual.required());
        }
        for (Map.Entry<String, JsonNode> property : expected.properties().entrySet()) {
          JsonNode other = actual.properties().get(property.getKey());
          if (other != null) {
            compare(path + "." + property.getKey(), property.getValue(), other, diffs, visiting);
          }
        }
        if (expected.mapValues() != null || actual.mapValues() != null) {
          if (expected.mapValues() != null && actual.mapValues() != null) {
            compare(path + "{}", expected.mapValues(), actual.mapValues(), diffs, visiting);
          } else if (expected.properties().isEmpty() || actual.properties().isEmpty()) {
            diffs.add(
                path
                    + ": map values contract="
                    + (expected.mapValues() != null)
                    + " code="
                    + (actual.mapValues() != null));
          }
        }
      }
      default -> {
        // Scalars: enumerations compared above; bounds and formats are not compared.
      }
    }
  }

  private void compareAlternatives(
      String path, Shape expected, Shape actual, List<String> diffs, Set<String> visiting) {
    if (expected.alternatives().size() != actual.alternatives().size()) {
      diffs.add(
          path
              + ": alternatives contract="
              + expected.alternatives().size()
              + " code="
              + actual.alternatives().size());
      return;
    }
    List<JsonNode> unmatched = new ArrayList<>(actual.alternatives());
    for (JsonNode alternative : expected.alternatives()) {
      JsonNode match = null;
      for (JsonNode candidate : unmatched) {
        List<String> trial = new ArrayList<>();
        compare(path, alternative, candidate, trial, new HashSet<>(visiting));
        if (trial.isEmpty()) {
          match = candidate;
          break;
        }
      }
      if (match == null) {
        diffs.add(
            path
                + ": no code alternative matches "
                + shape(contract, alternative).identity().strip());
      } else {
        unmatched.remove(match);
      }
    }
  }

  private static JsonNode resolve(JsonNode document, JsonNode node) {
    JsonNode current = node;
    while (current != null && current.has("$ref")) {
      String ref = current.get("$ref").asString();
      if (!ref.startsWith("#/")) {
        // An external document (the import JSON Schema): compared by reference only.
        return JSON.createObjectNode().put("x-external", ref);
      }
      JsonNode target = document;
      for (String segment : ref.substring(2).split("/")) {
        target = target == null ? null : target.get(segment.replace("~1", "/").replace("~0", "~"));
      }
      if (target == null) {
        throw new AssertionError("unresolvable reference " + ref);
      }
      current = target;
    }
    return current;
  }
}
