package com.vitadelta.api.catalog.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class UnitConversionTableTest {

  /** The rows of the unit conversion table of the project specification, section 5.5. */
  static final List<ConversionRule> SPEC_RULES =
      List.of(
          rule("*", Unit.G, Unit.MG, "1000", "ANY"),
          rule("*", Unit.MG, Unit.MCG, "1000", "ANY"),
          rule("*", Unit.MCG, Unit.MG, "0.001", "ANY"),
          rule("*", Unit.MG, Unit.G, "0.001", "ANY"),
          rule("VIT_D", Unit.IU, Unit.MCG, "0.025", "ANY"),
          rule("VIT_A", Unit.IU, Unit.MCG, "0.3", "RETINOL"),
          rule("VIT_A", Unit.IU, Unit.MCG, "0.3", "RETINYL_ACETATE"),
          rule("VIT_A", Unit.IU, Unit.MCG, "0.3", "RETINYL_PALMITATE"),
          rule("VIT_E", Unit.IU, Unit.MG, "0.67", "D_ALPHA_TOCOPHEROL"),
          rule("VIT_E", Unit.IU, Unit.MG, "0.45", "DL_ALPHA_TOCOPHEROL"),
          rule("VIT_E", Unit.IU, Unit.MG, "0.67", "ANY"));

  private final UnitConversionTable table = new UnitConversionTable(SPEC_RULES);

  static ConversionRule rule(String nutrient, Unit from, Unit to, String factor, String form) {
    return new ConversionRule(nutrient, from, to, new BigDecimal(factor), form, null);
  }

  private ConversionResult convert(
      String nutrient, String amount, Unit from, String form, Unit to) {
    return table.convert(nutrient, new BigDecimal(amount), from, form, to);
  }

  @ParameterizedTest(name = "{0}: {1} {2} -> {4} {3}")
  @CsvSource({
    "CALCIUM, 1, g, mg, 1000",
    "VIT_C, 1.5, g, mg, 1500",
    "SELENIUM, 0.055, mg, mcg, 55",
    "ZINC, 2500, mcg, mg, 2.5",
    "POTASSIUM, 2500, mg, g, 2.5",
    "SELENIUM, 1, g, mcg, 1000000",
    "LYCOPENE, 2500000, mcg, g, 2.5"
  })
  void genericRowsConvertMassUnitsForEveryNutrient(
      String nutrient, String amount, String from, String to, String expected) {
    ConversionResult result =
        convert(
            nutrient,
            amount,
            Unit.fromSymbol(from).orElseThrow(),
            "UNKNOWN",
            Unit.fromSymbol(to).orElseThrow());
    assertThat(result.amount()).isEqualByComparingTo(expected);
    assertThat(result.unit().symbol()).isEqualTo(to);
    assertThat(result.assumed()).isFalse();
  }

  @Test
  void equalUnitsReturnTheAmountUnchanged() {
    ConversionResult result = convert("VIT_A", "800", Unit.MCG, "BETA_CAROTENE", Unit.MCG);
    assertThat(result.amount()).isEqualByComparingTo("800");
    assertThat(result.assumed()).isFalse();
  }

  @ParameterizedTest(name = "VIT_D IU with form {0}")
  @CsvSource({"D3_CHOLECALCIFEROL", "D2_ERGOCALCIFEROL", "UNKNOWN"})
  void vitaminDInIuUsesTheFormIndependentFactor(String form) {
    ConversionResult result = convert("VIT_D", "400", Unit.IU, form, Unit.MCG);
    assertThat(result.amount()).isEqualByComparingTo("10");
    assertThat(result.assumed()).isFalse();
  }

  @Test
  void vitaminDInIuCanBeChainedIntoMilligrams() {
    ConversionResult result = convert("VIT_D", "4000", Unit.IU, "UNKNOWN", Unit.MG);
    assertThat(result.amount()).isEqualByComparingTo("0.1");
  }

  @ParameterizedTest(name = "VIT_A IU with form {0}")
  @CsvSource({"RETINOL", "RETINYL_ACETATE", "RETINYL_PALMITATE"})
  void vitaminAInIuConvertsOnRetinolBasis(String form) {
    ConversionResult result = convert("VIT_A", "3333", Unit.IU, form, Unit.MCG);
    assertThat(result.amount()).isEqualByComparingTo("999.9");
    assertThat(result.assumed()).isFalse();
  }

  @ParameterizedTest(name = "VIT_A IU with form {0} is rejected")
  @CsvSource({"BETA_CAROTENE", "UNKNOWN", "D_ALPHA_TOCOPHEROL"})
  void vitaminAInIuWithoutRetinolFormIsUnsupported(String form) {
    assertThatThrownBy(() -> convert("VIT_A", "5000", Unit.IU, form, Unit.MCG))
        .isInstanceOfSatisfying(
            UnitConversionException.class,
            e -> {
              assertThat(e.reason()).isEqualTo(UnitConversionException.Reason.UNSUPPORTED_IU_FORM);
              assertThat(e.reason().code()).isEqualTo("unsupported_iu_form");
              assertThat(e.getMessage()).startsWith("unsupported_iu_form: ");
              assertThat(e.detail()).contains("VIT_A").contains(form);
            });
  }

  @Test
  void vitaminENaturalFormUsesNaturalFactor() {
    ConversionResult result = convert("VIT_E", "30", Unit.IU, "D_ALPHA_TOCOPHEROL", Unit.MG);
    assertThat(result.amount()).isEqualByComparingTo("20.1");
    assertThat(result.assumed()).isFalse();
  }

  @Test
  void vitaminESyntheticFormUsesSyntheticFactor() {
    ConversionResult result = convert("VIT_E", "30", Unit.IU, "DL_ALPHA_TOCOPHEROL", Unit.MG);
    assertThat(result.amount()).isEqualByComparingTo("13.5");
    assertThat(result.assumed()).isFalse();
  }

  @Test
  void vitaminEWithUnknownFormAssumesNaturalAndIsFlagged() {
    ConversionResult result = convert("VIT_E", "30", Unit.IU, "UNKNOWN", Unit.MG);
    assertThat(result.amount()).isEqualByComparingTo("20.1");
    assertThat(result.unit()).isEqualTo(Unit.MG);
    assertThat(result.assumed()).isTrue();
  }

  @Test
  void vitaminEWithNullFormIsTreatedAsUnknown() {
    ConversionResult result = table.convert("VIT_E", new BigDecimal("100"), Unit.IU, null, Unit.MG);
    assertThat(result.amount()).isEqualByComparingTo("67");
    assertThat(result.assumed()).isTrue();
  }

  @Test
  void vitaminEWithAFormOutsideItsRulesFallsBackToAnyAndIsFlagged() {
    ConversionResult result = convert("VIT_E", "10", Unit.IU, "RETINOL", Unit.MG);
    assertThat(result.amount()).isEqualByComparingTo("6.7");
    assertThat(result.assumed()).isTrue();
  }

  @Test
  void vitaminEInIuCanBeChainedIntoMicrograms() {
    ConversionResult result = convert("VIT_E", "1", Unit.IU, "DL_ALPHA_TOCOPHEROL", Unit.MCG);
    assertThat(result.amount()).isEqualByComparingTo("450");
    assertThat(result.assumed()).isFalse();
  }

  @ParameterizedTest(name = "IU for {0} is not allowed")
  @CsvSource({"VIT_C", "ZINC", "COQ10", "VIT_K"})
  void iuIsRejectedForNutrientsWithoutIuRule(String nutrient) {
    assertThatThrownBy(() -> convert(nutrient, "100", Unit.IU, "UNKNOWN", Unit.MG))
        .isInstanceOfSatisfying(
            UnitConversionException.class,
            e -> assertThat(e.reason()).isEqualTo(UnitConversionException.Reason.IU_NOT_ALLOWED));
    assertThat(table.acceptsIu(nutrient)).isFalse();
  }

  @Test
  void onlyVitaminsADAndEAcceptIu() {
    assertThat(table.acceptsIu("VIT_A")).isTrue();
    assertThat(table.acceptsIu("VIT_D")).isTrue();
    assertThat(table.acceptsIu("VIT_E")).isTrue();
  }

  @Test
  void conversionIntoIuIsRejected() {
    assertThatThrownBy(() -> convert("VIT_D", "10", Unit.MCG, "UNKNOWN", Unit.IU))
        .isInstanceOfSatisfying(
            UnitConversionException.class,
            e -> assertThat(e.reason()).isEqualTo(UnitConversionException.Reason.NO_CONVERSION));
  }

  @Test
  void missingMassRuleIsRejected() {
    UnitConversionTable onlyGrams =
        new UnitConversionTable(List.of(rule("*", Unit.G, Unit.MG, "1000", "ANY")));
    assertThatThrownBy(() -> onlyGrams.convert("ZINC", BigDecimal.ONE, Unit.MG, "UNKNOWN", Unit.G))
        .isInstanceOfSatisfying(
            UnitConversionException.class,
            e -> assertThat(e.reason()).isEqualTo(UnitConversionException.Reason.NO_CONVERSION));
  }

  @Test
  void nutrientSpecificMassRuleWinsOverGenericRule() {
    UnitConversionTable custom =
        new UnitConversionTable(
            List.of(
                rule("*", Unit.MG, Unit.MCG, "1000", "ANY"),
                rule("OMEGA3_EPA", Unit.MG, Unit.MCG, "999", "ANY")));
    assertThat(custom.convert("OMEGA3_EPA", BigDecimal.ONE, Unit.MG, "UNKNOWN", Unit.MCG).amount())
        .isEqualByComparingTo("999");
    assertThat(custom.convert("ZINC", BigDecimal.ONE, Unit.MG, "UNKNOWN", Unit.MCG).amount())
        .isEqualByComparingTo("1000");
  }

  @Test
  void conversionIsExactWithoutRounding() {
    ConversionResult result = convert("VIT_E", "0.0001", Unit.IU, "DL_ALPHA_TOCOPHEROL", Unit.MG);
    assertThat(result.amount()).isEqualByComparingTo("0.000045");
  }

  @Test
  void rulesAreValidatedOnConstruction() {
    assertThatThrownBy(() -> rule("*", Unit.MG, Unit.MCG, "0", "ANY"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> rule("*", Unit.MG, Unit.MG, "1", "ANY"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> rule("VIT_D", Unit.MCG, Unit.IU, "40", "ANY"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> rule("VIT_E", Unit.IU, Unit.MG, "0.67", "UNKNOWN"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> rule("*", Unit.G, Unit.MG, "1000", "RETINOL"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(rule("*", Unit.G, Unit.MG, "1000", "ANY").isGeneric()).isTrue();
    assertThat(table.rules()).hasSize(11);
  }
}
