package com.vitadelta.api.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class of tests that need the database: one PostgreSQL container (the image pinned in {@code
 * infra/docker-compose.yml}) is started once per test JVM and supplied to the application through
 * the same {@code VITADELTA_DB_*} variables the application reads in production.
 */
public abstract class PostgresIntegrationTest {

  /** Must match the postgres image of {@code infra/docker-compose.yml}. */
  public static final String POSTGRES_IMAGE = "postgres:18.7-alpine";

  protected static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE)).withDatabaseName("vitadelta");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("VITADELTA_DB_URL", POSTGRES::getJdbcUrl);
    registry.add("VITADELTA_DB_USERNAME", POSTGRES::getUsername);
    registry.add("VITADELTA_DB_PASSWORD", POSTGRES::getPassword);
  }
}
