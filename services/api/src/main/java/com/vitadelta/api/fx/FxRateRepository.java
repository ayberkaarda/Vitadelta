package com.vitadelta.api.fx;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Rows of {@code fx_rate} (migration V4). */
@Repository
public class FxRateRepository {

  private final JdbcClient jdbc;

  public FxRateRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** One stored rate: 1 {@code base} = {@code rate} {@code quote} on {@code asOf}. */
  public record StoredRate(
      String base,
      String quote,
      LocalDate asOf,
      BigDecimal rate,
      Instant fetchedAt,
      String source) {}

  /**
   * Inserts the rates of {@code quote} or, for an existing {@code (base, quote, as_of)}, replaces
   * rate, fetch time and source.
   *
   * @return the number of rows inserted or updated
   */
  public int upsert(FxQuote quote, Instant fetchedAt, String source) {
    int rows = 0;
    for (Map.Entry<String, BigDecimal> rate : quote.rates().entrySet()) {
      rows +=
          jdbc.sql(
                  "INSERT INTO fx_rate (base_currency, quote_currency, as_of, rate, fetched_at,"
                      + " source) VALUES (?, ?, ?, ?, ?, ?)"
                      + " ON CONFLICT (base_currency, quote_currency, as_of) DO UPDATE"
                      + " SET rate = EXCLUDED.rate, fetched_at = EXCLUDED.fetched_at,"
                      + " source = EXCLUDED.source")
              .params(
                  quote.base(),
                  rate.getKey(),
                  quote.asOf(),
                  rate.getValue(),
                  Timestamp.from(fetchedAt),
                  source)
              .update();
    }
    return rows;
  }

  /** The row with the newest {@code as_of} for the pair. */
  public Optional<StoredRate> newest(String base, String quote) {
    return jdbc.sql(
            "SELECT base_currency, quote_currency, as_of, rate, fetched_at, source FROM fx_rate"
                + " WHERE base_currency = ? AND quote_currency = ?"
                + " ORDER BY as_of DESC LIMIT 1")
        .params(base, quote)
        .query(FxRateRepository::row)
        .optional();
  }

  /** The newest {@code as_of} of the whole table. */
  public Optional<LocalDate> newestAsOf() {
    return jdbc.sql("SELECT max(as_of) FROM fx_rate")
        .query((rs, n) -> Optional.ofNullable(rs.getObject(1, LocalDate.class)))
        .single();
  }

  /** Per currency: the newest {@code as_of} of a row that has it as base or quote. */
  public Map<String, LocalDate> newestAsOfPerCurrency() {
    Map<String, LocalDate> newest = new TreeMap<>();
    jdbc.sql(
            "SELECT currency, max(as_of) FROM ("
                + " SELECT base_currency AS currency, as_of FROM fx_rate"
                + " UNION ALL SELECT quote_currency, as_of FROM fx_rate) rows"
                + " GROUP BY currency")
        .query(rs -> {
          newest.put(rs.getString(1), rs.getObject(2, LocalDate.class));
        });
    return newest;
  }

  private static StoredRate row(ResultSet rs, int n) throws SQLException {
    return new StoredRate(
        rs.getString("base_currency"),
        rs.getString("quote_currency"),
        rs.getObject("as_of", LocalDate.class),
        rs.getBigDecimal("rate"),
        rs.getTimestamp("fetched_at").toInstant(),
        rs.getString("source"));
  }
}
