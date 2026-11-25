package com.vitadelta.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;

/**
 * Register, verify, login, refresh rotation, grace window, reuse detection, logout, disabled
 * accounts and password reset (section 8.1 and 8.2), with contract checks of every response.
 */
@ExtendWith(OutputCaptureExtension.class)
class AuthFlowIntegrationTest extends IdentityIntegrationTest {

  @Autowired MeterRegistry meters;

  private double refreshRejections() {
    return meters.find(AuthService.REFRESH_REJECTED_METRIC).counters().stream()
        .mapToDouble(Counter::count)
        .sum();
  }

  @Test
  void registerVerifyLoginRefreshReuseLogout(CapturedOutput output) {
    String email = uniqueEmail();
    EntityExchangeResult<String> registered = register(email, PASSWORD);
    ContractSchemas.assertConforms("AcceptedMessage", registered.getResponseBody());
    assertThat(json(registered).get("messageKey").asString())
        .isEqualTo("auth.register.check_email");
    assertThat(setCookie(registered, AuthCookies.ANONYMOUS).orElseThrow()).contains("Max-Age=0");
    assertThat(registered.getResponseHeaders().get(HttpHeaders.CACHE_CONTROL))
        .containsExactly("no-store");

    String verifyToken = mail().awaitToken(email, 1);
    assertThat(mail().to(email).get(0).subject()).isNotBlank();

    Login unverified = login(email, PASSWORD);
    assertThat(unverified.body().get("user").get("emailVerified").asBoolean()).isFalse();
    assertThat(unverified.body().get("tokenType").asString()).isEqualTo("Bearer");
    assertThat(unverified.body().get("expiresIn").asInt()).isEqualTo(900);

    assertThat(
            post("/api/v1/auth/verify-email", body(Map.of("token", verifyToken)))
                .getStatus()
                .value())
        .isEqualTo(204);
    assertProblem(
        post("/api/v1/auth/verify-email", body(Map.of("token", verifyToken))),
        400,
        "token_invalid");

    Login session = login(email, PASSWORD);
    assertThat(session.body().get("user").get("emailVerified").asBoolean()).isTrue();
    EntityExchangeResult<String> refreshCookieResult =
        post("/api/v1/auth/login", body(Map.of("email", email, "password", PASSWORD)));
    String cookie = setCookie(refreshCookieResult, AuthCookies.REFRESH).orElseThrow();
    assertThat(cookie)
        .contains("HttpOnly", "SameSite=Strict", "Path=/api/v1/auth", "Max-Age=2592000", "Secure");

    EntityExchangeResult<String> rotated = refresh(session.refreshToken());
    assertThat(rotated.getStatus().value()).as(rotated.getResponseBody()).isEqualTo(200);
    ContractSchemas.assertConforms("AuthTokenResponse", rotated.getResponseBody());
    String successor = cookieValue(setCookie(rotated, AuthCookies.REFRESH).orElseThrow());
    assertThat(successor).isNotEqualTo(session.refreshToken());

    String next = cookieValue(setCookie(refresh(successor), AuthCookies.REFRESH).orElseThrow());
    clock.advance(Duration.ofSeconds(21));
    EntityExchangeResult<String> reused = refresh(session.refreshToken());
    assertProblem(reused, 401, "refresh_reuse_detected");
    assertThat(setCookie(reused, AuthCookies.REFRESH).orElseThrow()).contains("Max-Age=0");
    assertProblem(refresh(next), 401, "refresh_reuse_detected");

    Login again = login(email, PASSWORD);
    EntityExchangeResult<String> logout =
        post(
            "/api/v1/auth/logout",
            "{}",
            "X-Requested-With",
            "vitadelta",
            HttpHeaders.COOKIE,
            AuthCookies.REFRESH + "=" + again.refreshToken());
    assertThat(logout.getStatus().value()).isEqualTo(204);
    assertThat(setCookie(logout, AuthCookies.REFRESH).orElseThrow())
        .contains("Max-Age=0", "Path=/api/v1/auth");
    assertThat(refresh(again.refreshToken()).getStatus().value()).isEqualTo(401);
    assertThat(
            post("/api/v1/auth/logout", "{}", "X-Requested-With", "vitadelta").getStatus().value())
        .isEqualTo(204);

    assertThat(output.getAll())
        .doesNotContain(PASSWORD)
        .doesNotContain(email)
        .doesNotContain(verifyToken)
        .doesNotContain(session.refreshToken())
        .doesNotContain(successor)
        .doesNotContain(session.accessToken());
  }

