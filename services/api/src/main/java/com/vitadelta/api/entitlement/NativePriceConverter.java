package com.vitadelta.api.entitlement;

/**
 * Default {@link DisplayPriceConverter} until the FX module exists: the identity pair converts with
 * rate 1, every other display currency is answered in the native currency with {@code fx.status =
 * UNAVAILABLE} (contract schema {@code FxMeta}).
 */
public class NativePriceConverter implements DisplayPriceConverter {

  @Override
  public PriceConversion conversion(String nativeCurrency, String displayCurrency) {
    return nativeCurrency.equals(displayCurrency)
        ? PriceConversion.identity(nativeCurrency)
        : PriceConversion.unavailable(nativeCurrency, displayCurrency);
  }
}
