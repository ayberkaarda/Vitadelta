package com.vitadelta.api.recommendation;

import com.vitadelta.api.catalog.read.CatalogViews.NutrientView;
import com.vitadelta.api.catalog.unit.UnitConversionTable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The nutrient registry and the unit conversion table of one catalog version, used to validate a
 * request (override keys, all-zero weights, required-nutrient units) before any quota is reserved.
 * Immutable.
 */
final class ReferenceSnapshot {

  private final long catalogVersion;
  private final List<NutrientView> nutrients;
  private final Map<String, NutrientView> byCode;
  private final UnitConversionTable conversions;

  ReferenceSnapshot(
      long catalogVersion, List<NutrientView> nutrients, UnitConversionTable conversions) {
    this.catalogVersion = catalogVersion;
    this.nutrients = List.copyOf(nutrients);
    Map<String, NutrientView> map = new LinkedHashMap<>();
    this.nutrients.forEach(n -> map.put(n.code(), n));
    this.byCode = Map.copyOf(map);
    this.conversions = conversions;
  }

  long catalogVersion() {
    return catalogVersion;
  }

  /** The whole registry in registry order. */
  List<NutrientView> nutrients() {
    return nutrients;
  }

  /** The registry entry of {@code code}. */
  Optional<NutrientView> nutrient(String code) {
    return Optional.ofNullable(byCode.get(code));
  }

  UnitConversionTable conversions() {
    return conversions;
  }
}
