package com.vitadelta.api.catalog.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.catalog.seed.SeedService;
import com.vitadelta.api.identity.AccessTokenService;
import com.vitadelta.api.identity.ContractSchemas;
import com.vitadelta.api.identity.Role;
import com.vitadelta.api.identity.SubscriptionProvisioning;
import com.vitadelta.api.identity.UserAccount;
import com.vitadelta.api.identity.UserRepository;
import com.vitadelta.api.identity.UserStatus;
import com.vitadelta.api.support.PostgresIntegrationTest;
import com.vitadelta.api.support.TestData;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base of the catalog and import API tests: a running server on the shared PostgreSQL container
 * with the seed data loaded, the trusted-proxy rule for the loopback peer (each test picks its
 * client IP through {@code X-Forwarded-For}) and scheduling switched off. Users created here use
 * the e-mail domain {@link #DOMAIN} and are removed after each test.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "vitadelta.proxy.trust-proxy=true",
      "vitadelta.proxy.trusted-proxies=127.0.0.1/32,::1/128",
      "vitadelta.operations.jobs-enabled=false"
    })
public abstract class CatalogApiTest extends PostgresIntegrationTest {

  protected static final JsonMapper JSON = JsonMapper.builder().build();
  protected static final String DOMAIN = "catalog-api.example.org";

  @LocalServerPort protected int port;
  @Autowired protected JdbcClient jdbc;
  @Autowired protected SeedService seedService;
  @Autowired protected UserRepository users;
  @Autowired protected SubscriptionProvisioning provisioning;
  @Autowired protected AccessTokenService accessTokens;
  @Autowired protected PlatformTransactionManager transactionManager;

  protected RestTestClient client;
  protected String ip;

  @BeforeEach
  void setUpCatalogClient() {
    client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    ip = randomIp();
    boolean seeded =
        jdbc.sql(
                "SELECT EXISTS (SELECT 1 FROM product WHERE code = 'GOLD-A')"
                    + " AND EXISTS (SELECT 1 FROM nutrient_form)"
                    + " AND NOT EXISTS (SELECT 1 FROM upper_limit WHERE note = '')")
            .query(Boolean.class)
            .single();
    if (!seeded) {
      seedService.seed(TestData.dataDir());
    }
  }

  @AfterEach
  void removeCatalogApiUsers() {
    jdbc.sql("DELETE FROM app_user WHERE email LIKE ?").param("%@" + DOMAIN).update();
  }

  protected static String randomIp() {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    return "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(1, 255);
  }

  /** A bearer token of a new verified user with {@code role}. */
  protected String token(Role role) {
    UUID id = UUID.randomUUID();
    Instant now = Instant.now();
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> {
          users.insertIfAbsent(
              new UserAccount(
                  id,
                  "c." + id.toString().substring(0, 13) + "@" + DOMAIN,
                  "synthetic-password-hash",
                  now,
                  role,
                  UserStatus.ACTIVE,
                  null,
                  null,
                  "en",
                  true,
                  null,
                  now,
                  now));
          provisioning.provisionFreeSubscription(id, now);
        });
    return accessTokens.issue(id, role, true).value();
  }

  protected EntityExchangeResult<String> get(String path, String token) {
    return client
        .get()
        .uri(java.net.URI.create("http://localhost:" + port + path))
        .headers(h -> {
          h.set("X-Forwarded-For", ip);
          if (token != null) {
            h.setBearerAuth(token);
          }
        })
        .exchange()
        .returnResult(String.class);
  }

  protected static JsonNode json(EntityExchangeResult<String> result) {
    return JSON.readTree(result.getResponseBody());
  }

  protected static void assertStatus(EntityExchangeResult<String> result, int status) {
    assertThat(result.getStatus().value()).as(result.getResponseBody()).isEqualTo(status);
  }

  /** Asserts a problem response with the given status and code (or {@code about:blank}). */
  protected static void assertProblem(
      EntityExchangeResult<String> result, int status, String code) {
    assertStatus(result, status);
    assertThat(result.getResponseHeaders().getContentType())
        .isNotNull()
        .satisfies(t -> assertThat(t.toString()).startsWith("application/problem+json"));
    JsonNode body = json(result);
    assertThat(body.get("type").asString())
        .isEqualTo(code.equals("about:blank") ? code : "https://vitadelta.dev/problems/" + code);
    assertThat(body.get("requestId").asString())
        .isEqualTo(result.getResponseHeaders().getFirst("X-Request-Id"));
    ContractSchemas.assertConforms("Problem", result.getResponseBody());
  }

  /** The id of a product by code. */
  protected UUID productId(String code) {
    return jdbc.sql("SELECT id FROM product WHERE code = ?").param(code).query(UUID.class).single();
  }

  protected long catalogVersion() {
    return jdbc.sql("SELECT version FROM catalog_version WHERE id = 1").query(Long.class).single();
  }
}