  @Test
  void graceWindowReturnsTheSameSuccessorAndIsReuseAfterwards() {
    String email = uniqueEmail();
    registerVerified(email);
    Login session = login(email, PASSWORD);

    EntityExchangeResult<String> first = refresh(session.refreshToken());
    String successor = cookieValue(setCookie(first, AuthCookies.REFRESH).orElseThrow());
    clock.advance(Duration.ofSeconds(19));
    EntityExchangeResult<String> second = refresh(session.refreshToken());
    assertThat(second.getStatus().value()).isEqualTo(200);
    assertThat(cookieValue(setCookie(second, AuthCookies.REFRESH).orElseThrow()))
        .isEqualTo(successor);
    assertThat(json(second).get("accessToken").asString())
        .isNotEqualTo(json(first).get("accessToken").asString());

    clock.advance(Duration.ofSeconds(2));
    assertProblem(refresh(session.refreshToken()), 401, "refresh_reuse_detected");
    assertProblem(refresh(successor), 401, "refresh_reuse_detected");
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM refresh_token WHERE user_id = ? AND"
                        + " (revoked_at IS NULL OR grace_successor_enc IS NOT NULL)")
                .param(userId(email))
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void graceWindowEndsWhenTheSuccessorWasRotatedItself() {
    String email = uniqueEmail();
    registerVerified(email);
    Login session = login(email, PASSWORD);
    String successor =
        cookieValue(setCookie(refresh(session.refreshToken()), AuthCookies.REFRESH).orElseThrow());
    assertThat(refresh(successor).getStatus().value()).isEqualTo(200);
    clock.advance(Duration.ofSeconds(1));
    assertProblem(refresh(session.refreshToken()), 401, "refresh_reuse_detected");
  }

  @Test
  void concurrentRefreshesWithOneTokenAllGetTheSameSuccessor() throws Exception {
    String email = uniqueEmail();
    registerVerified(email);
    Login session = login(email, PASSWORD);
    java.util.List<java.util.concurrent.Callable<EntityExchangeResult<String>>> calls =
        java.util.stream.IntStream.range(0, 5)
            .<java.util.concurrent.Callable<EntityExchangeResult<String>>>mapToObj(
                i -> () -> refresh(session.refreshToken()))
            .toList();
    try (java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(5)) {
      java.util.Set<String> successors = new java.util.HashSet<>();
      for (java.util.concurrent.Future<EntityExchangeResult<String>> future :
          pool.invokeAll(calls)) {
        EntityExchangeResult<String> result = future.get();
        assertThat(result.getStatus().value()).isEqualTo(200);
        successors.add(cookieValue(setCookie(result, AuthCookies.REFRESH).orElseThrow()));
      }
      assertThat(successors).hasSize(1);
    }
  }

