package com.vitadelta.api.entitlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.identity.AccessTokenService;
import com.vitadelta.api.identity.CurrentUser;
import com.vitadelta.api.identity.Role;
import com.vitadelta.api.identity.UserAccount;
import com.vitadelta.api.identity.UserRepository;
import com.vitadelta.api.identity.UserStatus;
import com.vitadelta.api.mail.EmailSender;
import com.vitadelta.api.support.PostgresIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base of the entitlement and billing integration tests: a running server on the shared PostgreSQL
 * container, a movable clock, a recording e-mail sender, recorded subscription events, the
 * trusted-proxy rule for the loopback peer (each test picks its client IP through {@code
 * X-Forwarded-For}) and scheduling switched off (jobs are run directly).
 *
 * <p>Probe routes (test only) run the {@link EntitlementService} checks the way the Phase 4 and
 * Phase 5 modules will, so that limit errors are rendered by the real exception handler.
 *
 * <p>Users are inserted directly (e-mail domain {@link #DOMAIN}) with their FREE subscription and
 * get access tokens from {@link AccessTokenService}; every row created by these tests is removed
 * after each test.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "vitadelta.proxy.trust-proxy=true",
      "vitadelta.proxy.trusted-proxies=127.0.0.1/32,::1/128",
      "vitadelta.operations.jobs-enabled=false"
    })
@Import({
  EntitlementIntegrationTest.EntitlementTestConfig.class,
  EntitlementIntegrationTest.EntitlementProbe.class
})
public abstract class EntitlementIntegrationTest extends PostgresIntegrationTest {

  protected static final JsonMapper JSON = JsonMapper.builder().build();

  /** E-mail domain of every user created by these tests. */
  protected static final String DOMAIN = "entitlement.example.org";

  protected static final String COUNTRY = "XE";
  protected static final String CURRENCY = "XEC";
  protected static final String PRODUCT_PREFIX = "ENT-";
  protected static final String PROMO_PREFIX = "ENTTEST";

  @TestConfiguration(proxyBeanMethods = false)
  static class EntitlementTestConfig {

    @Bean
    @Primary
    TestClock entitlementTestClock() {
      return new TestClock(Instant.now());
    }

    @Bean
    @Primary
    EmailSender recordingEmailSender() {
      return new RecordingEmailSender();
    }

    @Bean
    SubscriptionEvents subscriptionEvents() {
      return new SubscriptionEvents();
    }
  }

  /** Records the subscription events. */
  public static class SubscriptionEvents {
    public final List<SubscriptionActivatedEvent> activated = new CopyOnWriteArrayList<>();
    public final List<SubscriptionExpiredEvent> expired = new CopyOnWriteArrayList<>();

    @EventListener
    void on(SubscriptionActivatedEvent event) {
      activated.add(event);
    }

    @EventListener
    void on(SubscriptionExpiredEvent event) {
      expired.add(event);
    }
  }

  /** Test-only routes that perform the limit checks inside a writing transaction. */
  @RestController
  static class EntitlementProbe {

    private final EntitlementService entitlements;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    EntitlementProbe(
        EntitlementService entitlements,
        JdbcClient jdbc,
        PlatformTransactionManager transactionManager) {
      this.entitlements = entitlements;
      this.jdbc = jdbc;
      this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Saves a product (idempotent): 201 when inserted, 200 when already saved. */
    @PostMapping("/api/v1/me/entitlement-probe/saved-products/{productId}")
    ResponseEntity<Map<String, Object>> save(
        @PathVariable UUID productId, @RequestParam String country, Authentication authentication) {
      UUID userId = CurrentUser.require(authentication);
      Boolean created =
          transactions.execute(status -> {
            boolean exists =
                jdbc.sql(
                        "SELECT EXISTS (SELECT 1 FROM saved_product WHERE user_id = ?"
                            + " AND product_id = ? AND country_code = ?)")
                    .params(userId, productId, country)
                    .query(Boolean.class)
                    .single();
            if (exists) {
              return false;
            }
            entitlements.requireSavedProductSlot(userId);
            jdbc.sql(
                    "INSERT INTO saved_product (user_id, product_id, country_code, created_at)"
                        + " VALUES (?, ?, ?, now())")
                .params(userId, productId, country)
                .update();
            return true;
          });
      return ResponseEntity.status(Boolean.TRUE.equals(created) ? 201 : 200)
          .body(Map.of("created", Boolean.TRUE.equals(created)));
    }

    /** Sets an alert subscription of the caller active. */
    @PostMapping("/api/v1/me/entitlement-probe/alerts/{alertId}/activate")
    Map<String, Object> activate(@PathVariable UUID alertId, Authentication authentication) {
      UUID userId = CurrentUser.require(authentication);
      transactions.executeWithoutResult(status -> {
        entitlements.requireAlertSlot(userId);
        jdbc.sql("UPDATE alert_subscription SET active = true WHERE id = ? AND user_id = ?")
            .params(alertId, userId)
            .update();
      });
      return Map.of("active", true);
    }

    /** Reserves one anonymous query of the identity hash. */
    @PostMapping("/api/v1/recommendations/entitlement-probe/{idHash}")
    Map<String, Object> query(@PathVariable String idHash) {
      return Map.of("used", entitlements.reserveAnonymousQuery(idHash));
    }
  }

