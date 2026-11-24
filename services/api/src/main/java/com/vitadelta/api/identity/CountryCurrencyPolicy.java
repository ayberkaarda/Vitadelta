package com.vitadelta.api.identity;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Default {@link DisplayCurrencyPolicy}: the native currencies of the country registry. */
public class CountryCurrencyPolicy implements DisplayCurrencyPolicy {

  private final JdbcClient jdbc;

  public CountryCurrencyPolicy(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean isSupported(String currency) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM country WHERE currency = ?)")
        .param(currency)
        .query(Boolean.class)
        .single();
  }
}