  @Test
  void expiredAndUnknownRefreshTokensAreUnauthorized() {
    double rejectedBefore = refreshRejections();
    assertProblem(refresh(SecureTokens.newToken()), 401, "unauthorized");
    EntityExchangeResult<String> garbage = refresh("not-a-token");
    assertProblem(garbage, 401, "unauthorized");
    assertThat(setCookie(garbage, AuthCookies.REFRESH).orElseThrow()).contains("Max-Age=0");
    String email = uniqueEmail();
    registerVerified(email);
    Login session = login(email, PASSWORD);
    clock.advance(RefreshTokenService.LIFETIME.plusSeconds(1));
    assertProblem(refresh(session.refreshToken()), 401, "unauthorized");
    assertThat(refreshRejections()).as("counted rejections").isEqualTo(rejectedBefore + 3);
    assertThat(meters.find(AuthService.REFRESH_REJECTED_METRIC).counters())
        .allSatisfy(c ->
            assertThat(c.getId().getTag("reason"))
                .isIn("UNKNOWN", "EXPIRED", "REUSED", "DISABLED"));
  }

  /**
   * No session cookie is the normal state of an anonymous visitor: {@code 204} without a body and
   * without {@code Set-Cookie}, not a failed attempt. The forgery checks and the per-IP limit still
   * run first.
   */
  @Test
  void aRefreshWithoutSessionCookieIsNoContentAfterTheForgeryAndRateChecks() {
    double rejectedBefore = refreshRejections();
    for (EntityExchangeResult<String> anonymous :
        List.of(
            post("/api/v1/auth/refresh", "{}", "X-Requested-With", "vitadelta"),
            post(
                "/api/v1/auth/refresh",
                "{}",
                "X-Requested-With",
                "vitadelta",
                HttpHeaders.COOKIE,
                AuthCookies.REFRESH + "="),
            post(
                "/api/v1/auth/refresh",
                "{}",
                "X-Requested-With",
                "vitadelta",
                HttpHeaders.COOKIE,
                "vd_other=value"))) {
      assertThat(anonymous.getStatus().value()).as(anonymous.getResponseBody()).isEqualTo(204);
      assertThat(anonymous.getResponseBody()).isNullOrEmpty();
      assertThat(anonymous.getResponseHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
      assertThat(anonymous.getResponseHeaders().getFirst("X-Request-Id")).isNotBlank();
      assertThat(anonymous.getResponseHeaders().get(HttpHeaders.CACHE_CONTROL))
          .containsExactly("no-store");
    }
    assertThat(refreshRejections()).as("not counted as a failed refresh").isEqualTo(rejectedBefore);

    // The forgery guard still answers first.
    assertProblem(post("/api/v1/auth/refresh", "{}"), 403, "forbidden");

    // The per-IP limit (60 per minute) still counts requests without a cookie.
    ip = randomIp();
    int passed = 0;
    EntityExchangeResult<String> limited = null;
    for (int i = 0; i < 200 && limited == null; i++) {
      EntityExchangeResult<String> result =
          post("/api/v1/auth/refresh", "{}", "X-Requested-With", "vitadelta");
      if (result.getStatus().value() == 429) {
        limited = result;
      } else {
        assertThat(result.getStatus().value()).isEqualTo(204);
        passed++;
      }
    }
    assertThat(passed).isGreaterThanOrEqualTo(60);
    assertThat(limited).isNotNull();
    assertProblem(limited, 429, "rate_limited");
  }

  @Test
  void disabledUsersCannotRefreshAndDisablingRevokesEveryFamily() {
    String email = uniqueEmail();
    UUID id = registerVerified(email);
    Login first = login(email, PASSWORD);
    Login second = login(email, PASSWORD);
    String admin = adminToken();

    EntityExchangeResult<String> disabled =
        post(
            "/api/v1/admin/users/" + id + "/status",
            body(Map.of("status", "DISABLED")),
            HttpHeaders.AUTHORIZATION,
            "Bearer " + admin);
    assertThat(disabled.getStatus().value()).as(disabled.getResponseBody()).isEqualTo(200);
    ContractSchemas.assertConforms("AdminUser", disabled.getResponseBody());
    assertThat(json(disabled).get("status").asString()).isEqualTo("DISABLED");
    assertThat(
            jdbc.sql("SELECT count(*) FROM refresh_token WHERE user_id = ? AND revoked_at IS NULL")
                .param(id)
                .query(Long.class)
                .single())
        .isZero();

    assertProblem(refresh(first.refreshToken()), 403, "account_disabled");
    assertProblem(refresh(second.refreshToken()), 403, "account_disabled");
    assertProblem(
        post("/api/v1/auth/login", body(Map.of("email", email, "password", PASSWORD))),
        403,
        "account_disabled");
    assertProblem(
        post("/api/v1/auth/login", body(Map.of("email", email, "password", "wrong-password-1"))),
        401,
        "invalid_credentials");

    post(
        "/api/v1/admin/users/" + id + "/status",
        body(Map.of("status", "ACTIVE")),
        HttpHeaders.AUTHORIZATION,
        "Bearer " + admin);
    login(email, PASSWORD);
  }

  @Test
  void resetPasswordRevokesFamiliesAndInvalidatesOlderResetTokens() {
    String email = uniqueEmail();
    UUID id = registerVerified(email);
    Login session = login(email, PASSWORD);

    EntityExchangeResult<String> forgot =
        post("/api/v1/auth/forgot-password", body(Map.of("email", email)));
    assertThat(forgot.getStatus().value()).isEqualTo(202);
    ContractSchemas.assertConforms("AcceptedMessage", forgot.getResponseBody());
    String older = mail().awaitToken(email, 2);
    String newer = issueResetToken(id);

    assertProblem(
        post(
            "/api/v1/auth/reset-password",
            body(Map.of("token", older, "newPassword", "another-long-password-2"))),
        400,
        "token_invalid");
    assertProblem(
        post(
            "/api/v1/auth/reset-password",
            body(Map.of("token", newer, "newPassword", "1234567890"))),
        422,
        "validation_failed");
    EntityExchangeResult<String> reset =
        post(
            "/api/v1/auth/reset-password",
            body(Map.of("token", newer, "newPassword", "another-long-password-2")));
    assertThat(reset.getStatus().value()).as(reset.getResponseBody()).isEqualTo(204);
    assertProblem(
        post(
            "/api/v1/auth/reset-password",
            body(Map.of("token", newer, "newPassword", "another-long-password-3"))),
        400,
        "token_invalid");

    assertProblem(refresh(session.refreshToken()), 401, "refresh_reuse_detected");
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM email_token WHERE user_id = ? AND kind ="
                        + " 'RESET_PASSWORD' AND used_at IS NULL")
                .param(id)
                .query(Long.class)
                .single())
        .isZero();
    assertProblem(
        post("/api/v1/auth/login", body(Map.of("email", email, "password", PASSWORD))),
        401,
        "invalid_credentials");
    login(email, "another-long-password-2");
  }

  /** A second reset token through the service, as a later forgot-password request would issue. */
  private String issueResetToken(UUID userId) {
    return transactionally(
        () -> emailTokens.issue(userId, EmailTokenService.Kind.RESET_PASSWORD, clock.instant()));
  }

  @org.springframework.beans.factory.annotation.Autowired EmailTokenService emailTokens;

  @org.springframework.beans.factory.annotation.Autowired
  org.springframework.transaction.PlatformTransactionManager transactionManager;

  private <T> T transactionally(java.util.function.Supplier<T> work) {
    return new org.springframework.transaction.support.TransactionTemplate(transactionManager)
        .execute(status -> work.get());
  }

  @Test
  void emailTokensExpire() {
    String email = uniqueEmail();
    register(email, PASSWORD);
    String verify = mail().awaitToken(email, 1);
    clock.advance(Duration.ofHours(24).plusSeconds(1));
    assertProblem(
        post("/api/v1/auth/verify-email", body(Map.of("token", verify))), 400, "token_expired");

    EntityExchangeResult<String> forgot =
        post("/api/v1/auth/forgot-password", body(Map.of("email", email)));
    assertThat(forgot.getStatus().value()).isEqualTo(202);
    String reset = mail().awaitToken(email, 2);
    clock.advance(Duration.ofHours(1).plusSeconds(1));
    assertProblem(
        post(
            "/api/v1/auth/reset-password",
            body(Map.of("token", reset, "newPassword", "another-long-password-2"))),
        400,
        "token_expired");
    assertProblem(
        post("/api/v1/auth/verify-email", body(Map.of("token", "@@@"))), 422, "validation_failed");
  }

  @Test
  void resendingVerificationInvalidatesOlderTokens() {
    String email = uniqueEmail();
    register(email, PASSWORD);
    String first = mail().awaitToken(email, 1);
    clock.advance(Duration.ofMinutes(11));
    EntityExchangeResult<String> resent =
        post("/api/v1/auth/resend-verification", body(Map.of("email", email)));
    assertThat(resent.getStatus().value()).isEqualTo(202);
    ContractSchemas.assertConforms("AcceptedMessage", resent.getResponseBody());
    assertThat(json(resent).get("messageKey").asString()).isEqualTo("auth.verify.resent");
    String second = mail().awaitToken(email, 2);
    assertThat(second).isNotEqualTo(first);
    assertProblem(
        post("/api/v1/auth/verify-email", body(Map.of("token", first))), 400, "token_invalid");
    assertThat(post("/api/v1/auth/verify-email", body(Map.of("token", second))).getStatus().value())
        .isEqualTo(204);
  }

  @Test
  void registrationValidatesPasswordEmailAndCountry() {
    String email = uniqueEmail();
    EntityExchangeResult<String> shortPassword =
        post("/api/v1/auth/register", body(Map.of("email", email, "password", "short")));
    assertProblem(shortPassword, 422, "validation_failed");
    assertThat(json(shortPassword).get("errors").get(0).get("code").asString())
        .isEqualTo("too_short");
    EntityExchangeResult<String> breached =
        post("/api/v1/auth/register", body(Map.of("email", email, "password", "1234567890")));
    assertProblem(breached, 422, "validation_failed");
    assertThat(json(breached).get("errors").get(0).get("code").asString())
        .isEqualTo("breached_password");
    assertProblem(
        post("/api/v1/auth/register", body(Map.of("email", "not-an-email", "password", PASSWORD))),
        422,
        "validation_failed");
    assertProblem(
        post(
            "/api/v1/auth/register",
            body(Map.of("email", email, "password", PASSWORD, "countryCode", missingCountry()))),
        422,
        "country_not_found");
    assertProblem(
        post(
            "/api/v1/auth/register",
            body(Map.of("email", email, "password", PASSWORD, "locale", "de"))),
        422,
        "validation_failed");

    post(
        "/api/v1/auth/register",
        body(
            Map.of(
                "email",
                email.toUpperCase(java.util.Locale.ROOT),
                "password",
                PASSWORD,
                "countryCode",
                testCountry())));
    JsonNode stored =
        JSON.readTree(
            jdbc.sql(
                    "SELECT json_build_object('email', email, 'locale', locale, 'country',"
                        + " default_country_code)::text FROM app_user WHERE email = CAST(? AS citext)")
                .param(email)
                .query(String.class)
                .single());
    assertThat(stored.get("email").asString()).isEqualTo(email);
    assertThat(stored.get("locale").asString()).isEqualTo("tr");
    assertThat(stored.get("country").asString()).isEqualTo(TEST_COUNTRY);
    assertThat(
            jdbc.sql(
                    "SELECT plan_code || '/' || status || '/' || source FROM subscription"
                        + " WHERE user_id = ?")
                .param(userId(email))
                .query(String.class)
                .single())
        .isEqualTo("FREE/ACTIVE/MANUAL");
    assertThat(mail().awaitMessages(email, 1).get(0).locale().getLanguage()).isEqualTo("tr");
  }
}
