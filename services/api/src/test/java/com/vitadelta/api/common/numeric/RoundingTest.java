package com.vitadelta.api.common.numeric;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RoundingTest {

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "13.33333333, 13.3333",
    "0.00005, 0.0001",
    "0.00015, 0.0001",
    "0.00025, 0.0003",
    "1.23455, 1.2346",
    "2.5, 2.5000",
    "-0.00004, 0.0000",
    "-1.23456, -1.2346",
    "0, 0.0000"
  })
  void roundsTheExactBinaryValueHalfEven(double value, String expected) {
    assertThat(Rounding.money(value).toPlainString()).isEqualTo(expected);
    assertThat(Rounding.score(value).toPlainString()).isEqualTo(expected);
    assertThat(Rounding.ratio(value).toPlainString()).isEqualTo(expected);
  }

  @Test
  void usesTheExactValueNotTheShortestDecimalString() {
    // 0.30005 is stored as 0.300049999999999983... and rounds down; 0.12345 is stored as
    // 0.123450000000000004... and rounds up, although both decimal strings end in 5.
    assertThat(Rounding.round(0.30005, 4).toPlainString()).isEqualTo("0.3000");
    assertThat(Rounding.round(0.12345, 4).toPlainString()).isEqualTo("0.1235");
    assertThat(Rounding.round(2.675, 2).toPlainString()).isEqualTo("2.67");
  }

  @ParameterizedTest
  @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
  void rejectsNonFiniteValues(double value) {
    assertThatThrownBy(() -> Rounding.money(value)).isInstanceOf(IllegalArgumentException.class);
  }
}
