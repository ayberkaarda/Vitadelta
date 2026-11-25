package com.vitadelta.api.fx;

import com.vitadelta.api.identity.DisplayCurrencyPolicy;
import java.util.Collections;
import java.util.SortedSet;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The supported display currencies (section 7.1): the currencies of the country registry plus
 * {@code VITADELTA_FX_EXTRA_CURRENCIES}. They are also the bases and quotes of the refresh job.
 *
 * <p>As a {@link DisplayCurrencyPolicy} bean this replaces the identity package's default (country
 * currencies only), so {@code PATCH /me}, {@code GET /plans} and every catalog and recommendation
 * response accept the extra currencies too. The country registry is read on every call (a handful
 * of rows), so a country added by a seed run is supported at once.
 */
@Component
public class SupportedCurrencies implements DisplayCurrencyPolicy {

  private final JdbcClient jdbc;
  private final FxProperties properties;

  public SupportedCurrencies(JdbcClient jdbc, FxProperties properties) {
    this.jdbc = jdbc;
    this.properties = properties;
  }

  @Override
  public boolean isSupported(String currency) {
    if (currency == null) {
      return false;
    }
    if (properties.extraCurrencies().contains(currency)) {
      return true;
    }
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM country WHERE currency = ?)")
        .param(currency)
        .query(Boolean.class)
        .single();
  }

  /** Every supported currency, sorted. */
  public SortedSet<String> all() {
    TreeSet<String> codes =
        new TreeSet<>(jdbc.sql("SELECT DISTINCT currency FROM country").query(String.class).list());
    codes.addAll(properties.extraCurrencies());
    return Collections.unmodifiableSortedSet(codes);
  }
}
