package com.vitadelta.api.catalog.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.VitadeltaApiApplication;
import com.vitadelta.api.support.PostgresIntegrationTest;
import com.vitadelta.api.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** The application started with the {@code seed} profile, as {@code scripts/seed.sh} runs it. */
class SeedProfileTest extends PostgresIntegrationTest {

  @Test
  void seedProfileStartsWithoutWebServerSeedsAndExitsWithZero() {
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(VitadeltaApiApplication.class)
            .profiles(VitadeltaApiApplication.SEED_PROFILE)
            .properties(applicationProperties())
            .run("--vitadelta.seed.data-dir=" + TestData.dataDir())) {
      assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
      JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
      assertThat(jdbc.queryForObject("SELECT version FROM catalog_version", Long.class))
          .isPositive();
      assertThat(jdbc.queryForObject("SELECT count(*) FROM product", Integer.class)).isPositive();
      assertThat(SpringApplication.exit(context)).isZero();
    }
  }
}
