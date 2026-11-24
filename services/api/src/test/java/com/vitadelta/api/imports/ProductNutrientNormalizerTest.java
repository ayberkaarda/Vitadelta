package com.vitadelta.api.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.catalog.ProductNutrientValues;
import com.vitadelta.api.catalog.unit.Unit;
import com.vitadelta.api.imports.ProductNutrientNormalizer.Outcome;
import com.vitadelta.api.imports.ProductNutrientNormalizer.RawNutrient;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProductNutrientNormalizerTest {

  private final CatalogRegistry registry = TestRegistry.create();

  private Outcome normalize(
      String nutrient, String amount, String unit, String ulAmount, String form, String label) {
    return ProductNutrientNormalizer.normalize(
        new RawNutrient(
            nutrient,
            amount == null ? null : new BigDecimal(amount),
            unit,
            ulAmount == null ? null : new BigDecimal(ulAmount),
            form,
            label),
        registry,
        "n[0]");
  }

  private ProductNutrientValues valid(Outcome outcome) {
    assertThat(outcome.problems()).isEmpty();
    return outcome.values().orElseThrow();
  }

  private void rejected(Outcome outcome, String code) {
    assertThat(outcome.values()).isEmpty();
    assertThat(outcome.problems()).extracting(ImportProblem::code).contains(code);
    assertThat(outcome.problems()).allSatisfy(p -> assertThat(p.location()).isEqualTo("n[0]"));
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({"µg, MCG", "μg, MCG", "ug, MCG", "mcg, MCG", "g, G", "mg, MG"})
  void normalizesUnitsAndKeepsTheLabelledAmount(String raw, Unit expected) {
    ProductNutrientValues values = valid(normalize("VIT_A", "800", raw, null, "RETINOL", null));
    assertThat(values.unit()).isEqualTo(expected);
    assertThat(values.amountPerServing()).isEqualByComparingTo("800");
  }

  @Test
  void lowerCaseIuIsNormalizedAndStoredInIu() {
    ProductNutrientValues values =
        valid(normalize("VIT_D", "1000", "iu", null, "D3_CHOLECALCIFEROL", "D3"));
    assertThat(values.unit()).isEqualTo(Unit.IU);
    assertThat(values.amountPerServing()).isEqualByComparingTo("1000");
    assertThat(values.formCode()).isEqualTo("D3_CHOLECALCIFEROL");
    assertThat(values.formLabel()).isEqualTo("D3");
  }

  @Test
  void vitaminEInIuWithUnknownFormIsAccepted() {
    ProductNutrientValues values = valid(normalize("VIT_E", "30", "IU", null, null, "vitamin E"));
    assertThat(values.formCode()).isEqualTo("UNKNOWN");
  }

  @Test
  void iuForANutrientWithoutIuRuleIsRejected() {
    rejected(normalize("ZINC", "10", "IU", null, null, null), "iu_not_allowed");
  }

  @Test
  void vitaminAInIuAsBetaCaroteneIsUnsupported() {
    rejected(normalize("VIT_A", "5000", "IU", null, "BETA_CAROTENE", null), "unsupported_iu_form");
  }

  @Test
  void vitaminAInIuWithUnknownFormIsUnsupported() {
    rejected(normalize("VIT_A", "5000", "IU", null, null, "vitamin A"), "unsupported_iu_form");
  }

  @Test
  void unknownUnitIsRejected() {
    rejected(normalize("VIT_C", "100", "kg", null, null, null), "invalid_unit");
  }

  @Test
  void unknownNutrientIsRejected() {
    rejected(normalize("NOT_A_NUTRIENT", "1", "mg", null, null, null), "unknown_nutrient");
  }

  @ParameterizedTest
  @CsvSource({"0", "-1", "1.23456", "10000000000"})
  void invalidAmountIsRejected(String amount) {
    rejected(normalize("VIT_C", amount, "mg", null, null, null), "invalid_amount");
  }

  @Test
  void missingAmountIsRejected() {
    rejected(normalize("VIT_C", null, "mg", null, null, null), "invalid_amount");
  }

  @Test
  void missingFormBecomesUnknown() {
    ProductNutrientValues values = valid(normalize("VIT_C", "100", "mg", null, null, null));
    assertThat(values.formCode()).isEqualTo("UNKNOWN");
    assertThat(values.formLabel()).isNull();
  }

  @Test
  void unregisteredFormBecomesUnknownAndKeepsRawText() {
    ProductNutrientValues values =
        valid(normalize("VIT_C", "100", "mg", null, "CALCIUM_ASCORBATE", null));
    assertThat(values.formCode()).isEqualTo("UNKNOWN");
    assertThat(values.formLabel()).isEqualTo("CALCIUM_ASCORBATE");
  }

  @Test
  void unregisteredFormKeepsAGivenLabel() {
    ProductNutrientValues values =
        valid(normalize("VIT_C", "100", "mg", null, "CALCIUM_ASCORBATE", "calcium ascorbate"));
    assertThat(values.formCode()).isEqualTo("UNKNOWN");
    assertThat(values.formLabel()).isEqualTo("calcium ascorbate");
  }

  @Test
  void explicitUnknownFormStaysUnknownWithoutLabel() {
    ProductNutrientValues values = valid(normalize("VIT_C", "100", "mg", null, "UNKNOWN", " "));
    assertThat(values.formCode()).isEqualTo("UNKNOWN");
    assertThat(values.formLabel()).isNull();
  }

  @Test
  void anyIsNotAProductForm() {
    rejected(normalize("VIT_C", "100", "mg", null, "ANY", null), "invalid_form_code");
  }

  @Test
  void formOfAnotherNutrientIsRejected() {
    rejected(normalize("ZINC", "10", "mg", null, "RETINOL", null), "form_nutrient_mismatch");
  }

  @Test
  void tooLongFormLabelIsRejected() {
    rejected(normalize("VIT_C", "100", "mg", null, null, "x".repeat(201)), "invalid_form_label");
  }

  @Test
  void labelBasisAmountIsKeptWhenWithinTheTotal() {
    ProductNutrientValues values =
        valid(normalize("VIT_A", "900", "mcg", "300", "RETINOL", "retinyl acetate + beta"));
    assertThat(values.ulAmountPerServing()).isEqualByComparingTo("300");
    assertThat(values.formCode()).isEqualTo("RETINOL");
    assertThat(valid(normalize("VIT_A", "900", "mcg", "0", "RETINOL", null)).ulAmountPerServing())
        .isEqualByComparingTo("0");
  }

  @ParameterizedTest
  @CsvSource({"901", "-1", "1.00001"})
  void labelBasisAmountOutsideBoundsIsRejected(String ulAmount) {
    rejected(normalize("VIT_A", "900", "mcg", ulAmount, "RETINOL", null), "invalid_ul_amount");
  }

  /** A registry whose conversion table has extra IU rows beyond the allow-list. */
  private static CatalogRegistry widenedRegistry() {
    CatalogRegistry base = TestRegistry.create();
    java.util.List<com.vitadelta.api.catalog.unit.ConversionRule> rules =
        new java.util.ArrayList<>(base.conversions().rules());
    rules.add(
        new com.vitadelta.api.catalog.unit.ConversionRule(
            "ZINC", Unit.IU, Unit.MG, BigDecimal.ONE, "ANY", null));
    rules.add(
        new com.vitadelta.api.catalog.unit.ConversionRule(
            "VIT_A", Unit.IU, Unit.MCG, new BigDecimal("0.05"), "BETA_CAROTENE", null));
    rules.add(
        new com.vitadelta.api.catalog.unit.ConversionRule(
            "VIT_A", Unit.IU, Unit.MCG, new BigDecimal("0.3"), "ANY", null));
    return new CatalogRegistry(
        base.canonicalUnits(),
        base.formNutrients(),
        base.countryCurrencies(),
        new com.vitadelta.api.catalog.unit.UnitConversionTable(rules));
  }

  @Test
  void addedConversionRowsCannotWidenTheIuAllowList() {
    CatalogRegistry widened = widenedRegistry();
    Outcome zinc =
        ProductNutrientNormalizer.normalize(
            new RawNutrient("ZINC", BigDecimal.TEN, "IU", null, null, null), widened, "n[0]");
    assertThat(zinc.problems()).extracting(ImportProblem::code).containsExactly("iu_not_allowed");
    Outcome betaCarotene =
        ProductNutrientNormalizer.normalize(
            new RawNutrient("VIT_A", BigDecimal.TEN, "IU", null, "BETA_CAROTENE", null),
            widened,
            "n[0]");
    assertThat(betaCarotene.problems())
        .extracting(ImportProblem::code)
        .containsExactly("unsupported_iu_form");
    Outcome unknownForm =
        ProductNutrientNormalizer.normalize(
            new RawNutrient("VIT_A", BigDecimal.TEN, "IU", null, null, "vitamin A"),
            widened,
            "n[0]");
    assertThat(unknownForm.problems())
        .extracting(ImportProblem::code)
        .containsExactly("unsupported_iu_form");
    Outcome retinol =
        ProductNutrientNormalizer.normalize(
            new RawNutrient("VIT_A", BigDecimal.TEN, "IU", null, "RETINOL", null), widened, "n[0]");
    assertThat(retinol.problems()).isEmpty();
  }
}
