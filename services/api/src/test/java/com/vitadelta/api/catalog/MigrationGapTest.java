package com.vitadelta.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitadelta.api.support.PostgresIntegrationTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Migrations V1, V2 and V5 on a clean database, and the reserved gap: V3 and V4 arrive in Phase 5
 * after V5 has been applied. With {@code out-of-order} (as configured in {@code application.yml}) a
 * later V3 is applied and validation still passes; without it Flyway rejects the database.
 */
class MigrationGapTest extends PostgresIntegrationTest {

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

  @Test
  void aCleanDatabaseGetsV1V2AndV5AndAcceptsTheReservedVersionsLater() throws Exception {
    String url = createDatabase();
    Flyway current = flyway(url, true, "classpath:db/migration");
    current.migrate();
    MigrationInfo[] applied = current.info().applied();
    assertThat(Arrays.stream(applied).map(i -> i.getVersion().getVersion()))
        .containsExactly("1", "2", "5");
    assertThat(applied).allSatisfy(i -> assertThat(i.getState()).isEqualTo(MigrationState.SUCCESS));
    assertThat(current.info().pending()).isEmpty();

    Files.writeString(later.resolve("V3__later_migration.sql"), "CREATE TABLE gap_probe (id int);");
    String laterLocation = "filesystem:" + later.toAbsolutePath();

    assertThatThrownBy(() -> flyway(url, false, "classpath:db/migration", laterLocation).migrate())
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("3");

    Flyway withLater = flyway(url, true, "classpath:db/migration", laterLocation);
    withLater.migrate();
    assertThat(Arrays.stream(withLater.info().applied()).map(i -> i.getVersion().getVersion()))
        .contains("1", "2", "3", "5");
    withLater.validate();
  }

  @Test
  void aChangedAppliedMigrationIsStillRejected() throws IOException, SQLException {
    String url = createDatabase();
    flyway(url, true, "classpath:db/migration").migrate();
    Path copy = later.resolve("changed");
    Files.createDirectories(copy);
    for (String file :
        new String[] {
          "V1__catalog.sql", "V2__identity_and_plans.sql", "V5__form_registry_and_ul_notes.sql"
        }) {
      try (var in = getClass().getResourceAsStream("/db/migration/" + file)) {
        String sql = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        Files.writeString(copy.resolve(file), file.startsWith("V5") ? sql + "\n-- edited\n" : sql);
      }
    }
    assertThatThrownBy(() -> flyway(url, true, "filesystem:" + copy.toAbsolutePath()).validate())
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("checksum");
  }
}
