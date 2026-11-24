package com.vitadelta.api.entitlement;

/**
 * Seam to the FX module (section 7.7): how a plan price in its native currency is shown in a
 * display currency on {@code GET /plans}. The entitlement package registers {@link
 * NativePriceConverter} (identity pair only; any other currency is answered in the native currency
 * with {@code fx.status = UNAVAILABLE}) unless another bean of this type exists; the FX module
 * replaces it with a converter over the stored rates.
 */
public interface DisplayPriceConverter {

  /**
   * The conversion from {@code nativeCurrency} to {@code displayCurrency}. The display currency is
   * already validated as supported.
   */
  PriceConversion conversion(String nativeCurrency, String displayCurrency);
}
