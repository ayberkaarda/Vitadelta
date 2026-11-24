package com.vitadelta.api.imports;

import com.vitadelta.api.catalog.unit.Unit;
import com.vitadelta.api.catalog.unit.UnitConversionTable;
import java.util.Map;
import java.util.Optional;

/**
 * Registry data that import validation checks against.
 *
 * @param canonicalUnits canonical unit per nutrient code
 * @param formNutrients the form registry: nutrient code per form code
 * @param countryCurrencies currency per country code
 */
public record CatalogRegistry(
    Map<String, Unit> canonicalUnits,
    Map<String, String> formNutrients,
    Map<String, String> countryCurrencies,
    UnitConversionTable conversions) {

  public CatalogRegistry {
    canonicalUnits = Map.copyOf(canonicalUnits);
    formNutrients = Map.copyOf(formNutrients);
    countryCurrencies = Map.copyOf(countryCurrencies);
  }

  public Optional<Unit> canonicalUnit(String nutrientCode) {
    return Optional.ofNullable(canonicalUnits.get(nutrientCode));
  }

  public Optional<String> currencyOf(String countryCode) {
    return Optional.ofNullable(countryCurrencies.get(countryCode));
  }
}
