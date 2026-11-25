package com.vitadelta.api.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.VitadeltaApiApplication;
import com.vitadelta.api.catalog.CatalogWriteTransaction;
import com.vitadelta.api.catalog.CatalogWriter;
import com.vitadelta.api.catalog.WriteReport;
import com.vitadelta.api.catalog.seed.ReferenceData;
import com.vitadelta.api.catalog.seed.SeedDataReader;
import com.vitadelta.api.identity.AccessTokenService;
import com.vitadelta.api.identity.ContractSchemas;
import com.vitadelta.api.identity.Role;
import com.vitadelta.api.identity.SubscriptionProvisioning;
import com.vitadelta.api.identity.UserAccount;
import com.vitadelta.api.identity.UserRepository;
import com.vitadelta.api.identity.UserStatus;
import com.vitadelta.api.support.PostgresIntegrationTest;
import com.vitadelta.api.support.TestData;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * On a new database (the delivered migrations only, no seed), the reference data is loaded and then
 * the committed products and offers files are imported through the admin endpoints: every import
 * succeeds and bumps the catalog version exactly once; importing the same files again changes no
 * row and does not bump.
 */
class FreshDatabaseImportTest extends PostgresIntegrationTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void theDataFilesImportThroughTheEndpointsOnAFreshDatabase() throws Exception {
    String name = "fresh_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + name);
    }
    String url = POSTGRES.getJdbcUrl().replace("/vitadelta", "/" + name);
    // Command-line arguments outrank application.yml (default properties would not: server.port
    // 8080 and management.server.port 8081 from application.yml would win and collide with a
    // running stack).
    String[] arguments =
        Stream.concat(
                Arrays.stream(applicationProperties())
                    .filter(p -> !p.startsWith("VITADELTA_DB_URL=")),
                Stream.of(
                    "VITADELTA_DB_URL=" + url, "server.port=0", "VITADELTA_JOBS_ENABLED=false"))
            .map(p -> "--" + p)
            .toArray(String[]::new);
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(VitadeltaApiApplication.class).run(arguments)) {
      int serverPort = ((WebServerApplicationContext) context).getWebServer().getPort();
      assertThat(serverPort).as("random server port").isNotEqualTo(8080);
      assertThat(context.getEnvironment().getProperty("local.management.port", Integer.class))
          .as("random management port")
          .isNotNull()
          .isNotEqualTo(8081);
      JdbcClient jdbc = context.getBean(JdbcClient.class);
      assertThat(jdbc.sql("SELECT count(*) FROM product").query(Integer.class).single()).isZero();
      // Reference data only, written the way the seed writes it; products come from the imports.
      ReferenceData reference = SeedDataReader.readReference(TestData.dataDir());
      CatalogWriter writer = context.getBean(CatalogWriter.class);
      context
          .getBean(CatalogWriteTransaction.class)
          .execute(() -> {
            WriteReport report = new WriteReport();
            writer.upsertNutrients(reference.nutrients(), report);
            writer.upsertForms(reference.formRows(), report);
            writer.upsertReferenceSets(reference.referenceSets(), report);
            writer.upsertUlSets(reference.ulSets(), report);
            writer.replaceUnitConversions(reference.conversions(), report);
            writer.replaceReferenceValues(reference.referenceValues(), report);
            writer.replaceUpperLimits(reference.upperLimits(), report);
            writer.upsertCountries(reference.countries(), report);
            return report;
          });
      long version = version(jdbc);
      String token = adminToken(context);
      int port = ((WebServerApplicationContext) context).getWebServer().getPort();
      RestTestClient client =
          RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();

      for (String file : SeedDataReader.PRODUCT_FILES) {
        JsonNode job = await(client, token, postProducts(client, token, read(file)));
        assertThat(job.get("status").asString()).as(file).isEqualTo("SUCCEEDED");
        assertThat(version(jdbc)).as(file).isEqualTo(++version);
      }
      for (String file : SeedDataReader.OFFER_FILES) {
        JsonNode job = await(client, token, postOffers(client, token, read(file)));
        assertThat(job.get("status").asString()).as(file).isEqualTo("SUCCEEDED");
        assertThat(job.get("summary").get("inserted").asLong()).isPositive();
        assertThat(version(jdbc)).as(file).isEqualTo(++version);
      }
      long afterFirst = version;
      String offersBefore =
          jdbc.sql("SELECT string_agg(id::text, ',' ORDER BY id) FROM offer")
              .query(String.class)
              .single();
      for (String file : SeedDataReader.PRODUCT_FILES) {
        await(client, token, postProducts(client, token, read(file)));
      }
      for (String file : SeedDataReader.OFFER_FILES) {
        JsonNode job = await(client, token, postOffers(client, token, read(file)));
        assertThat(job.get("summary").get("changedPairs").asLong()).isZero();
      }
      assertThat(version(jdbc)).isEqualTo(afterFirst);
      assertThat(
              jdbc.sql("SELECT string_agg(id::text, ',' ORDER BY id) FROM offer")
                  .query(String.class)
                  .single())
          .isEqualTo(offersBefore);
      assertThat(
              jdbc.sql("SELECT count(*) FROM product WHERE code = 'GOLD-A'")
                  .query(Integer.class)
                  .single())
          .isOne();
    }
  }

  private static byte[] read(String file) throws java.io.IOException {
    return Files.readAllBytes(TestData.dataDir().resolve(file));
  }

  private static long version(JdbcClient jdbc) {
    return jdbc.sql("SELECT version FROM catalog_version WHERE id = 1").query(Long.class).single();
  }

  private static String adminToken(ConfigurableApplicationContext context) {
    UUID id = UUID.randomUUID();
    Instant now = Instant.now();
    new TransactionTemplate(context.getBean(PlatformTransactionManager.class))
        .executeWithoutResult(status -> {
          context
              .getBean(UserRepository.class)
              .insertIfAbsent(
                  new UserAccount(
                      id,
                      "fresh-admin@fresh.example.org",
                      "synthetic-password-hash",
                      now,
                      Role.ADMIN,
                      UserStatus.ACTIVE,
                      null,
                      null,
                      "en",
                      true,
                      null,
                      now,
                      now));
          context.getBean(SubscriptionProvisioning.class).provisionFreeSubscription(id, now);
        });
    return context.getBean(AccessTokenService.class).issue(id, Role.ADMIN, true).value();
  }

  private static EntityExchangeResult<String> postProducts(
      RestTestClient client, String token, byte[] body) {
    return client
        .post()
        .uri("/api/v1/admin/imports/products")
        .headers(h -> h.setBearerAuth(token))
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .exchange()
        .returnResult(String.class);
  }

  private static EntityExchangeResult<String> postOffers(
      RestTestClient client, String token, byte[] csv) {
    org.springframework.util.LinkedMultiValueMap<String, Object> parts =
        new org.springframework.util.LinkedMultiValueMap<>();
    HttpHeaders partHeaders = new HttpHeaders();
    partHeaders.setContentType(MediaType.parseMediaType("text/csv"));
    parts.add(
        "file",
        new org.springframework.http.HttpEntity<>(
            new ByteArrayResource(csv) {
              @Override
              public String getFilename() {
                return "offers.csv";
              }
            },
            partHeaders));
    return client
        .post()
        .uri("/api/v1/admin/imports/offers")
        .headers(h -> h.setBearerAuth(token))
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(parts)
        .exchange()
        .returnResult(String.class);
  }

  private static JsonNode await(
      RestTestClient client, String token, EntityExchangeResult<String> accepted)
      throws InterruptedException {
    assertThat(accepted.getStatus().value()).as(accepted.getResponseBody()).isEqualTo(202);
    String location = accepted.getResponseHeaders().getFirst(HttpHeaders.LOCATION);
    Instant deadline = Instant.now().plus(Duration.ofSeconds(120));
    while (true) {
      EntityExchangeResult<String> job =
          client
              .get()
              .uri(location)
              .headers(h -> h.setBearerAuth(token))
              .exchange()
              .returnResult(String.class);
      ContractSchemas.assertConforms("ImportJobDetail", job.getResponseBody());
      JsonNode body = JSON.readTree(job.getResponseBody());
      if (!body.get("status").asString().equals("RUNNING")) {
        assertThat(body.get("errors").isEmpty()).as(job.getResponseBody()).isTrue();
        return body;
      }
      assertThat(Instant.now()).isBefore(deadline);
      Thread.sleep(50);
    }
  }
}
