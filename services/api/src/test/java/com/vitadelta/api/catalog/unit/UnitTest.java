package com.vitadelta.api.catalog.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class UnitTest {

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "mg, MG",
    "mcg, MCG",
    "ug, MCG",
    "µg, MCG",
    "μg, MCG",
    "g, G",
    "IU, IU",
    "iu, IU",
    "' mg ', MG"
  })
  void normalizesAcceptedSpellings(String raw, Unit expected) {
    assertThat(Unit.normalize(raw)).contains(expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"MG", "Mg", "kg", "ml", "Iu", "mcg/day", ""})
  void rejectsOtherSpellings(String raw) {
    assertThat(Unit.normalize(raw)).isEmpty();
  }

  @Test
  void nullIsRejected() {
    assertThat(Unit.normalize(null)).isEmpty();
  }

  @Test
  void storedSymbolsResolveExactly() {
    for (Unit unit : Unit.values()) {
      assertThat(Unit.fromSymbol(unit.symbol())).contains(unit);
    }
    assertThat(Unit.fromSymbol("ug")).isEmpty();
    assertThat(Unit.IU.isMass()).isFalse();
    assertThat(Unit.MCG.isMass()).isTrue();
  }

  @Test
  void attributeConverterRoundTrips() {
    UnitAttributeConverter converter = new UnitAttributeConverter();
    assertThat(converter.convertToDatabaseColumn(Unit.MCG)).isEqualTo("mcg");
    assertThat(converter.convertToEntityAttribute("IU")).isEqualTo(Unit.IU);
    assertThat(converter.convertToDatabaseColumn(null)).isNull();
    assertThat(converter.convertToEntityAttribute(null)).isNull();
    assertThatThrownBy(() -> converter.convertToEntityAttribute("kg"))
        .isInstanceOf(IllegalStateException.class);
  }
}
