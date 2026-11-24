package com.vitadelta.api.common.numeric;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The single rounding step of the API response assembly (project specification section 6.12):
 * values are converted with {@link BigDecimal#BigDecimal(double)} (the double's exact binary value,
 * not its shortest decimal string) and rounded half-even. Money, scores and ratios are rounded to 4
 * decimals. FX rates are not rounded here.
 */
public final class Rounding {

  /** Decimals of money, score and ratio values in responses. */
  public static final int SCALE = 4;

  private Rounding() {}

  /** A money amount rounded to 4 decimals. */
  public static BigDecimal money(double value) {
    return round(value, SCALE);
  }

  /** A score rounded to 4 decimals. */
  public static BigDecimal score(double value) {
    return round(value, SCALE);
  }

  /** A ratio rounded to 4 decimals. */
  public static BigDecimal ratio(double value) {
    return round(value, SCALE);
  }

  /**
   * Rounds the exact value of {@code value} half-even to {@code scale} decimals.
   *
   * @throws IllegalArgumentException for NaN and infinities
   */
  public static BigDecimal round(double value, int scale) {
    if (!Double.isFinite(value)) {
      throw new IllegalArgumentException("cannot round a non-finite value: " + value);
    }
    BigDecimal rounded = new BigDecimal(value).setScale(scale, RoundingMode.HALF_EVEN);
    return rounded.signum() == 0 ? BigDecimal.ZERO.setScale(scale) : rounded;
  }
}
