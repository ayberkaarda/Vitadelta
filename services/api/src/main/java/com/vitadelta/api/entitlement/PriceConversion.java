package com.vitadelta.api.entitlement;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;

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
    BigDecimal rate = fx.rate() == null ? BigDecimal.ONE : fx.rate();
    return amountNative.multiply(rate).setScale(4, RoundingMode.HALF_EVEN);
  }
}
