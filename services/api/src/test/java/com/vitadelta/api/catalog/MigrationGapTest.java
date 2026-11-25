package com.vitadelta.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitadelta.api.support.PostgresIntegrationTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The delivered migrations V1 to V6 on a clean database, and the delivery order of ADR-0013: a
 * database that is already at V5 without V3 (V3 was delivered after V4 and V5) applies V3 and V6
 * later with {@code out-of-order} (as configured in {@code application.yml}) and ends in the same
 * schema as a fresh database; without it Flyway rejects the database. An edited applied migration
 * still fails validation.
 */
class MigrationGapTest extends PostgresIntegrationTest {

  private static final String[] MIGRATIONS = {
    "V1__catalog.sql",
    "V2__identity_and_plans.sql",
    "V3__notifications_and_outbox.sql",
    "V4__fx_rates.sql",
    "V5__form_registry_and_ul_notes.sql",
    "V6__alert_ordering.sql"
  };

  @TempDir Path later;

  private static String createDatabase() throws SQLException {
    String name = "gap_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + name);
    }
    return POSTGRES.getJdbcUrl().replace("/vitadelta", "/" + name);
  }

  private static Flyway flyway(String url, boolean outOfOrder, String... locations) {
    return Flyway.configure()
        .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations(locations)
        .validateOnMigrate(true)
        .outOfOrder(outOfOrder)
        .cleanDisabled(true)
        .load();
  }

  private static String migration(String file) throws IOException {
    try (var in = MigrationGapTest.class.getResourceAsStream("/db/migration/" + file)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /** Columns, constraints and indexes of the public schema, in a stable order. */
  private static List<String> schema(String url) throws SQLException {
    List<String> lines = new ArrayList<>();
    try (Connection connection =
            DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement()) {
      for (String sql :
          List.of(
              "SELECT table_name || '.' || column_name || ' ' || data_type || ' '"
                  + " || is_nullable || ' ' || coalesce(column_default, '')"
                  + " FROM information_schema.columns WHERE table_schema = 'public'"
                  + " AND table_name <> 'flyway_schema_history'",
              "SELECT conrelid::regclass || ' ' || conname || ' ' || pg_get_constraintdef(oid)"
                  + " FROM pg_constraint WHERE connamespace = 'public'::regnamespace",
              "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public'"
                  + " AND tablename <> 'flyway_schema_history'")) {
        try (ResultSet rows = statement.executeQuery(sql)) {
          while (rows.next()) {
            lines.add(rows.getString(1));
          }
        }
      }
    }
    lines.sort(String::compareTo);
    return lines;
  }

  @Test
  void aCleanDatabaseGetsEveryMigrationInOrder() throws Exception {
    String url = createDatabase();
    Flyway current = flyway(url, true, "classpath:db/migration");
    current.migrate();
    MigrationInfo[] applied = current.info().applied();
    assertThat(Arrays.stream(applied).map(i -> i.getVersion().getVersion()))
        .containsExactly("1", "2", "3", "4", "5", "6");
    assertThat(applied).allSatisfy(i -> assertThat(i.getState()).isEqualTo(MigrationState.SUCCESS));
    assertThat(current.info().pending()).isEmpty();
  }

  @Test
  void aDatabaseAtV5AppliesTheLaterV3OutOfOrderAndEndsInTheSameSchema() throws Exception {
    Path withoutV3 = later.resolve("without-v3");
    Files.createDirectories(withoutV3);
    for (String file : MIGRATIONS) {
      // V6 builds on V3 and was delivered after it.
      if (!file.startsWith("V3") && !file.startsWith("V6")) {
        Files.writeString(withoutV3.resolve(file), migration(file));
      }
    }
    String existing = createDatabase();
    flyway(existing, true, "filesystem:" + withoutV3.toAbsolutePath()).migrate();

    assertThatThrownBy(() -> flyway(existing, false, "classpath:db/migration").migrate())
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("3");

    Flyway upgraded = flyway(existing, true, "classpath:db/migration");
    upgraded.migrate();
    assertThat(Arrays.stream(upgraded.info().applied()).map(i -> i.getVersion().getVersion()))
        .containsExactly("1", "2", "4", "5", "3", "6");
    upgraded.validate();

    String fresh = createDatabase();
    flyway(fresh, true, "classpath:db/migration").migrate();
    assertThat(schema(existing)).isEqualTo(schema(fresh));
  }

  @Test
  void aChangedAppliedMigrationIsStillRejected() throws IOException, SQLException {
    String url = createDatabase();
    flyway(url, true, "classpath:db/migration").migrate();
    Path copy = later.resolve("changed");
    Files.createDirectories(copy);
    for (String file : MIGRATIONS) {
      String sql = migration(file);
      Files.writeString(copy.resolve(file), file.startsWith("V3") ? sql + "\n-- edited\n" : sql);
    }
    assertThatThrownBy(() -> flyway(url, true, "filesystem:" + copy.toAbsolutePath()).validate())
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("checksum");
  }
}
