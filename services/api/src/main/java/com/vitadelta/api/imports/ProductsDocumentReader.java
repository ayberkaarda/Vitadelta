package com.vitadelta.api.imports;

import com.vitadelta.api.catalog.AvailabilityStatus;
import com.vitadelta.api.catalog.AvailabilityValues;
import com.vitadelta.api.catalog.DosageForm;
import com.vitadelta.api.catalog.FormCodes;
import com.vitadelta.api.catalog.OfferValues;
import com.vitadelta.api.catalog.ProductDetails;
import com.vitadelta.api.catalog.ProductImport;
import com.vitadelta.api.catalog.ProductNutrientValues;
import com.vitadelta.api.config.properties.ImportLimitsProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and validates a products document ({@code contracts/schemas/import-products.schema.json})
 * against the schema and the importer's semantic rules. The whole document is validated; the result
 * carries either all products or all problems.
 */
public final class ProductsDocumentReader {

  /** Deepest nesting of arrays and objects a document may have (the schema needs 5 levels). */
  static final int MAX_NESTING_DEPTH = 64;

  private static final int DEFAULT_MAPPER_FIELD_LENGTH =
      ImportLimitsProperties.DEFAULTS.maxFieldLength();

  private static final JsonMapper DEFAULT_MAPPER = buildMapper(DEFAULT_MAPPER_FIELD_LENGTH);

  private static final Set<String> DOCUMENT_FIELDS = Set.of("schema_version", "products");
  private static final Set<String> PRODUCT_FIELDS =
      Set.of(
          "code",
          "brand",
          "name",
          "dosage_form",
          "units_per_container",
          "units_per_serving",
          "servings_per_day",
          "gtin",
          "source",
          "source_ref",
          "source_entry_date",
          "synthetic",
          "nutrients",
          "availability",
          "offers");
  private static final List<String> PRODUCT_REQUIRED =
      List.of(
          "code",
          "brand",
          "name",
          "dosage_form",
          "units_per_container",
          "units_per_serving",
          "servings_per_day",
          "nutrients",
          "availability");
  private static final Set<String> NUTRIENT_FIELDS =
      Set.of(
          "nutrient_code",
          "amount_per_serving",
          "unit",
          "ul_amount_per_serving",
          "form_code",
          "form_label");
  private static final Set<String> AVAILABILITY_FIELDS = Set.of("country_code", "status", "note");
  private static final Set<String> OFFER_FIELDS =
      Set.of("country_code", "retailer", "price", "currency", "in_stock", "url", "checked_at");

  /**
   * Properties whose schema type includes {@code null} (gtin, source, ul_amount_per_serving,
   * form_code, form_label, note); every other property must not be an explicit null.
   */
  private static final Set<String> NULLABLE =
      Set.of("gtin", "source", "ul_amount_per_serving", "form_code", "form_label", "note");

  private static final Pattern GTIN = Pattern.compile("^(?:[0-9]{8}|[0-9]{12,14})$");
  private static final BigDecimal MAX_SERVINGS_PER_DAY = new BigDecimal("99.99");

  /**
   * Validated products, or the problems that prevent the document from being applied.
   *
   * @param entries entries of the document's {@code products} array; 0 when the document could not
   *     be read
   */
  public record Result(List<ProductImport> products, List<ImportProblem> problems, int entries) {

    public Result {
      products = List.copyOf(products);
      problems = List.copyOf(problems);
    }

    public boolean valid() {
      return problems.isEmpty();
    }
  }

  private ProductsDocumentReader() {}

  /** Reads raw document bytes with the default limits; invalid UTF-8 or JSON fails it whole. */
  public static Result read(byte[] content, CatalogRegistry registry, Instant now) {
    return read(content, registry, now, ImportLimitsProperties.DEFAULTS);
  }

  /** Reads raw document bytes within {@code limits}. */
  public static Result read(
      byte[] content, CatalogRegistry registry, Instant now, ImportLimitsProperties limits) {
    Optional<String> text = Utf8.decode(content);
    if (text.isEmpty()) {
      return failure("$", "invalid_encoding", "document is not valid UTF-8");
    }
    return read(text.get(), registry, now, limits);
  }

  /** Reads already decoded text with the default limits. */
  public static Result read(String text, CatalogRegistry registry, Instant now) {
    return read(text, registry, now, ImportLimitsProperties.DEFAULTS);
  }

