package com.vitadelta.api.fx;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Latest rates of one base currency as published by a provider (port {@link FxRateProvider}).
 *
 * @param base the requested base currency
 * @param asOf the provider's rate date shared by every rate (never the fetch time)
 * @param rates quote currency to rate (1 base = rate quote), every rate positive; sorted by quote
 */
public record FxQuote(String base, LocalDate asOf, Map<String, BigDecimal> rates) {

  public FxQuote {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(asOf, "asOf");
    Objects.requireNonNull(rates, "rates");
    TreeMap<String, BigDecimal> copy = new TreeMap<>();
    rates.forEach((quote, rate) -> {
      if (quote == null || rate == null || rate.signum() <= 0) {
        throw new IllegalArgumentException("rates must map currencies to positive rates");
      }
      copy.put(quote, rate);
    });
    rates = Collections.unmodifiableMap(copy);
  }
}
