package com.vitadelta.api.support;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class of tests that need the database: one PostgreSQL container (the image pinned in {@code
 * infra/docker-compose.yml}) is started once per test JVM and supplied to the application through
 * the same {@code VITADELTA_DB_*} variables the application reads in production. The other required
 * variables come from {@link TestEnvironment} (secrets generated per test JVM).
 */
public abstract class PostgresIntegrationTest {

  /** Must match the postgres image of {@code infra/docker-compose.yml}. */
  public static final String POSTGRES_IMAGE = "postgres:18.7-alpine";

  protected static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
          .withDatabaseName("vitadelta")
          // Every cached test context keeps its own connection pool.
          .withCommand("postgres", "-c", "max_connections=400");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("VITADELTA_DB_URL", POSTGRES::getJdbcUrl);
    registry.add("VITADELTA_DB_USERNAME", POSTGRES::getUsername);
    registry.add("VITADELTA_DB_PASSWORD", POSTGRES::getPassword);
    // Cached contexts stay alive for the whole test JVM and share this one database: a scheduled
    // run of an idle context must not claim, lease or delete rows another test is working with.
    // Tests call the jobs directly. Applications built with SpringApplicationBuilder are not
    // affected and keep the production default.
    registry.add("vitadelta.operations.jobs-enabled", () -> "false");
    for (Map.Entry<String, String> entry : TestEnvironment.required().entrySet()) {
      registry.add(entry.getKey(), entry::getValue);
    }
  }

  /** The database and required variables as {@code key=value} pairs (for application builders). */
  protected static String[] applicationProperties() {
    Map<String, String> all = new LinkedHashMap<>(TestEnvironment.required());
    all.put("VITADELTA_DB_URL", POSTGRES.getJdbcUrl());
    all.put("VITADELTA_DB_USERNAME", POSTGRES.getUsername());
    all.put("VITADELTA_DB_PASSWORD", POSTGRES.getPassword());
    return all.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toArray(String[]::new);
  }
}
