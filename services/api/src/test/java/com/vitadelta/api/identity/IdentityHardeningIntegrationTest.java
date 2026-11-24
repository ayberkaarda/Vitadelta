package com.vitadelta.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

/**
 * Regression tests of the security review of the identity package: serialization of token, login,
 * deletion and settings changes under the user-row lock, path spellings of the forgery guard, the
 * anonymous-identity creation limit, grace-successor clean-up, parallel login attempts and control
 * characters in free-text inputs.
 *
 * <p>Concurrent writers are simulated with a raw JDBC transaction that holds row locks the way the
 * application does; the request under test is started while that transaction is open, the test
 * waits until the request blocks on a lock and then commits.
 */
class IdentityHardeningIntegrationTest extends IdentityIntegrationTest {

  @Autowired DataSource dataSource;
  @Autowired PasswordHasher hasher;

  private void awaitRequestBlockedOnLock() {
    Awaitility.await()
        .atMost(Duration.ofSeconds(15))
        .until(() ->
            jdbc.sql(
                        "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                            + " AND datname = current_database()")
                    .query(Long.class)
                    .single()
                > 0);
  }

  private static void execute(Connection connection, String sql, Object... params)
      throws Exception {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        statement.setObject(i + 1, params[i]);
      }
      statement.execute();
    }
  }

  private UUID tokenId(String token) {
    return jdbc.sql("SELECT id FROM refresh_token WHERE token_hash = ?")
        .param(SecureTokens.sha256Hex(token))
        .query(UUID.class)
        .single();
  }

  private UUID familyId(String token) {
    return jdbc.sql("SELECT family_id FROM refresh_token WHERE token_hash = ?")
        .param(SecureTokens.sha256Hex(token))
        .query(UUID.class)
        .single();
  }

  /**
   * Opens a transaction that rotates {@code token} the way {@link RefreshTokenService} does (user
   * row lock, token row lock, successor insert, predecessor update) and leaves it uncommitted.
   *
   * @return the successor token value
   */
  private String beginConcurrentRotation(Connection connection, UUID userId, String token)
      throws Exception {
    connection.setAutoCommit(false);
    execute(connection, "SELECT id FROM app_user WHERE id = ? FOR UPDATE", userId);
    UUID predecessor = tokenId(token);
    execute(connection, "SELECT id FROM refresh_token WHERE id = ? FOR UPDATE", predecessor);
    String successor = SecureTokens.newToken();
    UUID successorId = UUID.randomUUID();
    execute(
        connection,
        "INSERT INTO refresh_token (id, user_id, token_hash, family_id, expires_at, created_at)"
            + " VALUES (?, ?, ?, ?, now() + interval '30 days', now())",
        successorId,
        userId,
        SecureTokens.sha256Hex(successor),
        familyId(token));
    execute(
        connection,
        "UPDATE refresh_token SET revoked_at = now(), replaced_by = ? WHERE id = ?",
        successorId,
        predecessor);
    return successor;
  }

  private boolean revoked(String token) {
    return jdbc.sql("SELECT revoked_at IS NOT NULL FROM refresh_token WHERE token_hash = ?")
        .param(SecureTokens.sha256Hex(token))
        .query(Boolean.class)
        .single();
  }

  // Item 1: family revocation versus a concurrent rotation

  @Test
  void logoutRevokesASuccessorCreatedByAConcurrentRotation() throws Exception {
    String email = uniqueEmail();
    UUID id = registerVerified(email);
    Login session = login(email, PASSWORD);
    String successor;
    CompletableFuture<EntityExchangeResult<String>> logout;
    try (Connection connection = dataSource.getConnection()) {
      successor = beginConcurrentRotation(connection, id, session.refreshToken());
      logout =
          CompletableFuture.supplyAsync(() ->
              post(
                  "/api/v1/auth/logout",
                  "{}",
                  "X-Requested-With",
                  "vitadelta",
                  HttpHeaders.COOKIE,
                  AuthCookies.REFRESH + "=" + session.refreshToken()));
      awaitRequestBlockedOnLock();
      connection.commit();
    }
    assertThat(logout.get().getStatus().value()).isEqualTo(204);
    assertThat(revoked(successor)).as("successor of the concurrent rotation").isTrue();
  }

  @Test
  void reuseDetectionRevokesASuccessorCreatedByAConcurrentRotation() throws Exception {
    String email = uniqueEmail();
    UUID id = registerVerified(email);
    Login session = login(email, PASSWORD);
    String current =
        cookieValue(setCookie(refresh(session.refreshToken()), AuthCookies.REFRESH).orElseThrow());
    clock.advance(Duration.ofSeconds(21));
    String successor;
    CompletableFuture<EntityExchangeResult<String>> reuse;
    try (Connection connection = dataSource.getConnection()) {
      successor = beginConcurrentRotation(connection, id, current);
      reuse = CompletableFuture.supplyAsync(() -> refresh(session.refreshToken()));
      awaitRequestBlockedOnLock();
      connection.commit();
    }
    assertProblem(reuse.get(), 401, "refresh_reuse_detected");
    assertThat(revoked(successor)).as("successor of the concurrent rotation").isTrue();
  }

  // Item 2: login re-validates the account under the user-row lock

  @Test
  void loginRacingADisableDoesNotIssueASession() throws Exception {
    String email = uniqueEmail();
    UUID id = registerVerified(email);
    CompletableFuture<EntityExchangeResult<String>> login;
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      execute(connection, "UPDATE app_user SET status = 'DISABLED' WHERE id = ?", id);
      login =
          CompletableFuture.supplyAsync(
              () -> post("/api/v1/auth/login", body(Map.of("email", email, "password", PASSWORD))));
      awaitRequestBlockedOnLock();
      connection.commit();
    }
    assertProblem(login.get(), 403, "account_disabled");
    assertThat(
            jdbc.sql("SELECT count(*) FROM refresh_token WHERE user_id = ?")
                .param(id)
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void loginRacingAPasswordChangeDoesNotIssueASession() throws Exception {
    String email = uniqueEmail();
    UUID id = registerVerified(email);
    String changed = hasher.hash("a-completely-different-passphrase");
    CompletableFuture<EntityExchangeResult<String>> login;
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      execute(connection, "UPDATE app_user SET password_hash = ? WHERE id = ?", changed, id);
      login =
          CompletableFuture.supplyAsync(
              () -> post("/api/v1/auth/login", body(Map.of("email", email, "password", PASSWORD))));
      awaitRequestBlockedOnLock();
      connection.commit();
    }
    assertProblem(login.get(), 401, "invalid_credentials");
    assertThat(
            jdbc.sql("SELECT count(*) FROM refresh_token WHERE user_id = ?")
                .param(id)
                .query(Long.class)
                .single())
        .isZero();
  }

  // Item 3: account deletion verifies the current hash under the lock

  @Test
  void deletionVerifiesTheCurrentPasswordUnderTheLock() throws Exception {
    String email = uniqueEmail();
    UUID id = registerVerified(email);
    String token = login(email, PASSWORD).accessToken();
    String changed = hasher.hash("a-completely-different-passphrase");
    CompletableFuture<EntityExchangeResult<String>> deletion;
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      execute(connection, "UPDATE app_user SET password_hash = ? WHERE id = ?", changed, id);
      deletion =
          CompletableFuture.supplyAsync(() ->
              client
                  .method(org.springframework.http.HttpMethod.DELETE)
                  .uri("/api/v1/me")
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .body(body(Map.of("password", PASSWORD)))
                  .exchange()
                  .returnResult(String.class));
      awaitRequestBlockedOnLock();
      connection.commit();
    }
    assertProblem(deletion.get(), 401, "invalid_credentials");
    assertThat(
            jdbc.sql("SELECT count(*) FROM app_user WHERE id = ?")
                .param(id)
                .query(Long.class)
                .single())
        .isOne();
  }

  // Item 10: PATCH /me never loses a concurrent update of another field

  @Test
  void concurrentSettingsUpdatesOfDifferentFieldsAreBothKept() throws Exception {
    String email = uniqueEmail();
    UUID id = registerVerified(email);
    String token = login(email, PASSWORD).accessToken();
    CompletableFuture<EntityExchangeResult<String>> patch;
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      execute(connection, "UPDATE app_user SET email_alerts_enabled = false WHERE id = ?", id);
      patch =
          CompletableFuture.supplyAsync(() ->
              client
                  .patch()
                  .uri("/api/v1/me")
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .body("{\"locale\": \"tr\"}")
                  .exchange()
                  .returnResult(String.class));
      awaitRequestBlockedOnLock();
      connection.commit();
    }
    assertThat(patch.get().getStatus().value()).isEqualTo(200);
    Map<String, Object> row =
        jdbc.sql("SELECT locale, email_alerts_enabled FROM app_user WHERE id = ?")
            .param(id)
            .query()
            .singleRow();
    assertThat(row).containsEntry("locale", "tr").containsEntry("email_alerts_enabled", false);
    assertThat(json(patch.get()).get("emailAlertsEnabled").asBoolean()).isFalse();
  }

  // Item 4: every path spelling that reaches the handler is guarded

  private EntityExchangeResult<String> rawPost(String rawPath, String refreshToken) {
    return client
        .post()
        .uri(URI.create("http://localhost:" + port + rawPath))
        .header("X-Forwarded-For", randomIp())
        .contentType(MediaType.APPLICATION_JSON)
        .cookie(AuthCookies.REFRESH, refreshToken)
        .body("{}")
        .exchange()
        .returnResult(String.class);
  }

  @Test
  void theForgeryGuardCoversEveryPathSpellingOfTheHandler() {
    String email = uniqueEmail();
    registerVerified(email);
    List<String> refreshSpellings =
        List.of(
            "/api/v1/auth/%72efresh",
            "/api/v1/auth/refres%68",
            "/api/v1/%61uth/refresh",
            "/api/v1/auth/refresh/",
            "/api/v1/AUTH/refresh",
            "/api/v1/auth/refresh;a=b");
    for (String path : refreshSpellings) {
      Login session = login(email, PASSWORD);
      EntityExchangeResult<String> result = rawPost(path, session.refreshToken());
      assertThat(result.getStatus().value()).as(path).isNotEqualTo(200);
      assertThat(revoked(session.refreshToken())).as(path).isFalse();
    }
    for (String path : List.of("/api/v1/auth/%6Cogout", "/api/v1/auth/logou%74")) {
      Login session = login(email, PASSWORD);
      EntityExchangeResult<String> result = rawPost(path, session.refreshToken());
      assertThat(result.getStatus().value()).as(path).isNotEqualTo(204);
      assertThat(revoked(session.refreshToken())).as(path).isFalse();
    }
    EntityExchangeResult<String> encodedLogin =
        client
            .post()
            .uri(URI.create("http://localhost:" + port + "/api/v1/auth/%6Cogin"))
            .header("X-Forwarded-For", randomIp())
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body("email=" + email + "&password=" + PASSWORD)
            .exchange()
            .returnResult(String.class);
    assertThat(encodedLogin.getStatus().value()).isEqualTo(403);
  }

  // Item 5: anonymous identities are created at most 60 times per hour per IP

  private EntityExchangeResult<String> quota(String cookie) {
    var spec = client.get().uri("/api/v1/session/quota").header("X-Forwarded-For", ip);
    if (cookie != null) {
      spec = spec.cookie(AuthCookies.ANONYMOUS, cookie);
    }
    return spec.exchange().returnResult(String.class);
  }

  @Test
  void anonymousIdentityCreationIsLimitedPerIp() {
    String cookie = null;
    for (int i = 0; i < 60; i++) {
      EntityExchangeResult<String> created = quota(null);
      assertThat(created.getStatus().value()).as("creation %d", i + 1).isEqualTo(200);
      cookie = cookieValue(setCookie(created, AuthCookies.ANONYMOUS).orElseThrow());
    }
    EntityExchangeResult<String> limited = quota(null);
    assertProblem(limited, 429, "rate_limited");
    assertThat(Long.parseLong(limited.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER)))
        .isPositive();
    assertProblem(quota(cookie.substring(0, cookie.length() - 2) + "AA"), 429, "rate_limited");
    EntityExchangeResult<String> existing = quota(cookie);
    assertThat(existing.getStatus().value()).isEqualTo(200);
    assertThat(existing.getResponseHeaders().containsHeader(HttpHeaders.SET_COOKIE)).isFalse();
  }

  // Item 6: grace successors are cleared once the window has passed

  @Test
  void expiredGraceSuccessorsAreClearedOnTheNextRotationOfTheUser() {
    String email = uniqueEmail();
    registerVerified(email);
    Login session = login(email, PASSWORD);
    String second =
        cookieValue(setCookie(refresh(session.refreshToken()), AuthCookies.REFRESH).orElseThrow());
    clock.advance(RefreshTokenService.GRACE_WINDOW.plusSeconds(1));
    assertThat(refresh(second).getStatus().value()).isEqualTo(200);
    assertThat(
            jdbc.sql("SELECT grace_successor_enc IS NULL FROM refresh_token WHERE token_hash = ?")
                .param(SecureTokens.sha256Hex(session.refreshToken()))
                .query(Boolean.class)
                .single())
        .isTrue();
  }

  // Item 7: parallel attempts for one e-mail reserve the failure budget atomically

  @Test
  void parallelWrongPasswordAttemptsReachVerificationAtMostTenTimes() throws Exception {
    String email = uniqueEmail();
    registerVerified(email);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Integer>> results = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(30)) {
      for (int i = 0; i < 30; i++) {
        String clientIp = randomIp();
        results.add(
            pool.submit(() -> {
              start.await();
              return client
                  .post()
                  .uri("/api/v1/auth/login")
                  .contentType(MediaType.APPLICATION_JSON)
                  .header("X-Forwarded-For", clientIp)
                  .body(body(Map.of("email", email, "password", "wrong-password-value")))
                  .exchange()
                  .returnResult(String.class)
                  .getStatus()
                  .value();
            }));
      }
      start.countDown();
      int verified = 0;
      for (Future<Integer> result : results) {
        int status = result.get();
        assertThat(status).isIn(401, 429);
        if (status == 401) {
          verified++;
        }
      }
      assertThat(verified).isLessThanOrEqualTo(LoginThrottle.MAX_FAILURES).isPositive();
    }
  }

  // Item 11: control characters in free-text inputs are validation errors, never 500

  @Test
  void controlCharactersInFreeTextInputsAreRejectedWith422() {
    String admin = adminToken();
    for (String query : List.of("%00x", "a%0Ab", "%00%27%20OR%201%3D1--")) {
      EntityExchangeResult<String> search =
          client
              .get()
              .uri(URI.create("http://localhost:" + port + "/api/v1/admin/users?q=" + query))
              .header("X-Forwarded-For", ip)
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
              .exchange()
              .returnResult(String.class);
      assertProblem(search, 422, "validation_failed");
    }
    String nulEmail = "nul\\u0000user@example.org";
    for (String path :
        List.of(
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/resend-verification")) {
      ip = randomIp();
      EntityExchangeResult<String> result =
          post(path, "{\"email\": \"" + nulEmail + "\", \"password\": \"" + PASSWORD + "\"}");
      assertProblem(result, 422, "validation_failed");
    }
    ip = randomIp();
    assertProblem(
        post("/api/v1/auth/verify-email", "{\"token\": \"abc\\u0000def\"}"),
        422,
        "validation_failed");
    assertProblem(
        post(
            "/api/v1/auth/reset-password",
            "{\"token\": \"abc\\u0000def\", \"newPassword\": \"" + PASSWORD + "\"}"),
        422,
        "validation_failed");
    String email = uniqueEmail();
    registerVerified(email);
    String token = login(email, PASSWORD).accessToken();
    for (String settings :
        List.of(
            "{\"locale\": \"t\\u0000\"}",
            "{\"defaultCountryCode\": \"X\\u0000\"}",
            "{\"displayCurrency\": \"US\\u0000\"}")) {
      EntityExchangeResult<String> result =
          client
              .patch()
              .uri("/api/v1/me")
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
              .contentType(MediaType.APPLICATION_JSON)
              .body(settings)
              .exchange()
              .returnResult(String.class);
      assertProblem(result, 422, "validation_failed");
    }
  }
}
