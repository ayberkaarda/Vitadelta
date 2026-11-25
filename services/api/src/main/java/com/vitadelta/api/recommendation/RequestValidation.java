package com.vitadelta.api.recommendation;

import com.vitadelta.api.catalog.NutrientCategory;
import com.vitadelta.api.catalog.WeightDefaults;
import com.vitadelta.api.catalog.price.StockFilter;
import com.vitadelta.api.catalog.read.CatalogViews.NutrientView;
import com.vitadelta.api.catalog.unit.ConversionResult;
import com.vitadelta.api.catalog.unit.Unit;
import com.vitadelta.api.catalog.unit.UnitConversionException;
import com.vitadelta.api.common.paging.PageQuery;
import com.vitadelta.api.common.problem.FieldViolation;
import com.vitadelta.api.common.problem.ProblemException;
import com.vitadelta.api.recommendation.RecommendationApi.CompareRequest;
import com.vitadelta.api.recommendation.RecommendationApi.QueryRequest;
import com.vitadelta.api.recommendation.RecommendationApi.RequiredNutrientInput;
import com.vitadelta.api.recommendation.RecommendationApi.WeightsInput;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Validation of the recommendation requests (section 9.1). Syntax is checked first, without the
 * database ({@link #parseQuery}, {@link #parseCompare}); the registry-dependent rules follow with
 * the {@link ReferenceSnapshot} of the current catalog version ({@link #resolveQuery}, {@link
 * #resolveWeights}). Every rule reports a field error with the JSON path of the value; all errors
 * of one phase are reported together as {@code 422 validation_failed}.
 */
final class RequestValidation {

  static final String DETAIL = "The request contains invalid fields.";

  private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");
  private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
  private static final Pattern NUTRIENT_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,39}$");
  private static final Pattern UUID_TEXT =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

  /**
   * Most decimal places a request number may have (after removing trailing zeros). Together with
   * {@link #MAX_INTEGER_DIGITS} it bounds the size of every number the API computes with or writes
   * into the cache key, so an exponent such as {@code 1e-1073741824} is rejected before any
   * arithmetic.
   */
  static final int MAX_FRACTION_DIGITS = 9;

  /** Most digits a request number may have before the decimal point. */
  static final int MAX_INTEGER_DIGITS = 12;

  private static final BigDecimal CATEGORY_MAX = BigDecimal.valueOf(WeightDefaults.CATEGORY_MAX);
  private static final BigDecimal OVERRIDE_MAX = BigDecimal.valueOf(WeightDefaults.OVERRIDE_MAX);
  private static final BigDecimal LAMBDA_MAX = BigDecimal.valueOf(WeightDefaults.LAMBDA_MAX);
  private static final BigDecimal LAMBDA_DEFAULT =
      BigDecimal.valueOf(WeightDefaults.LAMBDA_DEFAULT);

  private RequestValidation() {}

  /** A syntactically valid query; registry rules are not checked yet. */
  record ParsedQuery(
      String country,
      StockFilter stockFilter,
      RankingMode mode,
      BigDecimal lambda,
      ParsedWeights weights,
      List<RequiredNutrientInput> requiredNutrients,
      boolean excludeExceedingUl,
      String displayCurrency,
      PageQuery page) {}

  /** Syntactically valid weights: category weights with defaults, overrides as sent. */
  record ParsedWeights(
      Map<NutrientCategory, BigDecimal> categories, Map<String, BigDecimal> overrides) {}

  /** A syntactically valid comparison. */
  record ParsedCompare(
      String country,
      UUID baseline,
      UUID candidate,
      StockFilter stockFilter,
      ParsedWeights weights,
      String displayCurrency) {}

  static ParsedQuery parseQuery(QueryRequest request) {
    List<FieldViolation> errors = new ArrayList<>();
    String country = country(request.country(), errors);
    StockFilter filter = stockFilter(request.stockFilter(), errors);
    RankingMode mode = RankingMode.VALUE;
    BigDecimal lambda = null;
    if (request.ranking() != null) {
      String rawMode = request.ranking().mode();
      if (rawMode == null) {
        errors.add(required("ranking.mode"));
      } else {
        mode = parseEnum(RankingMode.class, rawMode).orElse(null);
        if (mode == null) {
          errors.add(
              new FieldViolation(
                  "ranking.mode",
                  "invalid_format",
                  "ranking.mode must be one of PRICE, RICHNESS, VALUE, CUSTOM."));
        }
      }
      lambda = request.ranking().lambda();
      if (lambda != null) {
        if (mode != null && mode != RankingMode.CUSTOM) {
          errors.add(
              new FieldViolation(
                  "ranking.lambda",
                  "lambda_requires_custom",
                  "ranking.lambda is accepted only with the CUSTOM mode."));
        } else if (!withinPrecision(lambda)) {
          errors.add(tooPrecise("ranking.lambda"));
        } else if (lambda.signum() < 0 || lambda.compareTo(LAMBDA_MAX) > 0) {
          errors.add(outOfRange("ranking.lambda", "0", "1"));
        }
      }
    }
    if (mode == RankingMode.CUSTOM && lambda == null) {
      lambda = LAMBDA_DEFAULT;
    }
    ParsedWeights weights = weights(request.weights(), errors);
    List<RequiredNutrientInput> required =
        request.requiredNutrients() == null ? List.of() : request.requiredNutrients();
    for (int i = 0; i < required.size(); i++) {
      requiredNutrient(required.get(i), "requiredNutrients[" + i + "]", errors);
    }
    currency(request.displayCurrency(), errors);
    PageQuery page = null;
    try {
      page = PageQuery.of(request.page(), request.pageSize());
    } catch (ProblemException e) {
      errors.addAll(e.errors());
    }
    failIfAny(errors);
    return new ParsedQuery(
        country,
        filter,
        mode,
        mode == RankingMode.CUSTOM ? lambda : null,
        weights,
        List.copyOf(required),
        Boolean.TRUE.equals(request.excludeExceedingUl()),
        request.displayCurrency(),
        page);
  }

  static ParsedCompare parseCompare(CompareRequest request) {
    List<FieldViolation> errors = new ArrayList<>();
    String country = country(request.country(), errors);
    UUID baseline = productId(request.baselineProductId(), "baselineProductId", errors);
    UUID candidate = productId(request.candidateProductId(), "candidateProductId", errors);
    if (baseline != null && baseline.equals(candidate)) {
      errors.add(
          new FieldViolation(
              "candidateProductId",
              "same_product",
              "The candidate must be a different product than the baseline."));
    }
    StockFilter filter = stockFilter(request.stockFilter(), errors);
    ParsedWeights weights = weights(request.weights(), errors);
    currency(request.displayCurrency(), errors);
    failIfAny(errors);
    return new ParsedCompare(
        country, baseline, candidate, filter, weights, request.displayCurrency());
  }

  /**
   * Applies the registry rules to a parsed query: override keys and required nutrients must be
   * registry codes, the weights must not be all zero, a required nutrient appears once and its unit
   * converts into the canonical unit without a form.
   */
  static RankingQuery resolveQuery(ParsedQuery query, ReferenceSnapshot reference) {
    List<FieldViolation> errors = new ArrayList<>();
    RankingQuery.Weights weights = resolveWeights(query.weights(), reference, errors);
    List<RankingQuery.RequiredNutrient> required = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < query.requiredNutrients().size(); i++) {
      RequiredNutrientInput input = query.requiredNutrients().get(i);
      String path = "requiredNutrients[" + i + "]";
      Optional<NutrientView> nutrient = reference.nutrient(input.code());
      if (nutrient.isEmpty()) {
        errors.add(unknownNutrient(path + ".code", input.code()));
        continue;
      }
      if (!seen.add(input.code())) {
        errors.add(
            new FieldViolation(
                path + ".code", "duplicate_nutrient", input.code() + " is listed more than once."));
        continue;
      }
      Unit canonical = nutrient.get().canonicalUnit();
      BigDecimal minimum = null;
      if (input.unit() != null) {
        Unit unit = Unit.fromSymbol(input.unit()).orElseThrow();
        Optional<BigDecimal> converted =
            canonicalAmount(reference, input.code(), input.minAmountPerDay(), unit, canonical);
        if (converted.isEmpty()) {
          errors.add(
              new FieldViolation(
                  path + ".unit",
                  "unit_requires_form",
                  input.unit()
                      + " cannot be converted to "
                      + canonical.symbol()
                      + " for "
                      + input.code()
                      + " without a form."));
          continue;
        }
        minimum = input.minAmountPerDay() == null ? null : converted.get();
      }
      required.add(new RankingQuery.RequiredNutrient(input.code(), minimum, canonical));
    }
    failIfAny(errors);
    return new RankingQuery(
        query.country(),
        query.stockFilter(),
        query.mode(),
        query.lambda(),
        weights,
        required,
        query.excludeExceedingUl());
  }

  /** Registry rules of the weights; adds violations to {@code errors}. */
  static RankingQuery.Weights resolveWeights(
      ParsedWeights parsed, ReferenceSnapshot reference, List<FieldViolation> errors) {
    TreeMap<String, BigDecimal> overrides = new TreeMap<>();
    boolean unknown = false;
    for (Map.Entry<String, BigDecimal> entry : parsed.overrides().entrySet()) {
      if (reference.nutrient(entry.getKey()).isEmpty()) {
        errors.add(unknownNutrient("weights.overrides." + entry.getKey(), entry.getKey()));
        unknown = true;
      } else {
        overrides.put(entry.getKey(), entry.getValue());
      }
    }
    RankingQuery.Weights weights = new RankingQuery.Weights(parsed.categories(), overrides);
    if (!unknown && allZero(weights, reference)) {
      errors.add(
          new FieldViolation(
              "weights", "weights_all_zero", "The weights give every nutrient a weight of 0."));
    }
    return weights;
  }

  /** Throws {@code 422 validation_failed} when {@code errors} is not empty. */
  static void failIfAny(List<FieldViolation> errors) {
    if (!errors.isEmpty()) {
      throw ProblemException.validation(DETAIL, errors);
    }
  }

  private static boolean allZero(RankingQuery.Weights weights, ReferenceSnapshot reference) {
    for (NutrientView nutrient : reference.nutrients()) {
      BigDecimal category = weights.categories().get(nutrient.category());
      BigDecimal override = weights.overrides().getOrDefault(nutrient.code(), BigDecimal.ONE);
      if (category.multiply(override).signum() != 0) {
        return false;
      }
    }
    return true;
  }

  /**
   * {@code amount} (or 1 when only the unit is given) of {@code code} in the canonical unit, or
   * empty when the unit needs a form: IU is convertible only where one form-independent rule exists
   * (VIT_D); a conversion that would have to assume a form is rejected.
   */
  private static Optional<BigDecimal> canonicalAmount(
      ReferenceSnapshot reference, String code, BigDecimal amount, Unit unit, Unit canonical) {
    try {
      ConversionResult result =
          reference
              .conversions()
              .convert(code, amount == null ? BigDecimal.ONE : amount, unit, null, canonical);
      return result.assumed() ? Optional.empty() : Optional.of(result.amount());
    } catch (UnitConversionException e) {
      return Optional.empty();
    }
  }

  private static String country(String country, List<FieldViolation> errors) {
    if (country == null) {
      errors.add(required("country"));
    } else if (!COUNTRY.matcher(country).matches()) {
      errors.add(
          new FieldViolation("country", "invalid_format", "country must be two capital letters."));
    }
    return country;
  }

  private static void currency(String currency, List<FieldViolation> errors) {
    if (currency != null && !CURRENCY.matcher(currency).matches()) {
      errors.add(
          new FieldViolation(
              "displayCurrency",
              "invalid_format",
              "displayCurrency must be three capital letters."));
    }
  }

  private static StockFilter stockFilter(String raw, List<FieldViolation> errors) {
    if (raw == null) {
      return StockFilter.IN_STOCK_ONLY;
    }
    Optional<StockFilter> filter = parseEnum(StockFilter.class, raw);
    if (filter.isEmpty()) {
      errors.add(
          new FieldViolation(
              "stockFilter",
              "invalid_format",
              "stockFilter must be IN_STOCK_ONLY or INCLUDE_OUT_OF_STOCK."));
      return StockFilter.IN_STOCK_ONLY;
    }
    return filter.get();
  }

  private static UUID productId(String raw, String field, List<FieldViolation> errors) {
    if (raw == null) {
      errors.add(required(field));
      return null;
    }
    if (!UUID_TEXT.matcher(raw).matches()) {
      errors.add(new FieldViolation(field, "invalid_format", field + " must be a UUID."));
      return null;
    }
    return UUID.fromString(raw);
  }

  private static ParsedWeights weights(WeightsInput input, List<FieldViolation> errors) {
    Map<NutrientCategory, BigDecimal> categories = new EnumMap<>(NutrientCategory.class);
    WeightDefaults.categoryDefaults()
        .forEach((category, weight) -> categories.put(category, BigDecimal.valueOf(weight)));
    Map<String, BigDecimal> overrides = new TreeMap<>();
    if (input == null) {
      return new ParsedWeights(categories, overrides);
    }
    if (input.categories() != null) {
      for (Map.Entry<String, BigDecimal> entry : input.categories().entrySet()) {
        String path = "weights.categories." + entry.getKey();
        Optional<NutrientCategory> category = parseEnum(NutrientCategory.class, entry.getKey());
        if (category.isEmpty()) {
          errors.add(
              new FieldViolation(
                  path,
                  "unknown_field",
                  "Category must be one of VITAMIN, MINERAL, COENZYME, OTHER."));
        } else if (entry.getValue() == null) {
          errors.add(new FieldViolation(path, "invalid_format", "A weight must be a number."));
        } else if (!withinPrecision(entry.getValue())) {
          errors.add(tooPrecise(path));
        } else if (entry.getValue().signum() < 0 || entry.getValue().compareTo(CATEGORY_MAX) > 0) {
          errors.add(outOfRange(path, "0", "2"));
        } else {
          categories.put(category.get(), entry.getValue());
        }
      }
    }
    if (input.overrides() != null) {
      for (Map.Entry<String, BigDecimal> entry : input.overrides().entrySet()) {
        String path = "weights.overrides." + entry.getKey();
        if (!NUTRIENT_CODE.matcher(entry.getKey()).matches()) {
          errors.add(
              new FieldViolation(
                  path, "invalid_format", "An override key must be a nutrient code."));
        } else if (entry.getValue() == null) {
          errors.add(new FieldViolation(path, "invalid_format", "A weight must be a number."));
        } else if (!withinPrecision(entry.getValue())) {
          errors.add(tooPrecise(path));
        } else if (entry.getValue().signum() < 0 || entry.getValue().compareTo(OVERRIDE_MAX) > 0) {
          errors.add(outOfRange(path, "0", "3"));
        } else {
          overrides.put(entry.getKey(), entry.getValue());
        }
      }
    }
    return new ParsedWeights(categories, overrides);
  }

  private static void requiredNutrient(
      RequiredNutrientInput input, String path, List<FieldViolation> errors) {
    if (input == null) {
      errors.add(required(path));
      return;
    }
    if (input.code() == null) {
      errors.add(required(path + ".code"));
    } else if (!NUTRIENT_CODE.matcher(input.code()).matches()) {
      errors.add(
          new FieldViolation(path + ".code", "invalid_format", "code must be a nutrient code."));
    }
    if (input.minAmountPerDay() != null && !withinPrecision(input.minAmountPerDay())) {
      errors.add(tooPrecise(path + ".minAmountPerDay"));
    } else if (input.minAmountPerDay() != null && input.minAmountPerDay().signum() <= 0) {
      errors.add(
          new FieldViolation(
              path + ".minAmountPerDay", "out_of_range", "minAmountPerDay must be above 0."));
    }
    if (input.unit() == null) {
      if (input.minAmountPerDay() != null) {
        errors.add(required(path + ".unit"));
      }
    } else if (Unit.fromSymbol(input.unit()).isEmpty()) {
      errors.add(
          new FieldViolation(path + ".unit", "invalid_format", "unit must be mg, mcg, g or IU."));
    }
  }

  private static <E extends Enum<E>> Optional<E> parseEnum(Class<E> type, String raw) {
    for (E value : type.getEnumConstants()) {
      if (value.name().equals(raw)) {
        return Optional.of(value);
      }
    }
    return Optional.empty();
  }

  private static FieldViolation required(String field) {
    return new FieldViolation(field, "required", "Value is required.");
  }

  /**
   * Whether {@code value} has at most {@link #MAX_FRACTION_DIGITS} decimal places and at most
   * {@link #MAX_INTEGER_DIGITS} integer digits once trailing zeros are removed. Only the unscaled
   * digits and the scale are inspected, so the check costs time proportional to the digits that
   * were sent, whatever the exponent.
   */
  static boolean withinPrecision(BigDecimal value) {
    if (value.signum() == 0) {
      return true;
    }
    BigDecimal stripped = value.stripTrailingZeros();
    long fractionDigits = Math.max(0L, stripped.scale());
    long integerDigits = (long) stripped.precision() - stripped.scale();
    return fractionDigits <= MAX_FRACTION_DIGITS && integerDigits <= MAX_INTEGER_DIGITS;
  }

  private static FieldViolation tooPrecise(String field) {
    return new FieldViolation(
        field,
        "out_of_range",
        field
            + " must have at most "
            + MAX_INTEGER_DIGITS
            + " integer digits and "
            + MAX_FRACTION_DIGITS
            + " decimal places.");
  }

  private static FieldViolation outOfRange(String field, String min, String max) {
    return new FieldViolation(
        field, "out_of_range", field + " must be between " + min + " and " + max + ".");
  }

  private static FieldViolation unknownNutrient(String field, String code) {
    return new FieldViolation(
        field, "unknown_nutrient_code", code + " is not a nutrient of the registry.");
  }
}
