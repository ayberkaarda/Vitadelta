package com.vitadelta.api.imports;

import com.vitadelta.api.catalog.unit.ConversionRule;
import com.vitadelta.api.catalog.unit.Unit;
import com.vitadelta.api.catalog.unit.UnitConversionTable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** A small registry for validation tests. */
final class TestRegistry {

  private TestRegistry() {}

  static CatalogRegistry create() {
    return new CatalogRegistry(
        Map.of(
            "VIT_A", Unit.MCG,
            "VIT_C", Unit.MG,
            "VIT_D", Unit.MCG,
            "VIT_E", Unit.MG,
            "ZINC", Unit.MG,
            "POTASSIUM", Unit.MG),
        Map.of(
            "RETINOL", "VIT_A",
            "BETA_CAROTENE", "VIT_A",
            "D3_CHOLECALCIFEROL", "VIT_D",
            "D_ALPHA_TOCOPHEROL", "VIT_E",
            "ZINC_GLUCONATE", "ZINC"),
        Map.of("TR", "TRY", "US", "USD"),
        new UnitConversionTable(
            List.of(
                rule("*", Unit.G, Unit.MG, "1000", "ANY"),
                rule("*", Unit.MG, Unit.MCG, "1000", "ANY"),
                rule("*", Unit.MCG, Unit.MG, "0.001", "ANY"),
                rule("VIT_D", Unit.IU, Unit.MCG, "0.025", "ANY"),
                rule("VIT_A", Unit.IU, Unit.MCG, "0.3", "RETINOL"),
                rule("VIT_E", Unit.IU, Unit.MG, "0.67", "D_ALPHA_TOCOPHEROL"),
                rule("VIT_E", Unit.IU, Unit.MG, "0.67", "ANY"))));
  }

  private static ConversionRule rule(
      String nutrient, Unit from, Unit to, String factor, String form) {
    return new ConversionRule(nutrient, from, to, new BigDecimal(factor), form, null);
  }
}
