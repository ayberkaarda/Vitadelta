package com.vitadelta.api.entitlement;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The response-level representability rule of {@link PriceConversion#present}. */
class PriceConversionTest {

  private static PriceConversion rate(String rate) {
    return new PriceConversion(
        "TRY",
        "USD",
        new PriceConversion.Fx(
            "TRY",
            "USD",
            new BigDecimal(rate),
            LocalDate.of(2026, 11, 24),
            PriceConversion.SOURCE_FRANKFURTER,
            false,
            PriceConversion.OK));
  }

  @Test
  void aConversionThatShowsEveryAmountIsUsedOnce() {
    List<PriceConversion> used = new ArrayList<>();
    BigDecimal shown =
        PriceConversion.present(
            rate("0.02413700"),
            c -> {
              used.add(c);
              return c.convert(new BigDecimal("199.00"));
            });
    assertThat(used).hasSize(1);
    assertThat(shown).isEqualTo(new BigDecimal("4.8033"));
  }

  @Test
  void onePositiveAmountShownAsZeroRendersTheWholeResponseNatively() {
    List<PriceConversion> used = new ArrayList<>();
    List<BigDecimal> shown =
        PriceConversion.present(
            rate("0.00000001"),
            c -> {
              used.add(c);
              return List.of(c.convert(new BigDecimal("1000000")), c.convert(new BigDecimal("2")));
            });
    assertThat(used).hasSize(2);
    PriceConversion fallback = used.get(1);
    assertThat(fallback.currency()).isEqualTo("TRY");
    assertThat(fallback.fx().status()).isEqualTo(PriceConversion.UNAVAILABLE);
    assertThat(fallback.fx().rate()).as("identifies the unusable row").isEqualTo("0.00000001");
    assertThat(fallback.fx().asOf()).isEqualTo(LocalDate.of(2026, 11, 24));
    assertThat(fallback.fx().stale()).isFalse();
    assertThat(fallback.fx().quote()).isEqualTo("USD");
    assertThat(fallback.convert(new BigDecimal("2"))).as("not applied").isEqualTo("2.0000");
    assertThat(shown).containsExactly(new BigDecimal("1000000.0000"), new BigDecimal("2.0000"));
  }

  @Test
  void zeroAndNegligibleNativeAmountsDoNotMakeTheRateUnusable() {
    List<PriceConversion> used = new ArrayList<>();
    PriceConversion.present(
        rate("0.00000001"),
        c -> {
          used.add(c);
          c.convert(BigDecimal.ZERO);
          c.convert(new BigDecimal("0.00001"));
          return c.convert(new BigDecimal("100000000"));
        });
    assertThat(used).hasSize(1);
  }

  @Test
  void aNegativeAmountShownAsZeroAlsoCounts() {
    List<PriceConversion> used = new ArrayList<>();
    PriceConversion.present(
        rate("0.00000300"),
        c -> {
          used.add(c);
          return c.convert(new BigDecimal("-3.5"));
        });
    assertThat(used).hasSize(2);
  }

  @Test
  void identityAndUnavailableConversionsAreNeverReplaced() {
    List<PriceConversion> used = new ArrayList<>();
    PriceConversion.present(
        PriceConversion.identity("TRY"),
        c -> {
          used.add(c);
          return c.convert(new BigDecimal("0.0001"));
        });
    PriceConversion.present(
        PriceConversion.unavailable("TRY", "USD"),
        c -> {
          used.add(c);
          return c.convert(new BigDecimal("5"));
        });
    assertThat(used).hasSize(2);
  }

  @Test
  void conversionsOutsideAResponseAreUntracked() {
    PriceConversion tiny = rate("0.00000001");
    assertThat(tiny.convert(new BigDecimal("5"))).isEqualTo(new BigDecimal("0.0000"));
    List<PriceConversion> used = new ArrayList<>();
    PriceConversion.present(
        rate("0.50000000"),
        c -> {
          used.add(c);
          return c.convert(new BigDecimal("5"));
        });
    assertThat(used).hasSize(1);
  }
}
