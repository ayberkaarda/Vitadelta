package com.vitadelta.api.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitadelta.api.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Migration V4 on a clean database (all migrations from V1, in numeric order, on a fresh schema of
 * the shared container) and the constraints of {@code fx_rate}.
 */
class FxRateSchemaTest extends PostgresIntegrationTest {

  private static final String DATABASE = "fx_schema_test";

  private static SingleConnectionDataSource dataSource;
  private static JdbcClient jdbc;

  @BeforeAll
  static void migrateCleanDatabase() throws SQLException {
    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = admin.createStatement()) {
      statement.execute("DROP DATABASE IF EXISTS " + DATABASE);
      statement.execute("CREATE DATABASE " + DATABASE);
    }
    String url = POSTGRES.getJdbcUrl().replace("/vitadelta", "/" + DATABASE);
    dataSource =
        new SingleConnectionDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword(), true);
    var result =
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .outOfOrder(true)
            .load()
            .migrate();
    assertThat(result.success).isTrue();
    jdbc = JdbcClient.create(dataSource);
  }

  @AfterAll
  static void dropDatabase() throws SQLException {
    dataSource.destroy();
    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = admin.createStatement()) {
      statement.execute("DROP DATABASE IF EXISTS " + DATABASE);
    }
  }

  private static int insert(String base, String quote, String asOf, String rate, String source) {
    return jdbc.sql(
            "INSERT INTO fx_rate (base_currency, quote_currency, as_of, rate, fetched_at, source)"
                + " VALUES (?, ?, CAST(? AS date), CAST(? AS numeric), now(), ?)")
        .params(base, quote, asOf, rate, source)
        .update();
  }

  @Test
  void v4IsAppliedAndCreatesTheTableAndTheNewestIndex() {
    List<String> versions =
        jdbc.sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
            .query(String.class)
            .list();
    assertThat(versions).contains("1", "2", "4", "5");
    String index =
        jdbc.sql(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'fx_rate'"
                    + " AND indexname = 'fx_rate_pair_newest_idx'")
            .query(String.class)
            .single();
    assertThat(index).contains("(base_currency, quote_currency, as_of DESC)");
    String type =
        jdbc.sql(
                "SELECT format_type(atttypid, atttypmod) FROM pg_attribute"
                    + " WHERE attrelid = 'fx_rate'::regclass AND attname = 'rate'")
            .query(String.class)
            .single();
    assertThat(type).isEqualTo("numeric(18,8)");
  }

  @Test
  void theRateIsStoredWithEightDecimals() {
    insert("TRY", "USD", "2026-11-23", "0.024137", "FRANKFURTER");
    BigDecimal stored =
        jdbc.sql("SELECT rate FROM fx_rate WHERE base_currency = 'TRY' AND quote_currency = 'USD'")
            .query(BigDecimal.class)
            .single();
    assertThat(stored.toPlainString()).isEqualTo("0.02413700");
    jdbc.sql("DELETE FROM fx_rate").update();
  }

  @Test
  void thePrimaryKeyRejectsASecondRowForThePairAndDate() {
    insert("TRY", "EUR", "2026-11-23", "0.01807", "FRANKFURTER");
    try {
      assertThat(insert("TRY", "EUR", "2026-11-20", "0.01807", "FRANKFURTER")).isEqualTo(1);
      assertThatThrownBy(() -> insert("TRY", "EUR", "2026-11-23", "0.01900", "FRANKFURTER"))
          .isInstanceOf(DataIntegrityViolationException.class)
          .hasMessageContaining("fx_rate_pk");
    } finally {
      jdbc.sql("DELETE FROM fx_rate").update();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-0.5", "NaN"})
  void nonPositiveAndNanRatesAreRejected(String rate) {
    assertThatThrownBy(() -> insert("TRY", "GBP", "2026-11-23", rate, "FRANKFURTER"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("fx_rate_rate_positive");
  }

  @Test
  void ratesOutsideTheColumnRangeAreRejected() {
    assertThatThrownBy(() -> insert("TRY", "GBP", "2026-11-23", "10000000000", "FRANKFURTER"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insert("TRY", "GBP", "2026-11-23", "Infinity", "FRANKFURTER"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void codesPairAndSourceAreChecked() {
    assertThatThrownBy(() -> insert("try", "GBP", "2026-11-23", "1", "FRANKFURTER"))
        .hasMessageContaining("fx_rate_base_format");
    assertThatThrownBy(() -> insert("TRY", "GB", "2026-11-23", "1", "FRANKFURTER"))
        .hasMessageContaining("fx_rate_quote_format");
    assertThatThrownBy(() -> insert("TRY", "TRY", "2026-11-23", "1", "FRANKFURTER"))
        .hasMessageContaining("fx_rate_distinct_pair");
    assertThatThrownBy(() -> insert("TRY", "GBP", "2026-11-23", "1", " "))
        .hasMessageContaining("fx_rate_source_format");
    assertThatThrownBy(() ->
            jdbc.sql(
                    "INSERT INTO fx_rate (base_currency, quote_currency, as_of, rate,"
                        + " fetched_at, source) VALUES ('TRY', 'GBP', NULL, 1, now(),"
                        + " 'FRANKFURTER')")
                .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
