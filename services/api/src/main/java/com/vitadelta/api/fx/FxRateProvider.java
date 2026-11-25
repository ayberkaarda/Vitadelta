package com.vitadelta.api.fx;

import java.util.Set;

/**
 * Port to an exchange-rate provider (ADR-0009). The default adapter is {@link
 * FrankfurterFxRateProvider}; replacing the provider means another implementation of this
 * interface, while the table, the refresh job and the conversion stay unchanged.
 */
public interface FxRateProvider {

  /** Value of {@code fx_rate.source} for the rows of this provider. */
  String source();

  /**
   * The latest published rates from {@code baseCurrency} to every currency of {@code
   * quoteCurrencies}.
   *
   * @param baseCurrency ISO 4217 code, upper case
   * @param quoteCurrencies ISO 4217 codes, upper case, not empty, not containing the base
   * @return a quote with exactly the requested currencies
   * @throws FxProviderException when the provider cannot be reached or its answer is not accepted
   */
  FxQuote fetchLatest(String baseCurrency, Set<String> quoteCurrencies);
}
