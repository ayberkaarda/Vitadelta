package com.vitadelta.api.catalog;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Default weights and allowed ranges of a recommendation query ({@code GET /weights/defaults},
 * equal to the scoring service defaults): category weights in [0, 2], per-nutrient overrides in [0,
 * 3] with default 1, the CUSTOM-mode lambda in [0, 1] with default 0.5.
 */
public final class WeightDefaults {

  public static final double CATEGORY_MIN = 0.0;
  public static final double CATEGORY_MAX = 2.0;
  public static final double OVERRIDE_DEFAULT = 1.0;
  public static final double OVERRIDE_MIN = 0.0;
  public static final double OVERRIDE_MAX = 3.0;
  public static final double LAMBDA_DEFAULT = 0.5;
  public static final double LAMBDA_MIN = 0.0;
  public static final double LAMBDA_MAX = 1.0;

  private static final Map<NutrientCategory, Double> CATEGORY_DEFAULTS;

  static {
    Map<NutrientCategory, Double> defaults = new EnumMap<>(NutrientCategory.class);
    defaults.put(NutrientCategory.VITAMIN, 1.0);
    defaults.put(NutrientCategory.MINERAL, 1.0);
    defaults.put(NutrientCategory.COENZYME, 0.8);
    defaults.put(NutrientCategory.OTHER, 0.5);
    CATEGORY_DEFAULTS = Collections.unmodifiableMap(defaults);
  }

  private WeightDefaults() {}

  /** Default weight of every category, in category order. */
  public static Map<NutrientCategory, Double> categoryDefaults() {
    return CATEGORY_DEFAULTS;
  }

  /** Default weight of one category. */
  public static double categoryDefault(NutrientCategory category) {
    return CATEGORY_DEFAULTS.get(category);
  }
}
