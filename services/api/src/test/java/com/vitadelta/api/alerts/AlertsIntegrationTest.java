package com.vitadelta.api.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.catalog.price.EffectivePrice;
import com.vitadelta.api.catalog.seed.SeedService;
import com.vitadelta.api.common.jobs.JobLockKeys;
import com.vitadelta.api.entitlement.SubscriptionService;
import com.vitadelta.api.identity.AccessTokenService;
import com.vitadelta.api.identity.ContractSchemas;
import com.vitadelta.api.identity.PasswordPolicy;
import com.vitadelta.api.identity.Role;
import com.vitadelta.api.identity.UserAccount;
import com.vitadelta.api.identity.UserRepository;
import com.vitadelta.api.identity.UserStatus;
import com.vitadelta.api.imports.OfferChangeListener.OfferPairChange;
import com.vitadelta.api.mail.EmailMessage;
import com.vitadelta.api.mail.EmailSender;
import com.vitadelta.api.mail.SmtpEmailSender;
import com.vitadelta.api.support.PostgresIntegrationTest;
import com.vitadelta.api.support.TestData;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base of the alerts integration tests: a running server on the shared PostgreSQL container, a
 * Mailpit container receiving the alert e-mails over real SMTP, a movable clock, the seeded fixture
 * catalog and scheduling switched off (the tests run the outbox processor directly).
 *
 * <p>No test context runs scheduled jobs (see {@code PostgresIntegrationTest}). As a second guard,
 * each test holds the processor's advisory lock on a dedicated connection, so a scheduled run of
 * any other process on the same database is skipped and only the test processes events. The outbox
 * is emptied before and after every test; users of {@link #DOMAIN} are removed with the cascade of
 * section 5.9.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "vitadelta.operations.jobs-enabled=false",
      "vitadelta.proxy.trust-proxy=true",
      "vitadelta.proxy.trusted-proxies=127.0.0.1/32,::1/128"
    })
@Import(AlertsIntegrationTest.AlertsTestConfig.class)
public abstract class AlertsIntegrationTest extends PostgresIntegrationTest {

  static final String MAILPIT_IMAGE = "axllent/mailpit:v1.34.0";

  protected static final JsonMapper JSON = JsonMapper.builder().build();

  /** E-mail domain of every user created by these tests. */
  protected static final String DOMAIN = "alerts.example.org";

  static final GenericContainer<?> MAILPIT =
      new GenericContainer<>(DockerImageName.parse(MAILPIT_IMAGE))
          .withExposedPorts(1025, 8025)
          .waitingFor(Wait.forHttp("/readyz").forPort(8025));

  private static volatile boolean seeded;

  static {
    MAILPIT.start();
  }

