package com.vitadelta.api.catalog.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitadelta.api.catalog.seed.SeedService;
import com.vitadelta.api.support.PostgresIntegrationTest;
import com.vitadelta.api.support.TestData;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The conversion service over the seeded {@code unit_conversion} table. */
@SpringBootTest
class UnitConversionServiceTest extends PostgresIntegrationTest {

  @Autowired private UnitConversionService service;
  @Autowired private SeedService seedService;

  @BeforeEach
  void seed() {
    seedService.seed(TestData.dataDir());
  }

  @Test
  void convertsIntoTheCanonicalUnit() {
    ConversionResult vitaminD =
        service.toCanonical("VIT_D", new BigDecimal("1000"), Unit.IU, "D3_CHOLECALCIFEROL");
    assertThat(vitaminD.amount()).isEqualByComparingTo("25");
    assertThat(vitaminD.unit()).isEqualTo(Unit.MCG);

    ConversionResult potassium =
        service.toCanonical("POTASSIUM", new BigDecimal("1.5"), Unit.G, "UNKNOWN");
    assertThat(potassium.amount()).isEqualByComparingTo("1500");
    assertThat(potassium.unit()).isEqualTo(Unit.MG);

    ConversionResult vitaminE =
        service.toCanonical("VIT_E", new BigDecimal("30"), Unit.IU, "UNKNOWN");
    assertThat(vitaminE.amount()).isEqualByComparingTo("20.1");
    assertThat(vitaminE.assumed()).isTrue();
  }

  @Test
  void seededTableMatchesTheSpecificationRows() {
    UnitConversionTable table = service.currentTable();
    assertThat(table.acceptsIu("VIT_A")).isTrue();
    assertThat(table.acceptsIu("VIT_D")).isTrue();
    assertThat(table.acceptsIu("VIT_E")).isTrue();
    assertThat(table.acceptsIu("VIT_C")).isFalse();
    assertThat(
            table
                .convert("VIT_E", BigDecimal.ONE, Unit.IU, "DL_ALPHA_TOCOPHEROL", Unit.MG)
                .amount())
        .isEqualByComparingTo("0.45");
    assertThat(table.convert("VIT_A", BigDecimal.TEN, Unit.IU, "RETINOL", Unit.MCG).amount())
        .isEqualByComparingTo("3");
    assertThatThrownBy(
            () -> table.convert("VIT_A", BigDecimal.TEN, Unit.IU, "BETA_CAROTENE", Unit.MCG))
        .isInstanceOfSatisfying(
            UnitConversionException.class,
            e ->
                assertThat(e.reason())
                    .isEqualTo(UnitConversionException.Reason.UNSUPPORTED_IU_FORM));
  }

  @Test
  void unknownNutrientIsRejected() {
    assertThatThrownBy(() -> service.toCanonical("NOPE", BigDecimal.ONE, Unit.MG, "UNKNOWN"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
