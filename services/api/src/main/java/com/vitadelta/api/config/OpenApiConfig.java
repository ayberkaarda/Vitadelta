package com.vitadelta.api.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.servers.Server;
import java.lang.annotation.Annotation;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI description served by springdoc at {@link SecurityConfig#OPENAPI_DOCUMENT}. It is
 * generated from the controllers and their records ({@code springdoc.use-fqn}: schemas are named by
 * their fully qualified class name while the document is built, then renamed to the contract's
 * schema names by {@link #renameSchemas}) and compared with {@code
 * contracts/public-api.openapi.yaml} by a test; the contract stays authoritative.
 *
 * <p>Response records are always serialized with every component (also {@code null} ones), so for
 * every record reachable from a response body whose components carry no explicit
 * {@code @Schema(requiredMode = ...)}, every component is listed as {@code required}, except
 * components excluded by {@link JsonInclude} (for example absent explanation parameters). Request
 * bodies keep what their records declare.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

  private static final String REF_PREFIX = "#/components/schemas/";

  @Bean
  OpenAPI vitadeltaOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Vitadelta Public API")
                .version("1.0.0")
                .description(
                    "Generated from the implementation; the authoritative contract is"
                        + " contracts/public-api.openapi.yaml."))
        .servers(List.of(new Server().url("/").description("Same origin")));
  }

  /**
   * Contract schema names of the records whose simple class name differs from it (springdoc
   * generates the fully qualified names, see {@link #renameSchemas}). Several classes may map to
   * the same contract schema when they describe the same shape.
   */
  static final Map<String, String> CONTRACT_SCHEMA_NAMES =
      Map.ofEntries(
          Map.entry("com.vitadelta.api.billing.PromoCodeRecord", "PromoCode"),
          Map.entry(
              "com.vitadelta.api.catalog.web.ProductPresenter.Availability", "ProductAvailability"),
          Map.entry("com.vitadelta.api.catalog.web.ProductPresenter.Flag", "ProductFlag"),
          Map.entry("com.vitadelta.api.catalog.web.ProductPresenter.Nutrient", "ProductNutrient"),
          Map.entry(
              "com.vitadelta.api.catalog.web.ProductPresenter.Provenance", "ProductProvenance"),
          Map.entry("com.vitadelta.api.catalog.web.ReferenceDataController.CountryItem", "Country"),
          Map.entry(
              "com.vitadelta.api.catalog.web.ReferenceDataController.FormItem", "NutrientForm"),
          Map.entry(
              "com.vitadelta.api.catalog.web.ReferenceDataController.NutrientItem", "Nutrient"),
          Map.entry("com.vitadelta.api.common.HealthResponse", "Health"),
          Map.entry("com.vitadelta.api.common.watch.WatchedAlert", "AlertSubscription"),
          Map.entry("com.vitadelta.api.entitlement.PlanController.PlanView", "Plan"),
          Map.entry("com.vitadelta.api.entitlement.PriceConversion", "MoneyMeta"),
          Map.entry("com.vitadelta.api.entitlement.PriceConversion.Fx", "FxMeta"),
          Map.entry(
              "com.vitadelta.api.identity.AccountPlanSummary.SubscriptionView", "Subscription"),
          Map.entry(
              "com.vitadelta.api.imports.ImportController.ImportJobDetailView", "ImportJobDetail"),
          Map.entry(
              "com.vitadelta.api.imports.ImportController.ImportJobErrorView", "ImportJobError"),
          Map.entry("com.vitadelta.api.imports.ImportController.ImportJobView", "ImportJob"),
          Map.entry(
              "com.vitadelta.api.recommendation.RecommendationApi.CategoryWeightsInput",
              "CategoryWeights"),
          Map.entry("com.vitadelta.api.recommendation.RecommendationApi.Flag", "ProductFlag"),
          Map.entry(
              "com.vitadelta.api.recommendation.RecommendationApi.Principal", "PrincipalSummary"),
          Map.entry(
              "com.vitadelta.api.recommendation.RecommendationApi.QueryRequest",
              "RecommendationQueryRequest"),
          Map.entry(
              "com.vitadelta.api.recommendation.RecommendationApi.QueryResponse",
              "RecommendationQueryResponse"),
          Map.entry("com.vitadelta.api.recommendation.RecommendationApi.RankingInput", "Ranking"),
          Map.entry(
              "com.vitadelta.api.recommendation.RecommendationApi.RequiredNutrientInput",
              "RequiredNutrient"),
          Map.entry("com.vitadelta.api.recommendation.RecommendationApi.WeightsInput", "Weights"),
          Map.entry("tools.jackson.databind.JsonNode", "UpdateMeRequest"),
          Map.entry("JsonNode", "UpdateMeRequest"));

  /**
   * The schema kept when several classes map to one contract name: the one that describes the
   * contract schema completely (the recommendation flag declares the code enumeration).
   */
  static final Set<String> PREFERRED_SCHEMAS =
      Set.of("com.vitadelta.api.recommendation.RecommendationApi.Flag");

  /**
   * Records that the contract describes as an inline object of a property rather than as a named
   * schema: every property referencing one gets the schema itself, and the named schema is removed.
   */
  static final Set<String> INLINED_SCHEMAS =
      Set.of("com.vitadelta.api.health.HealthViews.AdminHealthComponents");

  @Bean
  OpenApiCustomizer responseRecordsRequired() {
    return openApi -> {
      markResponseRecordsRequired(openApi);
      inlineSchemas(openApi);
      renameSchemas(openApi);
    };
  }

  /** Replaces property references to {@link #INLINED_SCHEMAS} by the schemas themselves. */
  @SuppressWarnings({"rawtypes", "unchecked"})
  static void inlineSchemas(OpenAPI openApi) {
    if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
      return;
    }
    Map<String, Schema> schemas = openApi.getComponents().getSchemas();
    for (Schema schema : schemas.values()) {
      if (schema.getProperties() == null) {
        continue;
      }
      Map<String, Schema> properties = schema.getProperties();
      for (Map.Entry<String, Schema> property : properties.entrySet()) {
        String ref = property.getValue().get$ref();
        if (ref != null && ref.startsWith(REF_PREFIX)) {
          String target = ref.substring(REF_PREFIX.length());
          if (INLINED_SCHEMAS.contains(target) && schemas.containsKey(target)) {
            property.setValue(schemas.get(target));
          }
        }
      }
    }
    INLINED_SCHEMAS.forEach(schemas::remove);
  }

  /**
   * Renames the generated schemas (fully qualified class names, which keep nested records of
   * different classes apart while the document is built) to the contract's schema names: the entry
   * of {@link #CONTRACT_SCHEMA_NAMES}, otherwise the simple class name. Every reference is
   * rewritten; when two classes map to the same name the one of {@link #PREFERRED_SCHEMAS} is kept,
   * otherwise the first one. No class or package name remains in the served document.
   */
  @SuppressWarnings("rawtypes")
  static void renameSchemas(OpenAPI openApi) {
    if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
      return;
    }
    Map<String, String> names = new LinkedHashMap<>();
    Map<String, Schema> renamed = new TreeMap<>();
    for (Map.Entry<String, Schema> entry : openApi.getComponents().getSchemas().entrySet()) {
      String name = contractName(entry.getKey());
      names.put(entry.getKey(), name);
      if (!renamed.containsKey(name) || PREFERRED_SCHEMAS.contains(entry.getKey())) {
        renamed.put(name, entry.getValue());
      }
    }
    Set<Schema> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    renamed.values().forEach(schema -> rewriteReferences(schema, names, visited));
    if (openApi.getPaths() != null) {
      openApi
          .getPaths()
          .values()
          .forEach(item -> item.readOperations().forEach(op -> rewrite(op, names, visited)));
    }
    openApi.getComponents().setSchemas(new LinkedHashMap<>(renamed));
  }

  /** The contract name of a generated schema name. */
  static String contractName(String generated) {
    String mapped = CONTRACT_SCHEMA_NAMES.get(generated);
    if (mapped != null) {
      return mapped;
    }
    int dot = generated.lastIndexOf('.');
    return dot < 0 ? generated : generated.substring(dot + 1);
  }

  @SuppressWarnings("rawtypes")
  private static void rewrite(Operation operation, Map<String, String> names, Set<Schema> seen) {
    if (operation.getParameters() != null) {
      for (Parameter parameter : operation.getParameters()) {
        rewriteReferences(parameter.getSchema(), names, seen);
        rewriteContent(parameter.getContent(), names, seen);
      }
    }
    if (operation.getRequestBody() != null) {
      rewriteContent(operation.getRequestBody().getContent(), names, seen);
    }
    if (operation.getResponses() != null) {
      for (ApiResponse response : operation.getResponses().values()) {
        rewriteContent(response.getContent(), names, seen);
      }
    }
  }

  @SuppressWarnings("rawtypes")
  private static void rewriteContent(Content content, Map<String, String> names, Set<Schema> seen) {
    if (content != null) {
      for (MediaType media : content.values()) {
        rewriteReferences(media.getSchema(), names, seen);
      }
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void rewriteReferences(
      Schema schema, Map<String, String> names, Set<Schema> seen) {
    if (schema == null || !seen.add(schema)) {
      return;
    }
    String ref = schema.get$ref();
    if (ref != null && ref.startsWith(REF_PREFIX)) {
      String target = names.get(ref.substring(REF_PREFIX.length()));
      if (target != null) {
        schema.set$ref(REF_PREFIX + target);
      }
    }
    if (schema.getProperties() != null) {
      for (Object property : schema.getProperties().values()) {
        rewriteReferences((Schema) property, names, seen);
      }
    }
    rewriteReferences(schema.getItems(), names, seen);
    rewriteReferences(schema.getNot(), names, seen);
    if (schema.getAdditionalProperties() instanceof Schema values) {
      rewriteReferences(values, names, seen);
    }
    for (List<Schema> parts :
        List.of(
            listOf(schema.getAllOf()),
            listOf(schema.getOneOf()),
            listOf(schema.getAnyOf()),
            listOf(schema.getPrefixItems()))) {
      parts.forEach(part -> rewriteReferences(part, names, seen));
    }
  }

  @SuppressWarnings("rawtypes")
  static void markResponseRecordsRequired(OpenAPI openApi) {
    if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
      return;
    }
    Map<String, Schema> schemas = openApi.getComponents().getSchemas();
    Set<String> reachable = new LinkedHashSet<>();
    if (openApi.getPaths() != null) {
      openApi
          .getPaths()
          .values()
          .forEach(item -> item.readOperations().forEach(op -> visit(op, schemas, reachable)));
    }
    for (String name : reachable) {
      Schema schema = schemas.get(name);
      recordClass(name)
          .filter(record -> !declaresRequiredMode(record))
          .ifPresent(record -> markRequired(record, schema));
    }
  }

  @SuppressWarnings("rawtypes")
  private static void visit(Operation operation, Map<String, Schema> schemas, Set<String> seen) {
    if (operation.getResponses() == null) {
      return;
    }
    for (ApiResponse response : operation.getResponses().values()) {
      if (response.getContent() != null) {
        for (MediaType media : response.getContent().values()) {
          visit(media.getSchema(), schemas, seen);
        }
      }
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void visit(Schema schema, Map<String, Schema> schemas, Set<String> seen) {
    if (schema == null) {
      return;
    }
    if (schema.get$ref() != null && schema.get$ref().startsWith(REF_PREFIX)) {
      String name = schema.get$ref().substring(REF_PREFIX.length());
      if (seen.add(name)) {
        visit(schemas.get(name), schemas, seen);
      }
    }
    if (schema.getProperties() != null) {
      for (Object property : schema.getProperties().values()) {
        visit((Schema) property, schemas, seen);
      }
    }
    visit(schema.getItems(), schemas, seen);
    if (schema.getAdditionalProperties() instanceof Schema values) {
      visit(values, schemas, seen);
    }
    for (List<Schema> parts :
        List.of(listOf(schema.getAllOf()), listOf(schema.getOneOf()), listOf(schema.getAnyOf()))) {
      parts.forEach(part -> visit(part, schemas, seen));
    }
  }

  @SuppressWarnings("rawtypes")
  private static List<Schema> listOf(List<Schema> parts) {
    return parts == null ? List.of() : parts;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void markRequired(Class<?> record, Schema schema) {
    Schema target = schema;
    if (schema.getAllOf() != null) {
      for (Object part : schema.getAllOf()) {
        if (part instanceof Schema inline && inline.getProperties() != null) {
          target = inline;
        }
      }
    }
    if (target.getProperties() == null) {
      return;
    }
    boolean recordOmitsNulls = omitsNulls(record.getAnnotation(JsonInclude.class));
    List<String> required = new ArrayList<>();
    for (RecordComponent component : record.getRecordComponents()) {
      boolean omitted =
          recordOmitsNulls
              || omitsNulls(component.getAccessor().getAnnotation(JsonInclude.class))
              || omitsNulls(field(record, component.getName(), JsonInclude.class));
      if (!omitted && target.getProperties().containsKey(component.getName())) {
        required.add(component.getName());
      }
    }
    if (!required.isEmpty()) {
      target.setRequired(required);
    }
  }

  /** The record class of a fully qualified schema name ({@code Outer.Inner} for nested types). */
  static Optional<Class<?>> recordClass(String schemaName) {
    String binary = schemaName;
    ClassLoader loader = OpenApiConfig.class.getClassLoader();
    while (true) {
      try {
        Class<?> type = Class.forName(binary, false, loader);
        return type.isRecord() ? Optional.of(type) : Optional.empty();
      } catch (ClassNotFoundException e) {
        int dot = binary.lastIndexOf('.');
        if (dot < 0) {
          return Optional.empty();
        }
        binary = binary.substring(0, dot) + '$' + binary.substring(dot + 1);
      }
    }
  }

  private static boolean declaresRequiredMode(Class<?> record) {
    for (RecordComponent component : record.getRecordComponents()) {
      io.swagger.v3.oas.annotations.media.Schema schema =
          field(record, component.getName(), io.swagger.v3.oas.annotations.media.Schema.class);
      if (schema != null
          && schema.requiredMode()
              != io.swagger.v3.oas.annotations.media.Schema.RequiredMode.AUTO) {
        return true;
      }
    }
    return false;
  }

  /** An annotation of a record component, which Java keeps on the component's field. */
  private static <A extends Annotation> A field(Class<?> record, String name, Class<A> annotation) {
    try {
      return record.getDeclaredField(name).getAnnotation(annotation);
    } catch (NoSuchFieldException e) {
      return null;
    }
  }

  private static boolean omitsNulls(JsonInclude include) {
    return include != null
        && include.value() != JsonInclude.Include.ALWAYS
        && include.value() != JsonInclude.Include.USE_DEFAULTS;
  }
}
