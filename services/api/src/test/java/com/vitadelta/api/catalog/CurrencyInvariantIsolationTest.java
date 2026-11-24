package com.vitadelta.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitadelta.api.support.PostgresIntegrationTest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * offer.currency equals the country currency at every isolation level. A REPEATABLE READ
 * transaction whose snapshot predates a concurrently committed offer must not be able to change the
 * country currency.
 */
@SpringBootTest
class CurrencyInvariantIsolationTest extends PostgresIntegrationTest {

  private static final String PRODUCT_ID = "00000000-0000-4000-8000-0000000000a1";

  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    cleanUp();
    jdbc.execute("INSERT INTO reference_set VALUES ('ISO_REF', 'Ref', 'c')");
    jdbc.execute("INSERT INTO ul_set VALUES ('ISO_UL', 'UL', 'c')");
    jdbc.execute("INSERT INTO country VALUES ('QR', 'Isoland', 'QRD', 'ISO_REF', 'ISO_UL', 'en')");
    jdbc.execute(
        "INSERT INTO product (id, code, brand, name, dosage_form, units_per_container,"
            + " units_per_serving, servings_per_day, synthetic, created_at, updated_at) VALUES ('"
            + PRODUCT_ID
            + "', 'ISO-P1', 'Fixture Labs', 'Iso', 'TABLET', 30, 1, 1, true, now(), now())");
  }

  @AfterEach
  void cleanUp() {
    jdbc.execute("DELETE FROM offer WHERE country_code = 'QR'");
    jdbc.execute("DELETE FROM product WHERE id = '" + PRODUCT_ID + "'");
    jdbc.execute("DELETE FROM country WHERE code = 'QR'");
    jdbc.execute("DELETE FROM ul_set WHERE code = 'ISO_UL'");
    jdbc.execute("DELETE FROM reference_set WHERE code = 'ISO_REF'");
  }

  @Test
  void repeatableReadSnapshotCannotChangeCurrencyUnderAnExistingOffer() throws SQLException {
    try (Connection snapshot = dataSource.getConnection();
        Connection other = dataSource.getConnection()) {
      snapshot.setAutoCommit(false);
      snapshot.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      try (Statement s = snapshot.createStatement()) {
        s.executeQuery("SELECT count(*) FROM offer").close();
      }
      other.setAutoCommit(true);
      try (Statement s = other.createStatement()) {
        s.executeUpdate(
            "INSERT INTO offer VALUES (gen_random_uuid(), '"
                + PRODUCT_ID
                + "', 'QR', 'Shop', 10.00, 'QRD', true, 'https://a.example/x', now())");
      }
      try {
        assertThatThrownBy(
                () -> {
                  try (Statement s = snapshot.createStatement()) {
                    s.executeUpdate("UPDATE country SET currency = 'QSD' WHERE code = 'QR'");
                  }
                  snapshot.commit();
                })
            .isInstanceOf(SQLException.class);
      } finally {
        snapshot.rollback();
      }
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM offer o JOIN country c ON c.code = o.country_code"
                    + " WHERE o.currency <> c.currency",
                Integer.class))
        .isZero();
  }

  @Test
  void compositeForeignKeyBindsOfferCurrencyToItsCountry() {
    assertThat(
            jdbc.queryForList(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                    + " WHERE conrelid = 'offer'::regclass AND contype = 'f'",
                String.class))
        .anySatisfy(d ->
            assertThat(d)
                .contains("(country_code, currency)")
                .contains("REFERENCES country(code, currency)"));
  }
}