  /**
   * Reads already decoded text within {@code limits}: a string longer than {@code maxFieldLength}
   * or a nesting deeper than {@value #MAX_NESTING_DEPTH} fails the document as {@code
   * invalid_json}; more than {@code maxProducts} products fail it with {@code too_many_records}
   * before any product is validated; validation stops after {@code maxErrors} errors (see {@link
   * ImportProblems}). Parser failures are reported with a fixed message and their line and column
   * only, never with the parser's message or a fragment of the input.
   */
  public static Result read(
      String text, CatalogRegistry registry, Instant now, ImportLimitsProperties limits) {
    JsonNode root;
    try {
      root = mapper(limits).readTree(text);
    } catch (JacksonException e) {
      return failure("$", "invalid_json", invalidJson(e));
    }
    Context ctx = new Context(registry, now, limits.maxErrors());
    if (root == null || !root.isObject()) {
      return failure("$", "schema_violation", "document must be a JSON object");
    }
    JsonNode entries = root.get("products");
    int count = entries != null && entries.isArray() ? entries.size() : 0;
    if (count > limits.maxProducts()) {
      return new Result(
          List.of(),
          List.of(
              new ImportProblem(
                  "$",
                  "too_many_records",
                  "the document has more than " + limits.maxProducts() + " products")),
          count);
    }
    ctx.checkFields(root, "$", DOCUMENT_FIELDS, List.of("schema_version", "products"));
    JsonNode version = root.get("schema_version");
    if (version != null
        && !version.isNull()
        && !(version.isNumber() && version.decimalValue().compareTo(BigDecimal.ONE) == 0)) {
      ctx.problem("schema_version", "schema_violation", "schema_version must be 1");
    }
    JsonNode products = root.get("products");
    List<ProductImport> result = new ArrayList<>();
    if (products != null) {
      if (!products.isArray() || products.isEmpty()) {
        ctx.problem("products", "schema_violation", "products must be a non-empty array");
      } else {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < products.size() && !ctx.problems.overflowed(); i++) {
          String path = "products[" + i + "]";
          ctx.product(products.get(i), path)
              .ifPresent(p -> {
                if (codes.add(p.details().code())) {
                  result.add(p);
                } else {
                  ctx.problem(
                      path + ".code",
                      "duplicate_product_code",
                      "product code " + p.details().code() + " appears more than once");
                }
              });
        }
      }
    }
    return ctx.problems.isEmpty()
        ? new Result(result, List.of(), count)
        : new Result(List.of(), ctx.problems.toList(), count);
  }

  private static Result failure(String location, String code, String message) {
    return new Result(List.of(), List.of(new ImportProblem(location, code, message)), 0);
  }

  /** The fixed {@code invalid_json} message with the parser's line and column. */
  static String invalidJson(JacksonException e) {
    String reason =
        e instanceof StreamConstraintsException
            ? "the document exceeds a parser limit (nesting depth or string length)"
            : "the document is not valid JSON";
    TokenStreamLocation location = e.getLocation();
    if (location == null || location.getLineNr() < 1) {
      return reason;
    }
    return reason + " (line " + location.getLineNr() + ", column " + location.getColumnNr() + ")";
  }

  private static JsonMapper mapper(ImportLimitsProperties limits) {
    return limits.maxFieldLength() == DEFAULT_MAPPER_FIELD_LENGTH
        ? DEFAULT_MAPPER
        : buildMapper(limits.maxFieldLength());
  }

  private static JsonMapper buildMapper(int maxStringLength) {
    JsonFactory factory =
        JsonFactory.builder()
            .streamReadConstraints(
                StreamReadConstraints.builder()
                    .maxStringLength(maxStringLength)
                    .maxNestingDepth(MAX_NESTING_DEPTH)
                    .build())
            .build();
    return JsonMapper.builder(factory)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .build();
  }

  /** Validation state of one document. */
  private static final class Context {

    private final CatalogRegistry registry;
    private final Instant now;
    private final ImportProblems problems;

    Context(CatalogRegistry registry, Instant now, int maxErrors) {
      this.registry = registry;
      this.now = now;
      this.problems = new ImportProblems(maxErrors);
    }

    void problem(String location, String code, String message) {
      problems.add(new ImportProblem(location, code, message));
    }

    /**
     * Reports unknown, missing and explicitly null fields (null is allowed only where the schema
     * allows it, see {@link #NULLABLE}); returns whether the node is an object.
     */
    boolean checkFields(JsonNode node, String path, Set<String> allowed, List<String> required) {
      if (!node.isObject()) {
        problem(path, "schema_violation", "must be an object");
        return false;
      }
      for (Map.Entry<String, JsonNode> field : node.properties()) {
        String name = field.getKey();
        if (!allowed.contains(name)) {
          problem(path + "." + name, "schema_violation", "unknown property");
        } else if (field.getValue().isNull()
            && !NULLABLE.contains(name)
            && !required.contains(name)) {
          problem(path + "." + name, "schema_violation", "must not be null");
        }
      }
      for (String name : required) {
        if (!node.has(name) || node.get(name).isNull()) {
          problem(path + "." + name, "schema_violation", "required property is missing");
        }
      }
      return true;
    }

    Optional<ProductImport> product(JsonNode node, String path) {
      int before = problems.count();
      if (!checkFields(node, path, PRODUCT_FIELDS, PRODUCT_REQUIRED)) {
        return Optional.empty();
      }
      String code = string(node, path, "code", 1, 40, false);
      if (code != null && !OfferRules.PRODUCT_CODE.matcher(code).matches()) {
        problem(path + ".code", "schema_violation", "code does not match the product code pattern");
      }
      String brand = string(node, path, "brand", 1, 200, false);
      String name = string(node, path, "name", 1, 300, false);
      DosageForm dosageForm = enumValue(node, path, "dosage_form", DosageForm.class).orElse(null);
      Integer unitsPerContainer = positiveInt(node, path, "units_per_container");
      Integer unitsPerServing = positiveInt(node, path, "units_per_serving");
      if (unitsPerContainer != null
          && unitsPerServing != null
          && unitsPerContainer < unitsPerServing) {
        problem(
            path + ".units_per_container",
            "units_per_container_too_small",
            "units_per_container must be at least units_per_serving");
      }
      BigDecimal servingsPerDay = decimal(node, path, "servings_per_day");
      if (servingsPerDay != null
          && (servingsPerDay.signum() <= 0
              || servingsPerDay.compareTo(MAX_SERVINGS_PER_DAY) > 0
              || servingsPerDay.stripTrailingZeros().scale() > 2)) {
        problem(
            path + ".servings_per_day",
            "schema_violation",
            "servings_per_day must be > 0, at most 99.99, with at most 2 decimals");
      }
      String gtin = string(node, path, "gtin", 0, 14, true);
      if (gtin != null && !GTIN.matcher(gtin).matches()) {
        problem(path + ".gtin", "schema_violation", "gtin must have 8, 12, 13 or 14 digits");
      }
      String source = string(node, path, "source", 0, 500, true);
      String sourceRef = string(node, path, "source_ref", 1, 100, true);
      LocalDate sourceEntryDate = date(node, path, "source_entry_date");
      boolean synthetic = bool(node, path, "synthetic", false);
      if (!synthetic && (source == null || source.isBlank() || sourceRef == null)) {
        problem(
            path,
            "missing_provenance",
            "a product that is not synthetic needs source and source_ref");
      }
      List<ProductNutrientValues> nutrients = nutrients(node.get("nutrients"), path);
      List<AvailabilityValues> availability = availability(node.get("availability"), path);
      List<OfferValues> offers = offers(node.get("offers"), path, code);
      if (problems.count() > before) {
        return Optional.empty();
      }
      return Optional.of(
          new ProductImport(
              new ProductDetails(
                  code,
                  brand,
                  name,
                  dosageForm,
                  unitsPerContainer,
                  unitsPerServing,
                  servingsPerDay,
                  gtin,
                  source,
                  sourceRef,
                  sourceEntryDate,
                  synthetic),
              nutrients,
              availability,
              offers));
    }

    private List<ProductNutrientValues> nutrients(JsonNode array, String productPath) {
      List<ProductNutrientValues> result = new ArrayList<>();
      if (array == null || array.isNull()) {
        return result;
      }
      if (!array.isArray() || array.isEmpty()) {
        problem(productPath + ".nutrients", "schema_violation", "must be a non-empty array");
        return result;
      }
      Set<String> seen = new HashSet<>();
      for (int i = 0; i < array.size(); i++) {
        String path = productPath + ".nutrients[" + i + "]";
        JsonNode node = array.get(i);
        int before = problems.count();
        if (!checkFields(
            node, path, NUTRIENT_FIELDS, List.of("nutrient_code", "amount_per_serving", "unit"))) {
          continue;
        }
        String nutrientCode = string(node, path, "nutrient_code", 1, 40, false);
        if (nutrientCode != null && !FormCodes.REGISTRY_CODE.matcher(nutrientCode).matches()) {
          problem(path + ".nutrient_code", "schema_violation", "invalid nutrient code");
        }
        BigDecimal amount = decimal(node, path, "amount_per_serving");
        String unit = string(node, path, "unit", 1, 8, false);
        BigDecimal ulAmount = decimal(node, path, "ul_amount_per_serving");
        String formCode = string(node, path, "form_code", 1, 40, true);
        if (formCode != null && !FormCodes.REGISTRY_CODE.matcher(formCode).matches()) {
          problem(path + ".form_code", "schema_violation", "invalid form code");
        }
        String formLabel = string(node, path, "form_label", 0, 200, true);
        if (problems.count() > before) {
          continue;
        }
        if (!seen.add(nutrientCode)) {
          problem(
              path + ".nutrient_code",
              "duplicate_nutrient",
              "nutrient " + nutrientCode + " appears more than once");
          continue;
        }
        ProductNutrientNormalizer.Outcome outcome =
            ProductNutrientNormalizer.normalize(
                new ProductNutrientNormalizer.RawNutrient(
                    nutrientCode, amount, unit, ulAmount, formCode, formLabel),
                registry,
                path);
        problems.addAll(outcome.problems());
        outcome.values().ifPresent(result::add);
      }
      return result;
    }

    private List<AvailabilityValues> availability(JsonNode array, String productPath) {
      List<AvailabilityValues> result = new ArrayList<>();
      if (array == null || array.isNull()) {
        return result;
      }
      if (!array.isArray() || array.isEmpty()) {
        problem(productPath + ".availability", "schema_violation", "must be a non-empty array");
        return result;
      }
      Set<String> seen = new HashSet<>();
      for (int i = 0; i < array.size(); i++) {
        String path = productPath + ".availability[" + i + "]";
        JsonNode node = array.get(i);
        int before = problems.count();
        if (!checkFields(node, path, AVAILABILITY_FIELDS, List.of("country_code", "status"))) {
          continue;
        }
        String country = country(node, path);
        Optional<AvailabilityStatus> status =
            enumValue(node, path, "status", AvailabilityStatus.class);
        String note = string(node, path, "note", 0, 500, true);
        if (problems.count() > before) {
          continue;
        }
        if (!seen.add(country)) {
          problem(
              path + ".country_code",
              "duplicate_availability",
              "country " + country + " appears more than once");
          continue;
        }
        result.add(new AvailabilityValues(country, status.orElseThrow(), note));
      }
      return result;
    }

    private List<OfferValues> offers(JsonNode array, String productPath, String productCode) {
      List<OfferValues> result = new ArrayList<>();
      if (array == null || array.isNull()) {
        return result;
      }
      if (!array.isArray()) {
        problem(productPath + ".offers", "schema_violation", "must be an array");
        return result;
      }
      Set<String> seen = new HashSet<>();
      for (int i = 0; i < array.size(); i++) {
        String path = productPath + ".offers[" + i + "]";
        JsonNode node = array.get(i);
        int before = problems.count();
        if (!checkFields(node, path, OFFER_FIELDS, List.copyOf(OFFER_FIELDS))) {
          continue;
        }
        String country = country(node, path);
        String retailer = string(node, path, "retailer", 1, OfferRules.MAX_RETAILER_LENGTH, false);
        if (retailer != null && !OfferRules.isValidRetailer(retailer)) {
          problem(path + ".retailer", "schema_violation", "retailer has surrounding whitespace");
        }
        BigDecimal price = decimal(node, path, "price");
        if (price != null && !OfferRules.isValidPrice(price)) {
          problem(path + ".price", "invalid_price", "price must be > 0 with at most 2 decimals");
        }
        String currency = string(node, path, "currency", 3, 3, false);
        if (currency != null && country != null) {
          Optional<String> expected = registry.currencyOf(country);
          if (expected.isPresent() && !expected.get().equals(currency)) {
            problem(
                path + ".currency",
                "currency_mismatch",
                "currency " + currency + " differs from " + expected.get());
          }
        }
        boolean inStock = bool(node, path, "in_stock", false);
        String url = string(node, path, "url", 1, OfferRules.MAX_URL_LENGTH, false);
        if (url != null && !OfferRules.isValidUrl(url)) {
          problem(path + ".url", "invalid_offer_url", "url must be an absolute http or https URI");
        }
        String checkedAtText = string(node, path, "checked_at", 1, 64, false);
        Optional<Instant> checkedAt = Optional.empty();
        if (checkedAtText != null) {
          checkedAt = OfferRules.parseCheckedAt(checkedAtText);
          if (checkedAt.isEmpty()) {
            problem(path + ".checked_at", "invalid_checked_at", "not an RFC 3339 date-time");
          } else if (checkedAt.get().isAfter(now)) {
            problem(path + ".checked_at", "checked_at_in_future", "checked_at is in the future");
          }
        }
        if (problems.count() > before) {
          continue;
        }
        if (!seen.add(country + "\u0000" + retailer)) {
          problem(
              path,
              "duplicate_offer_key",
              "country " + country + " and retailer " + retailer + " appear more than once");
          continue;
        }
        result.add(
            new OfferValues(
                productCode,
                country,
                retailer,
                price,
                currency,
                inStock,
                url,
                checkedAt.orElseThrow()));
      }
      return result;
    }

    private String country(JsonNode node, String path) {
      String country = string(node, path, "country_code", 2, 2, false);
      if (country != null
          && (!OfferRules.COUNTRY_CODE.matcher(country).matches()
              || registry.currencyOf(country).isEmpty())) {
        problem(path + ".country_code", "unknown_country", "unknown country " + country);
      }
      return country;
    }

    private String string(
        JsonNode node, String path, String field, int minLength, int maxLength, boolean nullable) {
      JsonNode value = node.get(field);
      if (value == null || value.isNull()) {
        return null;
      }
      if (!value.isString()) {
        problem(path + "." + field, "schema_violation", "must be a string");
        return null;
      }
      String text = value.stringValue();
      if (text.length() < minLength || text.length() > maxLength) {
        problem(
            path + "." + field,
            "schema_violation",
            "length must be between " + minLength + " and " + maxLength);
        return null;
      }
      return nullable && text.isEmpty() ? null : text;
    }

    private BigDecimal decimal(JsonNode node, String path, String field) {
      JsonNode value = node.get(field);
      if (value == null || value.isNull()) {
        return null;
      }
      if (!value.isNumber()) {
        problem(path + "." + field, "schema_violation", "must be a number");
        return null;
      }
      return value.decimalValue();
    }

    private Integer positiveInt(JsonNode node, String path, String field) {
      JsonNode value = node.get(field);
      if (value == null || value.isNull()) {
        return null;
      }
      if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1) {
        problem(path + "." + field, "schema_violation", "must be an integer >= 1");
        return null;
      }
      return value.intValue();
    }

    private boolean bool(JsonNode node, String path, String field, boolean defaultValue) {
      JsonNode value = node.get(field);
      if (value == null) {
        return defaultValue;
      }
      if (!value.isBoolean()) {
        problem(path + "." + field, "schema_violation", "must be a boolean");
        return defaultValue;
      }
      return value.booleanValue();
    }

    private LocalDate date(JsonNode node, String path, String field) {
      String text = string(node, path, field, 10, 10, true);
      if (text == null) {
        return null;
      }
      try {
        return LocalDate.parse(text);
      } catch (DateTimeParseException e) {
        problem(path + "." + field, "schema_violation", "must be a date (YYYY-MM-DD)");
        return null;
      }
    }

    private <E extends Enum<E>> Optional<E> enumValue(
        JsonNode node, String path, String field, Class<E> type) {
      String text = string(node, path, field, 1, 40, false);
      if (text == null) {
        return Optional.empty();
      }
      for (E constant : type.getEnumConstants()) {
        if (constant.name().equals(text)) {
          return Optional.of(constant);
        }
      }
      problem(path + "." + field, "schema_violation", "invalid value " + text);
      return Optional.empty();
    }
  }
}