  @LocalServerPort protected int port;
  @Autowired protected TestClock clock;
  @Autowired protected EmailSender emailSender;
  @Autowired protected SubscriptionEvents events;
  @Autowired protected JdbcClient jdbc;
  @Autowired protected UserRepository users;
  @Autowired protected SubscriptionService subscriptionService;
  @Autowired protected AccessTokenService accessTokens;
  @Autowired protected PlatformTransactionManager transactionManager;

  protected RestTestClient client;
  protected TransactionTemplate transactions;

  /** The client IP of this test (sent as {@code X-Forwarded-For}). */
  protected String ip;

  @BeforeEach
  void setUpEntitlementClient() {
    client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    transactions = new TransactionTemplate(transactionManager);
    ip = randomIp();
    // Millisecond precision: timestamptz keeps microseconds, so instants read back compare equal.
    clock.set(Instant.now().truncatedTo(ChronoUnit.MILLIS));
  }

  @AfterEach
  void removeEntitlementTestRows() {
    jdbc.sql("DELETE FROM promo_code WHERE code LIKE ?").param(PROMO_PREFIX + "-%").update();
    jdbc.sql("DELETE FROM app_user WHERE email LIKE ?").param("%@" + DOMAIN).update();
    jdbc.sql("DELETE FROM product WHERE code LIKE ?").param(PRODUCT_PREFIX + "%").update();
    jdbc.sql("DELETE FROM country WHERE code = ?").param(COUNTRY).update();
    jdbc.sql("DELETE FROM reference_set WHERE code = 'ENT_REF'").update();
    jdbc.sql("DELETE FROM ul_set WHERE code = 'ENT_UL'").update();
  }

  protected RecordingEmailSender mail() {
    return (RecordingEmailSender) emailSender;
  }

  protected static String randomIp() {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    return "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(1, 255);
  }

  /** A test user. */
  protected record TestUser(UUID id, String email, String token) {}

  /** Inserts a verified USER with its FREE subscription. */
  protected TestUser user() {
    return user(true, Role.USER);
  }

  /** Inserts a user with its FREE subscription, the way registration does. */
  protected TestUser user(boolean verified, Role role) {
    UUID id = UUID.randomUUID();
    String email = "u." + id.toString().substring(0, 13) + "@" + DOMAIN;
    Instant now = clock.instant();
    transactions.executeWithoutResult(status -> {
      users.insertIfAbsent(
          new UserAccount(
              id,
              email,
              "synthetic-password-hash",
              verified ? now : null,
              role,
              UserStatus.ACTIVE,
              null,
              null,
              "en",
              true,
              null,
              now,
              now));
      subscriptionService.provisionFreeSubscription(id, now);
    });
    return new TestUser(id, email, token(id, role, verified));
  }

  /** A fresh access token (tokens are validated with the test clock). */
  protected String token(UUID userId, Role role, boolean verified) {
    return accessTokens.issue(userId, role, verified).value();
  }

  /** A fresh token for {@code user} (after the clock moved). */
  protected String token(TestUser user) {
    return token(user.id(), Role.USER, true);
  }

  protected TestUser admin() {
    return user(true, Role.ADMIN);
  }

  /** The synthetic test country (idempotent). */
  protected String country() {
    jdbc.sql(
            "INSERT INTO reference_set (code, name, source_citation) VALUES ('ENT_REF',"
                + " 'Entitlement test reference set', 'Synthetic test data')"
                + " ON CONFLICT DO NOTHING")
        .update();
    jdbc.sql(
            "INSERT INTO ul_set (code, name, source_citation) VALUES ('ENT_UL',"
                + " 'Entitlement test upper limits', 'Synthetic test data') ON CONFLICT DO NOTHING")
        .update();
    jdbc.sql(
            "INSERT INTO country VALUES (?, 'Entitlement Testland', ?, 'ENT_REF', 'ENT_UL', 'en')"
                + " ON CONFLICT DO NOTHING")
        .params(COUNTRY, CURRENCY)
        .update();
    return COUNTRY;
  }

  /** Inserts a synthetic product and returns its id. */
  protected UUID product() {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO product (id, code, brand, name, dosage_form, units_per_container,"
                + " units_per_serving, servings_per_day, synthetic, created_at, updated_at)"
                + " VALUES (?, ?, 'Synthetic Labs', 'Synthetic daily', 'TABLET', 60, 1, 1, true,"
                + " now(), now())")
        .params(id, PRODUCT_PREFIX + id.toString().substring(0, 8).toUpperCase(Locale.ROOT))
        .update();
    return id;
  }

