package com.vitadelta.api.catalog.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitadelta.api.catalog.NutrientCategory;
import com.vitadelta.api.catalog.UlBasis;
import com.vitadelta.api.catalog.read.CatalogViews.NutrientFacts;
import com.vitadelta.api.catalog.read.CatalogViews.ProductNutrientView;
import com.vitadelta.api.catalog.read.CatalogViews.ReferenceAmount;
import com.vitadelta.api.catalog.read.CatalogViews.UpperLimitView;
import com.vitadelta.api.catalog.unit.Unit;
import com.vitadelta.api.catalog.web.ProductPresenter.Nutrient;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The regulatory facts of one nutrient row ({@link ProductPresenter#nutrient}) on synthetic catalog
 * rows: the UL matching rule, exempt and form-dependent limits, the UL-relevant amount, the strict
 * comparison, the single rounding and the canonical-unit invariant.
 */
class NutrientFactsPresenterTest {

  private static final String NIACIN_NOTE = "Synthetic test note: niacin forms";

  private static final NutrientFacts EFSA_LIKE =
      new NutrientFacts(
          "TEST_REF",
          "TEST_UL",
          List.of(
              new ReferenceAmount("VIT_B3", new BigDecimal("16"), Unit.MG),
              new ReferenceAmount("VIT_A", new BigDecimal("800"), Unit.MCG),
              new ReferenceAmount("ZINC", new BigDecimal("3"), Unit.MG)),
          List.of(
              new UpperLimitView(
                  "VIT_A", "BETA_CAROTENE", null, null, null, "Synthetic test note: exempt"),
              new UpperLimitView(
                  "VIT_A",
                  "RETINYL_ACETATE",
                  new BigDecimal("3000"),
                  Unit.MCG,
                  UlBasis.TOTAL,
                  "Synthetic test note: preformed"),
              new UpperLimitView(
                  "VIT_B3",
                  "NICOTINAMIDE",
                  new BigDecimal("900"),
                  Unit.MG,
                  UlBasis.SUPPLEMENTAL,
                  NIACIN_NOTE),
              new UpperLimitView(
                  "VIT_B3",
                  "NICOTINIC_ACID",
                  new BigDecimal("10"),
                  Unit.MG,
                  UlBasis.SUPPLEMENTAL,
                  NIACIN_NOTE),
              new UpperLimitView("ZINC", "ANY", new BigDecimal("25"), Unit.MG, UlBasis.TOTAL, ""),
              new UpperLimitView(
                  "ZINC",
                  "ZINC_PICOLINATE",
                  new BigDecimal("20"),
                  Unit.MG,
                  UlBasis.SUPPLEMENTAL,
                  "Synthetic test note: picolinate")));

  private static ProductNutrientView row(
      String code, String amount, Unit unit, String ulAmount, String form) {
    return new ProductNutrientView(
        code,
        NutrientCategory.VITAMIN,
        new BigDecimal(amount),
        unit,
        ulAmount == null ? null : new BigDecimal(ulAmount),
        form,
        "synthetic label",
        false);
  }

  private static Nutrient present(ProductNutrientView row, String servingsPerDay) {
    return ProductPresenter.nutrient(row, new BigDecimal(servingsPerDay), EFSA_LIKE);
  }

  @Test
  void aFormDependentLimitDoesNotApplyToAnUnstatedForm() {
    Nutrient niacin = present(row("VIT_B3", "20", Unit.MG, null, "UNKNOWN"), "1");
    assertThat(niacin.upperLimit()).as("UL_FORM_DEPENDENT: no ANY row").isNull();
    assertThat(niacin.reference().percentOfReference()).isEqualByComparingTo("125");

    Nutrient nicotinamide = present(row("VIT_B3", "20", Unit.MG, null, "NICOTINAMIDE"), "1");
    assertThat(nicotinamide.upperLimit().formCode()).isEqualTo("NICOTINAMIDE");
    assertThat(nicotinamide.upperLimit().basis()).isEqualTo(UlBasis.SUPPLEMENTAL);
    assertThat(nicotinamide.upperLimit().note()).isEqualTo(NIACIN_NOTE);
    assertThat(nicotinamide.upperLimit().exceeded()).isFalse();
  }

  @Test
  void theFormRowWinsOverAnyAndUnknownOrUnlistedFormsFallBackToAny() {
    assertThat(present(row("ZINC", "21", Unit.MG, null, "ZINC_PICOLINATE"), "1").upperLimit())
        .satisfies(u -> {
          assertThat(u.formCode()).isEqualTo("ZINC_PICOLINATE");
          assertThat(u.amount().toPlainString()).isEqualTo("20.0000");
          assertThat(u.exceeded()).isTrue();
        });
    assertThat(present(row("ZINC", "21", Unit.MG, null, "UNKNOWN"), "1").upperLimit())
        .satisfies(u -> {
          assertThat(u.formCode()).isEqualTo("ANY");
          assertThat(u.exceeded()).isFalse();
          assertThat(u.note()).as("an empty note is written as null").isNull();
        });
    assertThat(present(row("ZINC", "21", Unit.MG, null, "ZINC_GLUCONATE"), "1").upperLimit())
        .satisfies(u -> assertThat(u.formCode()).isEqualTo("ANY"));
  }

  @Test
  void anExemptRowMeansNoUpperLimit() {
    Nutrient betaCarotene = present(row("VIT_A", "4000", Unit.MCG, null, "BETA_CAROTENE"), "1");
    assertThat(betaCarotene.upperLimit()).isNull();
    assertThat(betaCarotene.reference().percentOfReference().toPlainString()).isEqualTo("500.0000");
  }

  @Test
  void theUlRelevantAmountIsComparedAndTheEqualityBoundaryIsNotExceeded() {
    // 2 servings of 1600 mcg = 3200 mcg per day, above the UL; only 2 x 1000 mcg is UL-relevant.
    Nutrient mixed = present(row("VIT_A", "1600", Unit.MCG, "1000", "RETINYL_ACETATE"), "2");
    assertThat(mixed.amountPerDay().toPlainString()).isEqualTo("3200.0000");
    assertThat(mixed.upperLimit().ulAmountPerDay().toPlainString()).isEqualTo("2000.0000");
    assertThat(mixed.upperLimit().exceeded()).isFalse();

    Nutrient equal = present(row("VIT_A", "1500", Unit.MCG, null, "RETINYL_ACETATE"), "2");
    assertThat(equal.upperLimit().ulAmountPerDay()).isEqualByComparingTo("3000");
    assertThat(equal.upperLimit().exceeded()).as("equal to the UL").isFalse();

    // 3000.00001 mcg rounds to 3000.0000 but is strictly above the UL.
    Nutrient above = present(row("VIT_A", "3000.00001", Unit.MCG, null, "RETINYL_ACETATE"), "1");
    assertThat(above.upperLimit().ulAmountPerDay().toPlainString()).isEqualTo("3000.0000");
    assertThat(above.upperLimit().exceeded()).isTrue();
  }

  @Test
  void thePercentIsRoundedOnceHalfEvenFromTheExactValues() {
    // 100 x 1 / 3 = 33.3333...
    assertThat(present(row("ZINC", "1", Unit.MG, null, "UNKNOWN"), "1").reference())
        .satisfies(r -> {
          assertThat(r.percentOfReference().toPlainString()).isEqualTo("33.3333");
          assertThat(r.amount().toPlainString()).isEqualTo("3.0000");
          assertThat(r.setCode()).isEqualTo("TEST_REF");
          assertThat(r.source()).isEqualTo("REGULATORY");
        });
    // 100 x 0.000025 x 1.5 / 3 = 0.00125 exactly: half-even keeps the 2.
    assertThat(
            present(row("ZINC", "0.000025", Unit.MG, null, "UNKNOWN"), "1.5")
                .reference()
                .percentOfReference()
                .toPlainString())
        .isEqualTo("0.0012");
    // Rounding the amount first (0.0000) would give 0; the exact daily amount is used.
    assertThat(
            present(row("ZINC", "0.00003", Unit.MG, null, "UNKNOWN"), "1")
                .reference()
                .percentOfReference()
                .toPlainString())
        .isEqualTo("0.0010");
  }

  @Test
  void aNutrientOutsideBothSetsHasNeitherFact() {
    Nutrient coq10 = present(row("COQ10", "100", Unit.MG, null, "UBIQUINONE"), "1");
    assertThat(coq10.reference()).isNull();
    assertThat(coq10.upperLimit()).isNull();
  }

  @Test
  void aFactStoredOutsideTheCanonicalUnitIsADataError() {
    assertThatThrownBy(() -> present(row("VIT_A", "800", Unit.MG, null, "RETINYL_ACETATE"), "1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("canonical unit");
  }
}
