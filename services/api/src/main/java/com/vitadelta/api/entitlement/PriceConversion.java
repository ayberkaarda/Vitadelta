package com.vitadelta.api.entitlement;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;
import java.util.function.Function;

/**
 * A money conversion (contract schemas {@code MoneyMeta} and {@code FxMeta}): displayed value =
 * native value x {@code rate}, rounded once to 4 decimals. With {@code status = UNAVAILABLE} the
 * rate is {@code null} and money stays in the native currency.
 *
 * @param currency currency of every converted amount
 */
public record PriceConversion(String nativeCurrency, String currency, Fx fx) {

  /** Contract schema {@code FxMeta}. */
  public record Fx(
      String base,
      String quote,
      BigDecimal rate,
      LocalDate asOf,
      @Schema(allowableValues = SOURCE_FRANKFURTER) String source,
      boolean stale,
      @Schema(allowableValues = {OK, UNAVAILABLE}) String status) {}

  /** The only {@code FxMeta.source} value (rates stored by the FX refresh job). */
  public static final String SOURCE_FRANKFURTER = "FRANKFURTER";

  /** {@code FxMeta.status} values. */
  public static final String OK = "OK";

  public static final String UNAVAILABLE = "UNAVAILABLE";

  public PriceConversion {
    Objects.requireNonNull(nativeCurrency, "nativeCurrency");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(fx, "fx");
  }

  /** Identity pair: rate 1, no date, no source. */
  public static PriceConversion identity(String currency) {
    return new PriceConversion(
        currency, currency, new Fx(currency, currency, BigDecimal.ONE, null, null, false, OK));
  }

  /** No rate for the pair: money stays in {@code nativeCurrency}. */
  public static PriceConversion unavailable(String nativeCurrency, String displayCurrency) {
    return new PriceConversion(
        nativeCurrency,
        nativeCurrency,
        new Fx(nativeCurrency, displayCurrency, null, null, null, false, UNAVAILABLE));
  }

  /** {@code amountNative} in {@link #currency()}, rounded once to 4 decimals (half-even). */
  public BigDecimal convert(BigDecimal amountNative) {
    BigDecimal rate = OK.equals(fx.status()) && fx.rate() != null ? fx.rate() : BigDecimal.ONE;
    BigDecimal converted = amountNative.multiply(rate).setScale(SCALE, RoundingMode.HALF_EVEN);
    if (converted.signum() == 0) {
      boolean[] lost = LOST.get();
      if (lost != null && amountNative.setScale(SCALE, RoundingMode.HALF_EVEN).signum() != 0) {
        lost[0] = true;
      }
    }
    return converted;
  }

  /** Decimals of a displayed money amount. */
  private static final int SCALE = 4;

  /** Set while {@link #present} renders: whether a non-zero native amount was shown as zero. */
  private static final ThreadLocal<boolean[]> LOST = new ThreadLocal<>();

  /**
   * Renders one response with {@code conversion}, deciding for the whole response whether the
   * conversion is representable (section 7.7, contract {@code FxMeta}): when any native amount that
   * is non-zero at 4 decimals would be displayed as {@code 0.0000} in the display currency (a rate
   * so small that the converted value is lost), the response is rendered again with {@link
   * #unusable()}: money in the native currency and {@code fx.status = UNAVAILABLE}, so one response
   * never mixes currencies and never shows a positive price as zero. The renderer must be free of
   * side effects (it may run twice); every amount it shows must go through {@link #convert}.
   */
  public static <T> T present(PriceConversion conversion, Function<PriceConversion, T> renderer) {
    if (!OK.equals(conversion.fx().status()) || conversion.fx().rate() == null) {
      return renderer.apply(conversion);
    }
    boolean[] outer = LOST.get();
    boolean[] lost = {false};
    LOST.set(lost);
    T rendered;
    try {
      rendered = renderer.apply(conversion);
    } finally {
      if (outer == null) {
        LOST.remove();
      } else {
        LOST.set(outer);
      }
    }
    if (!lost[0]) {
      return rendered;
    }
    return renderer.apply(conversion.unusable());
  }

  /**
   * The stored rate found but not applied: money stays in the native currency with {@code status =
   * UNAVAILABLE}; {@code rate}, {@code asOf} and {@code source} still identify the stored row that
   * could not be used (unlike a missing row, where they are {@code null}), and {@code stale} is
   * {@code false} because no rate was applied.
   */
  PriceConversion unusable() {
    return new PriceConversion(
        nativeCurrency,
        nativeCurrency,
        new Fx(fx.base(), fx.quote(), fx.rate(), fx.asOf(), fx.source(), false, UNAVAILABLE));
  }
}