  /** Inserts {@code count} saved products of the user directly. */
  protected void savedProducts(UUID userId, int count) {
    String country = country();
    for (int i = 0; i < count; i++) {
      jdbc.sql(
              "INSERT INTO saved_product (user_id, product_id, country_code, created_at)"
                  + " VALUES (?, ?, ?, now())")
          .params(userId, product(), country)
          .update();
    }
  }

  /**
   * Inserts an alert subscription directly ({@code BACK_IN_STOCK}, no threshold) created at {@code
   * createdAt}.
   */
  protected UUID alert(UUID userId, boolean active, Instant createdAt) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO alert_subscription (id, user_id, product_id, country_code, trigger,"
                + " threshold_percent, active, created_at) VALUES (?, ?, ?, ?, 'BACK_IN_STOCK',"
                + " NULL, ?, ?)")
        .params(id, userId, product(), country(), active, SqlTime.ts(createdAt))
        .update();
    return id;
  }

  protected long activeAlerts(UUID userId) {
    return jdbc.sql("SELECT count(*) FROM alert_subscription WHERE user_id = ? AND active")
        .param(userId)
        .query(Long.class)
        .single();
  }

  protected long savedCount(UUID userId) {
    return jdbc.sql("SELECT count(*) FROM saved_product WHERE user_id = ?")
        .param(userId)
        .query(Long.class)
        .single();
  }

  /** The user's subscriptions, oldest first. */
  protected List<Map<String, Object>> subscriptions(UUID userId) {
    return jdbc.sql(
            "SELECT plan_code, status, source, started_at, current_period_end, canceled_at"
                + " FROM subscription WHERE user_id = ? ORDER BY started_at, status")
        .param(userId)
        .query()
        .listOfRows();
  }

  protected long activeCount(UUID userId) {
    return jdbc.sql("SELECT count(*) FROM subscription WHERE user_id = ? AND status = 'ACTIVE'")
        .param(userId)
        .query(Long.class)
        .single();
  }

  // HTTP helpers

  protected EntityExchangeResult<String> post(String path, String body, String bearer) {
    return post(path, body, bearer, ip, null);
  }

  protected EntityExchangeResult<String> post(
      String path, String body, String bearer, String clientIp, String requestId) {
    RestTestClient.RequestBodySpec spec =
        client
            .post()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Forwarded-For", clientIp);
    if (bearer != null) {
      spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
    }
    if (requestId != null) {
      spec = spec.header("X-Request-Id", requestId);
    }
    return spec.body(body).exchange().returnResult(String.class);
  }

  protected EntityExchangeResult<String> get(String path, String bearer) {
    RestTestClient.RequestHeadersSpec<?> spec =
        client.get().uri(path).header("X-Forwarded-For", ip);
    if (bearer != null) {
      spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
    }
    return spec.exchange().returnResult(String.class);
  }

  protected EntityExchangeResult<String> delete(String path, String bearer) {
    RestTestClient.RequestHeadersSpec<?> spec =
        client.delete().uri(path).header("X-Forwarded-For", ip);
    if (bearer != null) {
      spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
    }
    return spec.exchange().returnResult(String.class);
  }

  protected static JsonNode json(EntityExchangeResult<String> result) {
    return JSON.readTree(result.getResponseBody());
  }

  protected static String body(Map<String, ?> fields) {
    return JSON.writeValueAsString(fields);
  }

  protected static void assertStatus(EntityExchangeResult<String> result, int status) {
    assertThat(result.getStatus().value()).as(result.getResponseBody()).isEqualTo(status);
  }

  /** Asserts status, problem content type and type, and the contract schema {@code schema}. */
  protected static void assertProblem(
      EntityExchangeResult<String> result, int status, String code, String schema) {
    assertStatus(result, status);
    assertThat(String.valueOf(result.getResponseHeaders().getContentType()))
        .startsWith("application/problem+json");
    assertThat(json(result).get("type").asString())
        .isEqualTo(code.equals("about:blank") ? code : "https://vitadelta.dev/problems/" + code);
    Contract.assertConforms(schema, result.getResponseBody());
  }

  protected static void assertProblem(
      EntityExchangeResult<String> result, int status, String code) {
    assertProblem(result, status, code, "Problem");
  }

  /** Runs the tasks at the same moment on separate threads and returns their results in order. */
  protected static <T> List<T> parallel(List<Callable<T>> tasks) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<T>> futures = new ArrayList<>();
      for (Callable<T> task : tasks) {
        futures.add(
            executor.submit(() -> {
              start.await();
              return task.call();
            }));
      }
      start.countDown();
      List<T> results = new ArrayList<>();
      for (Future<T> future : futures) {
        results.add(future.get(60, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      executor.shutdownNow();
    }
  }
}
