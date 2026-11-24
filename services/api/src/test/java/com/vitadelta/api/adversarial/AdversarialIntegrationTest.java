package com.vitadelta.api.adversarial;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.vitadelta.api.adversarial.AttackClient.Req;
import com.vitadelta.api.adversarial.AttackClient.Resp;
import com.vitadelta.api.identity.JwtConfig;
import com.vitadelta.api.mail.EmailSender;
import com.vitadelta.api.support.PostgresIntegrationTest;
import com.vitadelta.api.support.TestData;
import com.vitadelta.api.support.TestEnvironment;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * Base of the adversarial integration tests (project specification section 15.7): a running server
 * on the shared PostgreSQL container, a movable application clock, a recording e-mail sender that
 * can simulate an SMTP outage, and the trusted-proxy rule enabled for the loopback peer so that
 * every test picks its client IP through {@code X-Forwarded-For}.
 *
 * <p>The tests attack the API from the outside only: raw HTTP requests, plus direct SQL for
 * arranging rows and for checking that nothing changed. They never call implementation internals to
 * make an attack succeed.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "vitadelta.proxy.trust-proxy=true",
      "vitadelta.proxy.trusted-proxies=127.0.0.1/32,::1/128"
    })
@Import(AdversarialIntegrationTest.AdversaryConfig.class)
abstract class AdversarialIntegrationTest extends PostgresIntegrationTest {

  static final String PASSWORD = "plum-orbit-lantern-quartz";
  static final String API = "/api/v1";

  /** Synthetic catalog rows of the adversarial tests, removed after every test. */
  static final String COUNTRY = "XR";

  static final String PRODUCT_PREFIX = "ADV-";

  @TestConfiguration(proxyBeanMethods = false)
  static class AdversaryConfig {

    @Bean
    @Primary
    AdversaryClock adversaryClock() {
      return new AdversaryClock(Instant.now());
    }

    @Bean
    @Primary
    EmailSender recordingMailSender() {
      return new RecordingMailSender();
    }
  }

