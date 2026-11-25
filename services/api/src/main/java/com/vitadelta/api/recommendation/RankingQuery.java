package com.vitadelta.api.recommendation;

import com.vitadelta.api.catalog.NutrientCategory;
import com.vitadelta.api.catalog.price.StockFilter;
import com.vitadelta.api.catalog.unit.Unit;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A validated, normalized ranking query: everything that decides the full ranking (section 7.5).
 * Defaults are filled in, so semantically equal requests are equal here and share one cache key;
 * the principal, the display currency and the page are not part of it.
 *
 * @param lambda the CUSTOM-mode weight, {@code null} for every other mode
 * @param requiredNutrients in request order (indexes match the request's {@code
 *     requiredNutrients}); minimums are converted into the canonical unit
 */
record RankingQuery(
    String country,
    StockFilter stockFilter,
    RankingMode mode,
    BigDecimal lambda,
    Weights weights,
    List<RequiredNutrient> requiredNutrients,
    boolean excludeExceedingUl) {

  RankingQuery {
    requiredNutrients = List.copyOf(requiredNutrients);
  }

  /**
   * Weights with every category set and the overrides in code order.
   *
   * @param categories weight of every category
   * @param overrides per-nutrient multipliers, by code
   */
  record Weights(
      Map<NutrientCategory, BigDecimal> categories, SortedMap<String, BigDecimal> overrides) {

    Weights {
      Map<NutrientCategory, BigDecimal> all = new EnumMap<>(NutrientCategory.class);
      all.putAll(categories);
      if (all.size() != NutrientCategory.values().length) {
        throw new IllegalArgumentException("every category needs a weight");
      }
      categories = java.util.Collections.unmodifiableMap(all);
      overrides = java.util.Collections.unmodifiableSortedMap(new TreeMap<>(overrides));
    }
  }

  /**
   * A required nutrient.
   *
   * @param minAmountPerDay minimum daily amount in {@code unit}, {@code null} when only presence is
   *     required
   * @param unit the nutrient's canonical unit
   */
  record RequiredNutrient(String code, BigDecimal minAmountPerDay, Unit unit) {}

  /**
   * The cache key: SHA-256 (hex) of the canonical JSON of {@code {catalog_version, country,
   * exclude_exceeding_ul, ranking, required_nutrients, stock_filter, weights}} with keys in
   * lexicographic order, numbers in the canonical form of {@link #number}, overrides by code and
   * required nutrients by code. {@code ranking.lambda} is present only for CUSTOM.
   */
  String cacheKey(long catalogVersion) {
    StringBuilder json = new StringBuilder(256);
    json.append("{\"catalog_version\":").append(catalogVersion);
    json.append(",\"country\":").append(text(country));
    json.append(",\"exclude_exceeding_ul\":").append(excludeExceedingUl);
    json.append(",\"ranking\":{");
    if (mode == RankingMode.CUSTOM) {
      json.append("\"lambda\":").append(number(lambda)).append(',');
    }
    json.append("\"mode\":").append(text(mode.name())).append('}');
    json.append(",\"required_nutrients\":[");
    List<RequiredNutrient> sorted =
        requiredNutrients.stream().sorted(Comparator.comparing(RequiredNutrient::code)).toList();
    for (int i = 0; i < sorted.size(); i++) {
      RequiredNutrient required = sorted.get(i);
      json.append(i == 0 ? "" : ",").append("{\"code\":").append(text(required.code()));
      if (required.minAmountPerDay() != null) {
        json.append(",\"min_amount_per_day\":").append(number(required.minAmountPerDay()));
      }
      json.append(",\"unit\":").append(text(required.unit().symbol())).append('}');
    }
    json.append("],\"stock_filter\":").append(text(stockFilter.name()));
    json.append(",\"weights\":{\"category_weights\":{");
    SortedMap<String, BigDecimal> categories = new TreeMap<>();
    weights.categories().forEach((category, weight) -> categories.put(category.name(), weight));
    appendNumbers(json, categories);
    json.append("},\"overrides\":{");
    appendNumbers(json, weights.overrides());
    json.append("}}}");
    return sha256(json.toString());
  }

  private static void appendNumbers(StringBuilder json, SortedMap<String, BigDecimal> values) {
    boolean first = true;
    for (Map.Entry<String, BigDecimal> entry : values.entrySet()) {
      json.append(first ? "" : ",").append(text(entry.getKey())).append(':');
      json.append(number(entry.getValue()));
      first = false;
    }
  }

  /** Keys and codes are validated identifiers ({@code [A-Z0-9_]}); no escaping is needed. */
  private static String text(String value) {
    return '"' + value + '"';
  }

  /**
   * The canonical JSON form of a number: the unscaled digits of the value without trailing zeros,
   * followed by {@code e<exponent>} when the exponent is not 0 (for example {@code 15e-1} for 1.5,
   * {@code 2} for 2.00). Equal values give the same text, and its length is bounded by the digits
   * of the value whatever its exponent, so no plain expansion is ever built.
   */
  static String number(BigDecimal value) {
    if (value.signum() == 0) {
      return "0";
    }
    BigDecimal stripped = value.stripTrailingZeros();
    String digits = stripped.unscaledValue().toString();
    return stripped.scale() == 0 ? digits : digits + "e" + (-(long) stripped.scale());
  }

  private static String sha256(String canonical) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
