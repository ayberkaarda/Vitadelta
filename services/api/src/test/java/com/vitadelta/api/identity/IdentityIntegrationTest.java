package com.vitadelta.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.mail.EmailSender;
import com.vitadelta.api.support.PostgresIntegrationTest;
import com.vitadelta.api.support.TestData;
import com.vitadelta.api.support.TestEnvironment;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base of the identity integration tests: a running server on the shared PostgreSQL container, a
 * movable clock, a capturing e-mail sender and the trusted-proxy rule enabled for the loopback
 * peer, so that every test chooses its own client IP through {@code X-Forwarded-For} (separate
 * per-IP rate-limit buckets).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "vitadelta.proxy.trust-proxy=true",
      "vitadelta.proxy.trusted-proxies=127.0.0.1/32,::1/128"
    })
@Import(IdentityIntegrationTest.IdentityTestConfig.class)
abstract class IdentityIntegrationTest extends PostgresIntegrationTest {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String PASSWORD = "correct-horse-battery-staple";

  @TestConfiguration(proxyBeanMethods = false)
  static class IdentityTestConfig {

    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock(Instant.now());
    }

    @Bean
    @Primary
    EmailSender capturingEmailSender() {
      return new CapturingEmailSender();
    }

    @Bean
    DeletionEvents deletionEvents() {
      return new DeletionEvents();
    }
  }

  /** Records {@link UserDeletingEvent}s. */
  static class DeletionEvents {
    final List<UUID> deleted = new CopyOnWriteArrayList<>();

    @EventListener
    void on(UserDeletingEvent event) {
      deleted.add(event.userId());
    }
  }

  @DynamicPropertySource
  static void identityProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "vitadelta.identity.weak-passwords-file",
        () -> TestData.dataDir().resolve(PasswordPolicy.LIST_PATH).toString());
  }

  @LocalServerPort int port;
  @Autowired MutableClock clock;
  @Autowired EmailSender emailSender;
  @Autowired JdbcClient jdbc;
  @Autowired DeletionEvents deletionEvents;

  RestTestClient client;

  /** The client IP of this test (sent as {@code X-Forwarded-For}). */
  String ip;

  @BeforeEach
  void setUpClient() {
    client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    ip = randomIp();
    mail().failing(false);
  }

  CapturingEmailSender mail() {
    return (CapturingEmailSender) emailSender;
  }

  static String randomIp() {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    return "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(1, 255);
  }

  static String uniqueEmail() {
    return "user." + UUID.randomUUID().toString().substring(0, 12) + "@example.org";
  }

  /** A JSON POST with the test's client IP and the given extra headers (name, value pairs). */
  EntityExchangeResult<String> post(String path, String body, String... headers) {
    RestTestClient.RequestBodySpec spec =
        client
            .post()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Forwarded-For", ip);
    for (int i = 0; i < headers.length; i += 2) {
      if (headers[i].equals(HttpHeaders.COOKIE)) {
        String[] cookie = headers[i + 1].split("=", 2);
        spec = spec.cookie(cookie[0], cookie[1]);
      } else {
        spec = spec.header(headers[i], headers[i + 1]);
      }
    }
    return spec.body(body).exchange().returnResult(String.class);
  }

  EntityExchangeResult<String> get(String path, String bearer) {
    RestTestClient.RequestHeadersSpec<?> spec =
        client.get().uri(path).header("X-Forwarded-For", ip);
    if (bearer != null) {
      spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
    }
    return spec.exchange().returnResult(String.class);
  }

  EntityExchangeResult<String> refresh(String refreshToken) {
    return post(
        "/api/v1/auth/refresh",
        "{}",
        "X-Requested-With",
        "vitadelta",
        HttpHeaders.COOKIE,
        AuthCookies.REFRESH + "=" + refreshToken);
  }

  static JsonNode json(EntityExchangeResult<String> result) {
    return JSON.readTree(result.getResponseBody());
  }

  static String body(Map<String, ?> fields) {
    return JSON.writeValueAsString(fields);
  }

  /** The value of cookie {@code name} set by the response, if any. */
  static Optional<String> setCookie(EntityExchangeResult<String> result, String name) {
    return result.getResponseHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).stream()
        .filter(header -> header.startsWith(name + "="))
        .findFirst();
  }

  static String cookieValue(String setCookie) {
    String pair = setCookie.split(";", 2)[0];
    return pair.substring(pair.indexOf('=') + 1);
  }

  static void assertProblem(EntityExchangeResult<String> result, int status, String code) {
    assertThat(result.getStatus().value()).as(result.getResponseBody()).isEqualTo(status);
    assertThat(String.valueOf(result.getResponseHeaders().getContentType()))
        .startsWith("application/problem+json");
    assertThat(json(result).get("type").asString())
        .isEqualTo(code.equals("about:blank") ? code : "https://vitadelta.dev/problems/" + code);
    ContractSchemas.assertConforms("Problem", result.getResponseBody());
  }

  EntityExchangeResult<String> register(String email, String password) {
    EntityExchangeResult<String> result =
        post("/api/v1/auth/register", body(Map.of("email", email, "password", password)));
    assertThat(result.getStatus().value()).as(result.getResponseBody()).isEqualTo(202);
    return result;
  }

  /** Registers, verifies through the e-mailed token and returns the user id. */
  UUID registerVerified(String email) {
    int before = mail().to(email).size();
    register(email, PASSWORD);
    String token = mail().awaitToken(email, before + 1);
    EntityExchangeResult<String> verified =
        post("/api/v1/auth/verify-email", body(Map.of("token", token)));
    assertThat(verified.getStatus().value()).as(verified.getResponseBody()).isEqualTo(204);
    return userId(email);
  }

  UUID userId(String email) {
    return jdbc.sql("SELECT id FROM app_user WHERE email = CAST(? AS citext)")
        .param(email)
        .query(UUID.class)
        .single();
  }

  /** A logged-in session. */
  record Login(String accessToken, String refreshToken, JsonNode body) {}

  Login login(String email, String password) {
    EntityExchangeResult<String> result =
        post("/api/v1/auth/login", body(Map.of("email", email, "password", password)));
    assertThat(result.getStatus().value()).as(result.getResponseBody()).isEqualTo(200);
    ContractSchemas.assertConforms("AuthTokenResponse", result.getResponseBody());
    String refresh = cookieValue(setCookie(result, AuthCookies.REFRESH).orElseThrow());
    return new Login(json(result).get("accessToken").asString(), refresh, json(result));
  }

  /** Inserts an ADMIN directly and returns its access token. */
  String adminToken() {
    String email = "admin." + UUID.randomUUID().toString().substring(0, 12) + "@example.org";
    UUID id = registerVerified(email);
    jdbc.sql("UPDATE app_user SET role = 'ADMIN' WHERE id = ?").param(id).update();
    return login(email, PASSWORD).accessToken();
  }

  static String testOrigin() {
    return TestEnvironment.WEB_ORIGIN;
  }

  // Synthetic catalog rows of the identity tests; removed after every test so that the catalog
  // tests sharing the database see no extra rows.

  static final String TEST_COUNTRY = "XJ";
  static final String TEST_CURRENCY = "XJD";
  static final String TEST_PRODUCT_PREFIX = "IDT-";

  /** Creates the synthetic test country (idempotent) and returns its code. */
  String testCountry() {
    jdbc.sql(
            "INSERT INTO reference_set (code, name, source_citation) VALUES ('IDT_REF',"
                + " 'Identity test reference set', 'Synthetic test data') ON CONFLICT DO NOTHING")
        .update();
    jdbc.sql(
            "INSERT INTO ul_set (code, name, source_citation) VALUES ('IDT_UL',"
                + " 'Identity test upper limits', 'Synthetic test data') ON CONFLICT DO NOTHING")
        .update();
    jdbc.sql(
            "INSERT INTO country VALUES (?, 'Identity Testland', ?, 'IDT_REF', 'IDT_UL', 'tr')"
                + " ON CONFLICT DO NOTHING")
        .params(TEST_COUNTRY, TEST_CURRENCY)
        .update();
    return TEST_COUNTRY;
  }

  /** A country code that is not in the registry. */
  String missingCountry() {
    for (char a = 'Q'; a <= 'Z'; a++) {
      for (char b = 'A'; b <= 'Z'; b++) {
        String code = "" + a + b;
        if (!jdbc.sql("SELECT EXISTS (SELECT 1 FROM country WHERE code = ?)")
            .param(code)
            .query(Boolean.class)
            .single()) {
          return code;
        }
      }
    }
    throw new IllegalStateException("no free country code");
  }

  /** Inserts a synthetic product and returns its id. */
  UUID testProduct() {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO product (id, code, brand, name, dosage_form, units_per_container,"
                + " units_per_serving, servings_per_day, synthetic, created_at, updated_at)"
                + " VALUES (?, ?, 'Synthetic Labs', 'Synthetic daily', 'TABLET', 60, 1, 1, true,"
                + " now(), now())")
        .params(
            id,
            TEST_PRODUCT_PREFIX + id.toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT))
        .update();
    return id;
  }

  @org.junit.jupiter.api.AfterEach
  void removeSyntheticCatalogRows() {
    jdbc.sql("DELETE FROM product WHERE code LIKE 'IDT-%'").update();
    jdbc.sql("DELETE FROM saved_product WHERE country_code = ?").param(TEST_COUNTRY).update();
    jdbc.sql("DELETE FROM alert_subscription WHERE country_code = ?").param(TEST_COUNTRY).update();
    jdbc.sql("UPDATE app_user SET default_country_code = NULL WHERE default_country_code = ?")
        .param(TEST_COUNTRY)
        .update();
    jdbc.sql("DELETE FROM country WHERE code = ?").param(TEST_COUNTRY).update();
    jdbc.sql("DELETE FROM reference_set WHERE code = 'IDT_REF'").update();
    jdbc.sql("DELETE FROM ul_set WHERE code = 'IDT_UL'").update();
  }
}
