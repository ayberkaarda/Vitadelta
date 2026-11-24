package com.vitadelta.api.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.servers.Server;
import java.lang.annotation.Annotation;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI description served by springdoc at {@link SecurityConfig#OPENAPI_DOCUMENT}. It is
 * generated from the controllers and their records ({@code springdoc.use-fqn}: schemas are named by
 * their fully qualified class name) and compared with {@code contracts/public-api.openapi.yaml} by
 * a test; the contract stays authoritative.
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

  @Bean
  OpenApiCustomizer responseRecordsRequired() {
    return OpenApiConfig::markResponseRecordsRequired;
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