  @DynamicPropertySource
  static void adversaryProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "vitadelta.identity.weak-passwords-file",
        () -> TestData.dataDir().resolve("reference/weak_passwords.txt").toString());
  }

  @LocalServerPort int port;
  @Autowired AdversaryClock clock;
  @Autowired EmailSender emailSender;
  @Autowired JdbcClient jdbc;

  AttackClient http;

  /** The client IP of this test (sent as {@code X-Forwarded-For} from the trusted loopback). */
  String ip;

  @BeforeEach
  void setUpAttacker() {
    http = new AttackClient(port);
    ip = randomIp();
    mail().failing(false);
  }

  @AfterEach
  void removeSyntheticCatalogRows() {
    jdbc.sql(
            "DELETE FROM saved_product WHERE country_code = ? OR product_id IN"
                + " (SELECT id FROM product WHERE code LIKE 'ADV-%')")
        .param(COUNTRY)
        .update();
    jdbc.sql(
            "DELETE FROM alert_subscription WHERE country_code = ? OR product_id IN"
                + " (SELECT id FROM product WHERE code LIKE 'ADV-%')")
        .param(COUNTRY)
        .update();
    jdbc.sql("DELETE FROM product WHERE code LIKE 'ADV-%'").update();
    jdbc.sql("UPDATE app_user SET default_country_code = NULL WHERE default_country_code = ?")
        .param(COUNTRY)
        .update();
    jdbc.sql("DELETE FROM country WHERE code = ?").param(COUNTRY).update();
    jdbc.sql("DELETE FROM reference_set WHERE code = 'ADV_REF'").update();
    jdbc.sql("DELETE FROM ul_set WHERE code = 'ADV_UL'").update();
  }

  RecordingMailSender mail() {
    return (RecordingMailSender) emailSender;
  }

  static String randomIp() {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    return "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(1, 255);
  }

  static String uniqueEmail() {
    return "adv." + UUID.randomUUID().toString().substring(0, 12) + "@example.org";
  }

  /** A request from this test's client IP. */
  Req req(String method, String path) {
    return http.req(method, path).ip(ip);
  }

  Resp postJson(String path, Map<String, ?> body) {
    return req("POST", path).json(body).send();
  }

  Resp get(String path, String bearer) {
    return req("GET", path).bearer(bearer).send();
  }

  Resp refresh(String refreshToken) {
    return req("POST", API + "/auth/refresh")
        .header("X-Requested-With", "vitadelta")
        .cookie("vd_refresh", refreshToken)
        .json("{}")
        .send();
  }

  // Accounts -----------------------------------------------------------------------------------

  Resp register(String email, String password) {
    Resp response = postJson(API + "/auth/register", Map.of("email", email, "password", password));
    assertThat(response.status()).as(response.toString()).isEqualTo(202);
    return response;
  }

  /** Registers through the API, verifies with the e-mailed token and returns the user id. */
  UUID registerVerified(String email) {
    int before = mail().to(email).size();
    String previousIp = ip;
    ip = randomIp();
    register(email, PASSWORD);
    String token = mail().awaitToken(email, before + 1);
    Resp verified = postJson(API + "/auth/verify-email", Map.of("token", token));
    assertThat(verified.status()).as(verified.toString()).isEqualTo(204);
    ip = previousIp;
    return userId(email);
  }

  UUID userId(String email) {
    return jdbc.sql("SELECT id FROM app_user WHERE email = CAST(? AS citext)")
        .param(email)
        .query(UUID.class)
        .single();
  }

  /** A logged-in session. */
  record Session(String accessToken, String refreshToken, JsonNode body) {}

  Session login(String email, String password) {
    String previousIp = ip;
    ip = randomIp();
    Resp response = postJson(API + "/auth/login", Map.of("email", email, "password", password));
    ip = previousIp;
    assertThat(response.status()).as(response.toString()).isEqualTo(200);
    return new Session(
        response.json().get("accessToken").asString(),
        response.cookieValue("vd_refresh"),
        response.json());
  }

  /** Registers, verifies and promotes an administrator; returns its access token. */
  String adminToken() {
    String email = uniqueEmail();
    UUID id = registerVerified(email);
    jdbc.sql("UPDATE app_user SET role = 'ADMIN' WHERE id = ?").param(id).update();
    return login(email, PASSWORD).accessToken();
  }

  /**
   * Inserts an account directly (no Argon2 cost) with an ACTIVE FREE subscription. Such accounts
   * cannot log in; tests address them with {@link #accessToken}.
   */
  UUID insertUser(boolean verified) {
    UUID id = UUID.randomUUID();
    Timestamp now = Timestamp.from(clock.instant());
    jdbc.sql(
            "INSERT INTO app_user (id, email, password_hash, email_verified_at, created_at,"
                + " updated_at) VALUES (?, ?, 'not-a-usable-hash', ?, ?, ?)")
        .params(id, uniqueEmail(), verified ? now : null, now, now)
        .update();
    jdbc.sql(
            "INSERT INTO subscription (id, user_id, plan_code, status, source, started_at)"
                + " VALUES (?, ?, 'FREE', 'ACTIVE', 'MANUAL', ?)")
        .params(UUID.randomUUID(), id, now)
        .update();
    return id;
  }

  // Access tokens ------------------------------------------------------------------------------

  static byte[] currentSecret() {
    return TestEnvironment.jwtSecret().getBytes(StandardCharsets.UTF_8);
  }

  static String currentKeyId() {
    return JwtConfig.keyId(currentSecret());
  }

  /** A correctly signed access token as the API would issue it (the test holds the secret). */
  String accessToken(UUID userId, String role, boolean emailVerified) {
    return sign(
        currentSecret(),
        currentKeyId(),
        JWSAlgorithm.HS256,
        claims(userId.toString(), role, emailVerified, clock.instant(), Duration.ofMinutes(15)));
  }

  static JWTClaimsSet.Builder claims(
      String subject, String role, boolean emailVerified, Instant issuedAt, Duration lifetime) {
    JWTClaimsSet.Builder builder =
        new JWTClaimsSet.Builder()
            .jwtID(UUID.randomUUID().toString())
            .issueTime(Date.from(issuedAt))
            .expirationTime(Date.from(issuedAt.plus(lifetime)))
            .claim("email_verified", emailVerified);
    if (subject != null) {
      builder.subject(subject);
    }
    if (role != null) {
      builder.claim("role", role);
    }
    return builder;
  }

  static String sign(byte[] secret, String keyId, JWSAlgorithm alg, JWTClaimsSet.Builder claims) {
    JWSHeader.Builder header = new JWSHeader.Builder(alg).type(JOSEObjectType.JWT);
    if (keyId != null) {
      header.keyID(keyId);
    }
    SignedJWT jwt = new SignedJWT(header.build(), claims.build());
    try {
      jwt.sign(new MACSigner(secret));
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
    return jwt.serialize();
  }

  /** A random secret of {@code bytes} bytes, as text (the API uses the UTF-8 bytes). */
  static String randomSecret(int bytes) {
    byte[] value = new byte[bytes];
    new SecureRandom().nextBytes(value);
    return java.util.HexFormat.of().formatHex(value);
  }

  // Synthetic catalog rows ---------------------------------------------------------------------

  String country() {
    jdbc.sql(
            "INSERT INTO reference_set (code, name, source_citation) VALUES ('ADV_REF',"
                + " 'Adversarial test reference set', 'Synthetic test data') ON CONFLICT DO NOTHING")
        .update();
    jdbc.sql(
            "INSERT INTO ul_set (code, name, source_citation) VALUES ('ADV_UL',"
                + " 'Adversarial test upper limits', 'Synthetic test data') ON CONFLICT DO NOTHING")
        .update();
    jdbc.sql(
            "INSERT INTO country VALUES (?, 'Adversaria', 'XRD', 'ADV_REF', 'ADV_UL', 'en')"
                + " ON CONFLICT DO NOTHING")
        .param(COUNTRY)
        .update();
    return COUNTRY;
  }

  /** A synthetic product (fictional brand) for saved-product and alert rows. */
  UUID product() {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO product (id, code, brand, name, dosage_form, units_per_container,"
                + " units_per_serving, servings_per_day, synthetic, created_at, updated_at)"
                + " VALUES (?, ?, 'Synthetic Adversary Labs', 'Synthetic daily', 'TABLET', 60, 1,"
                + " 1, true, now(), now())")
        .params(id, PRODUCT_PREFIX + id.toString().substring(0, 8).toUpperCase(Locale.ROOT))
        .update();
    return id;
  }

  void saveProduct(UUID userId, UUID productId) {
    jdbc.sql(
            "INSERT INTO saved_product (user_id, product_id, country_code, created_at)"
                + " VALUES (?, ?, ?, now())")
        .params(userId, productId, country())
        .update();
  }

  UUID alert(UUID userId, UUID productId, String trigger, boolean active) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO alert_subscription (id, user_id, product_id, country_code, trigger,"
                + " threshold_percent, active, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, now())")
        .params(
            id,
            userId,
            productId,
            country(),
            trigger,
            trigger.equals("PRICE_DROP") ? new java.math.BigDecimal("5.00") : null,
            active)
        .update();
    return id;
  }

  long count(String sql, Object... params) {
    return jdbc.sql(sql).params(params).query(Long.class).single();
  }

  // Promo codes --------------------------------------------------------------------------------

  private static final String CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

  /** A code in the generated format that is not stored. */
  static String randomCode() {
    StringBuilder code = new StringBuilder("ADV-");
    ThreadLocalRandom random = ThreadLocalRandom.current();
    for (int i = 0; i < 16; i++) {
      code.append(CROCKFORD.charAt(random.nextInt(CROCKFORD.length())));
    }
    return code.toString();
  }

  /** Inserts a promo code directly. */
  String insertPromo(int maxRedemptions, int redeemed, Instant expiresAt) {
    String code = randomCode();
    jdbc.sql(
            "INSERT INTO promo_code (code, plan_code, duration_days, max_redemptions,"
                + " redeemed_count, expires_at, created_at) VALUES (?, 'PRO', 30, ?, ?, ?, ?)")
        .params(
            code,
            maxRedemptions,
            redeemed,
            expiresAt == null ? null : Timestamp.from(expiresAt),
            Timestamp.from(clock.instant()))
        .update();
    return code;
  }
}