  @DynamicPropertySource
  static void mailProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.mail.host", MAILPIT::getHost);
    registry.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
    registry.add(
        "vitadelta.identity.weak-passwords-file",
        () -> TestData.dataDir().resolve(PasswordPolicy.LIST_PATH).toString());
  }

  /** A UTC clock the tests move explicitly. */
  public static final class TestClock extends Clock {

    private final AtomicReference<Instant> now;

    TestClock(Instant start) {
      this.now = new AtomicReference<>(start);
    }

    public void set(Instant instant) {
      now.set(instant);
    }

    public void advance(Duration duration) {
      now.updateAndGet(i -> i.plus(duration));
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now.get();
    }
  }

  /**
   * Delivers through the real SMTP transport (Mailpit) and records every call: whether a
   * transaction was active, and whether the event and notification rows could be locked from
   * another connection at that moment (no row lock held by the sender). A test can make the next
   * deliveries fail.
   */
  public static final class RecordingTransport implements EmailSender {

    /** One recorded delivery attempt. */
    public record Call(
        EmailMessage message,
        String unsubscribeUrl,
        boolean transactionActive,
        boolean rowsLockable) {}

    private final SmtpEmailSender delegate;
    private final DataSource dataSource;
    public final List<Call> calls = new CopyOnWriteArrayList<>();
    private volatile int failuresLeft;
    private volatile Consumer<EmailMessage> during = message -> {};

    RecordingTransport(SmtpEmailSender delegate, DataSource dataSource) {
      this.delegate = delegate;
      this.dataSource = dataSource;
    }

    public void failNext(int count) {
      failuresLeft = count;
    }

    /** Runs {@code action} while a delivery is in progress (before SMTP). */
    public void during(Consumer<EmailMessage> action) {
      during = action;
    }

    public void reset() {
      calls.clear();
      failuresLeft = 0;
      during = message -> {};
    }

    /**
     * Records and may fail only alert and cap mails (they carry {@code List-Unsubscribe}); identity
     * and plan mails of the same context go straight to SMTP.
     */
    @Override
    public void send(EmailMessage message) {
      String listUnsubscribe = message.headers().get(EmailMessage.LIST_UNSUBSCRIBE);
      if (listUnsubscribe == null) {
        delegate.send(message);
        return;
      }
      boolean active =
          org.springframework.transaction.support.TransactionSynchronizationManager
              .isActualTransactionActive();
      String unsubscribeUrl = listUnsubscribe.substring(1, listUnsubscribe.length() - 1);
      calls.add(new Call(message, unsubscribeUrl, active, rowsLockable()));
      during.accept(message);
      if (failuresLeft > 0) {
        failuresLeft--;
        throw new com.vitadelta.api.mail.EmailDeliveryException(
            "SMTP delivery failed: simulated", new IllegalStateException("simulated"));
      }
      delegate.send(message);
    }

    /** Locks every outbox and notification row with NOWAIT from a separate connection. */
    private boolean rowsLockable() {
      try (Connection connection = dataSource.getConnection()) {
        connection.setAutoCommit(false);
        try (PreparedStatement events =
                connection.prepareStatement("SELECT id FROM outbox_event FOR UPDATE NOWAIT");
            PreparedStatement notifications =
                connection.prepareStatement("SELECT id FROM notification FOR UPDATE NOWAIT")) {
          events.executeQuery().close();
          notifications.executeQuery().close();
          return true;
        } catch (SQLException e) {
          return false;
        } finally {
          connection.rollback();
        }
      } catch (SQLException e) {
        return false;
      }
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class AlertsTestConfig {

    @Bean
    @Primary
    TestClock alertsTestClock() {
      return new TestClock(Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }

    @Bean
    @Primary
    RecordingTransport recordingAlertTransport(SmtpEmailSender smtp, DataSource dataSource) {
      return new RecordingTransport(smtp, dataSource);
    }
  }

  @LocalServerPort protected int port;
  @Autowired protected TestClock clock;
  @Autowired protected RecordingTransport transport;
  @Autowired protected JdbcClient jdbc;
  @Autowired protected UserRepository users;
  @Autowired protected SubscriptionService subscriptionService;
  @Autowired protected AccessTokenService accessTokens;
  @Autowired protected PlatformTransactionManager transactionManager;
  @Autowired protected OutboxProcessor processor;
  @Autowired protected OffersChangedEmitter emitter;
  @Autowired protected DataSource dataSource;
  @Autowired private SeedService seedService;

  protected RestTestClient client;
  protected TransactionTemplate transactions;
  private Connection processorLock;
  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void setUpAlerts() throws Exception {
    client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    transactions = new TransactionTemplate(transactionManager);
    clock.set(Instant.now().truncatedTo(ChronoUnit.MILLIS));
    transport.reset();
    if (!seeded) {
      seedService.seed(TestData.dataDir());
      seeded = true;
    }
    processorLock = holdLock(JobLockKeys.OUTBOX_PROCESSOR);
    clearOutbox();
  }

  @AfterEach
  void tearDownAlerts() throws SQLException {
    try {
      clearOutbox();
      jdbc.sql("DELETE FROM app_user WHERE email LIKE ?").param("%@" + DOMAIN).update();
    } finally {
      release(processorLock, JobLockKeys.OUTBOX_PROCESSOR);
    }
  }

  /** Lets other sessions take the processor lock (for the lock test). */
  protected void releaseProcessorLock() throws SQLException {
    release(processorLock, JobLockKeys.OUTBOX_PROCESSOR);
    processorLock = null;
  }

  protected void reacquireProcessorLock() throws Exception {
    processorLock = holdLock(JobLockKeys.OUTBOX_PROCESSOR);
  }

  protected void clearOutbox() {
    jdbc.sql("DELETE FROM outbox_event").update();
  }

  /** Takes an advisory lock on a dedicated connection, waiting while another session holds it. */
  protected Connection holdLock(long key) throws Exception {
    Connection connection = dataSource.getConnection();
    connection.setAutoCommit(true);
    Awaitility.await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(100))
        .until(() -> tryLock(connection, key));
    return connection;
  }

  protected static void release(Connection connection, long key) throws SQLException {
    if (connection == null) {
      return;
    }
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
      statement.setLong(1, key);
      statement.executeQuery().close();
    } finally {
      connection.close();
    }
  }

  private static boolean tryLock(Connection connection, long key) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
      statement.setLong(1, key);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() && result.getBoolean(1);
      }
    }
  }

  // ---------- Users ----------

  /** A test user. */
  protected record TestUser(UUID id, String email) {}

  protected TestUser user() {
    return user(true, true, UserStatus.ACTIVE, "en");
  }

  /** Inserts a USER with its FREE subscription, the way registration does. */
  protected TestUser user(boolean verified, boolean emailAlerts, UserStatus status, String locale) {
    UUID id = UUID.randomUUID();
    String email = "a." + id.toString().substring(0, 13) + "@" + DOMAIN;
    Instant now = clock.instant();
    transactions.executeWithoutResult(tx -> {
      users.insertIfAbsent(
          new UserAccount(
              id,
              email,
              "synthetic-password-hash",
              verified ? now : null,
              Role.USER,
              status,
              null,
              null,
              locale,
              emailAlerts,
              null,
              now,
              now));
      subscriptionService.provisionFreeSubscription(id, now);
    });
    return new TestUser(id, email);
  }

  protected String token(TestUser user) {
    return accessTokens.issue(user.id(), Role.USER, true).value();
  }

  protected String adminToken() {
    TestUser admin = user();
    jdbc.sql("UPDATE app_user SET role = 'ADMIN' WHERE id = ?").param(admin.id()).update();
    return accessTokens.issue(admin.id(), Role.ADMIN, true).value();
  }

  // ---------- Catalog ----------

  protected UUID productId(String code) {
    return jdbc.sql("SELECT id FROM product WHERE code = ?").param(code).query(UUID.class).single();
  }

  /** Inserts a subscription directly (no limit checks). */
  protected UUID subscription(
      TestUser user,
      UUID productId,
      String country,
      AlertTrigger trigger,
      BigDecimal threshold,
      BigDecimal baseline) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO saved_product (user_id, product_id, country_code, created_at)"
                + " VALUES (?, ?, ?, now()) ON CONFLICT DO NOTHING")
        .params(user.id(), productId, country)
        .update();
    jdbc.sql(
            "INSERT INTO alert_subscription (id, user_id, product_id, country_code, trigger,"
                + " threshold_percent, baseline_price, active, created_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, true, now())")
        .params(id, user.id(), productId, country, trigger.name(), threshold, baseline)
        .update();
    return id;
  }

  // ---------- Outbox ----------

  protected static EffectivePrice.State state(String price, boolean inStock) {
    return new EffectivePrice.State(
        new BigDecimal(price).stripTrailingZeros(),
        inStock,
        "Fixture Pharmacy",
        "https://fixture-pharmacy.example/tr/p/test");
  }

  /**
   * Writes one {@code OFFERS_CHANGED} event through the emitter, as an import would: the catalog
   * version is bumped first and the event carries the version the transaction commits with.
   */
  protected void offersChanged(
      UUID productId, String country, EffectivePrice.State before, EffectivePrice.State after) {
    transactions.executeWithoutResult(tx ->
        emitter.offersChanged(
            List.of(new OfferPairChange(productId, "TEST", country, before, after)),
            jdbc.sql(
                    "UPDATE catalog_version SET version = version + 1, bumped_at = now()"
                        + " WHERE id = 1 RETURNING version")
                .query(Long.class)
                .single()));
  }

  /** The committed catalog version. */
  protected long committedCatalogVersion() {
    return jdbc.sql("SELECT version FROM catalog_version WHERE id = 1").query(Long.class).single();
  }

  /** Runs the processor until no event is due (at most 10 batches). */
  protected void drainOutbox() {
    for (int i = 0; i < 10; i++) {
      if (processor.processDue().claimed() == 0) {
        return;
      }
    }
  }

  protected List<Map<String, Object>> events() {
    return jdbc.sql(
            "SELECT id, event_type, aggregate_id, payload::text AS payload, processed_at, outcome,"
                + " attempts, last_error, next_attempt_at, lease_until FROM outbox_event"
                + " ORDER BY created_at, id")
        .query()
        .listOfRows();
  }

  protected List<Map<String, Object>> notifications(TestUser user) {
    return jdbc.sql(
            "SELECT id, type, payload::text AS payload, email_sent_at, email_error, read_at"
                + " FROM notification WHERE user_id = ? ORDER BY created_at, id")
        .param(user.id())
        .query()
        .listOfRows();
  }

  protected Map<String, Object> subscriptionRow(UUID id) {
    return jdbc.sql(
            "SELECT baseline_price, threshold_percent, last_triggered_at, active"
                + " FROM alert_subscription WHERE id = ?")
        .param(id)
        .query()
        .singleRow();
  }

  // ---------- HTTP ----------

  protected EntityExchangeResult<String> get(String path, String bearer) {
    RestTestClient.RequestHeadersSpec<?> spec = client.get().uri(path);
    if (bearer != null) {
      spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
    }
    return spec.exchange().returnResult(String.class);
  }

  protected EntityExchangeResult<String> post(String path, String body, String bearer) {
    RestTestClient.RequestBodySpec spec =
        client.post().uri(path).contentType(MediaType.APPLICATION_JSON);
    if (bearer != null) {
      spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
    }
    return spec.body(body).exchange().returnResult(String.class);
  }

  protected EntityExchangeResult<String> postEmpty(String path, String bearer) {
    RestTestClient.RequestBodySpec spec = client.post().uri(path);
    if (bearer != null) {
      spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
    }
    return spec.exchange().returnResult(String.class);
  }

  protected EntityExchangeResult<String> patch(String path, String body, String bearer) {
    RestTestClient.RequestBodySpec spec =
        client.patch().uri(path).contentType(MediaType.APPLICATION_JSON);
    if (bearer != null) {
      spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
    }
    return spec.body(body).exchange().returnResult(String.class);
  }

  protected EntityExchangeResult<String> delete(String path, String bearer) {
    RestTestClient.RequestHeadersSpec<?> spec = client.delete().uri(path);
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

  /** Asserts status, problem content type, type and the contract {@code Problem} schema. */
  protected static void assertProblem(
      EntityExchangeResult<String> result, int status, String code) {
    assertStatus(result, status);
    assertThat(String.valueOf(result.getResponseHeaders().getContentType()))
        .startsWith("application/problem+json");
    assertThat(json(result).get("type").asString())
        .isEqualTo(code.equals("about:blank") ? code : "https://vitadelta.dev/problems/" + code);
    ContractSchemas.assertConforms(
        code.equals("entitlement_exceeded") ? "EntitlementExceededProblem" : "Problem",
        result.getResponseBody());
  }

  /** The field error codes of a 422 body. */
  protected static List<String> errorCodes(EntityExchangeResult<String> result, String field) {
    List<String> codes = new ArrayList<>();
    json(result)
        .path("errors")
        .forEach(e -> {
          if (e.path("field").asString().equals(field)) {
            codes.add(e.path("code").asString());
          }
        });
    return codes;
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

  // ---------- Mailpit ----------

  protected JsonNode mailpit(String path) throws Exception {
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025) + path))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    return JSON.readTree(response.body());
  }

  /** The summaries of every message delivered to {@code to}. */
  protected JsonNode messagesTo(String to) throws Exception {
    return mailpit(
            "/api/v1/search?query="
                + URLEncoder.encode("to:\"" + to + "\"", StandardCharsets.UTF_8))
        .path("messages");
  }

  /** Waits for the message delivered to {@code to} and returns its full Mailpit view. */
  protected JsonNode awaitMessage(String to) {
    JsonNode[] found = new JsonNode[1];
    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .pollInterval(Duration.ofMillis(200))
        .until(() -> {
          JsonNode list = messagesTo(to);
          if (list.isEmpty()) {
            return false;
          }
          found[0] = mailpit("/api/v1/message/" + list.get(0).path("ID").asString());
          return true;
        });
    return found[0];
  }
}
