package com.vitadelta.api.catalog.seed;

import com.vitadelta.api.catalog.ReferenceValues.CountryRow;
import com.vitadelta.api.catalog.ReferenceValues.FormRow;
import com.vitadelta.api.catalog.ReferenceValues.NutrientRow;
import com.vitadelta.api.catalog.ReferenceValues.ReferenceValueRow;
import com.vitadelta.api.catalog.ReferenceValues.SetRow;
import com.vitadelta.api.catalog.ReferenceValues.UpperLimitRow;
import com.vitadelta.api.catalog.unit.ConversionRule;
import com.vitadelta.api.catalog.unit.UnitConversionTable;
import com.vitadelta.api.imports.CatalogRegistry;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Validated content of {@code data/reference}.
 *
 * @param formRows form registry rows (code, nutrient, English name) in file order
 * @param forms the same form registry as nutrient code per form code
 */
public record ReferenceData(
    List<NutrientRow> nutrients,
    List<FormRow> formRows,
    Map<String, String> forms,
    List<ConversionRule> conversions,
    List<SetRow> referenceSets,
    List<ReferenceValueRow> referenceValues,
    List<SetRow> ulSets,
    List<UpperLimitRow> upperLimits,
    List<CountryRow> countries) {

  public ReferenceData {
    nutrients = List.copyOf(nutrients);
    formRows = List.copyOf(formRows);
    forms = Map.copyOf(forms);
    conversions = List.copyOf(conversions);
    referenceSets = List.copyOf(referenceSets);
    referenceValues = List.copyOf(referenceValues);
    ulSets = List.copyOf(ulSets);
    upperLimits = List.copyOf(upperLimits);
    countries = List.copyOf(countries);
  }

  /** The registry that product and offer validation checks against. */
  public CatalogRegistry registry() {
    return new CatalogRegistry(
        nutrients.stream().collect(Collectors.toMap(NutrientRow::code, NutrientRow::canonicalUnit)),
        forms,
        countries.stream().collect(Collectors.toMap(CountryRow::code, CountryRow::currency)),
        new UnitConversionTable(conversions));
  }
}
