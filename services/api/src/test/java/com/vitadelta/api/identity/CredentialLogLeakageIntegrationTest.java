package com.vitadelta.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

/**
 * Log leakage guarantee of section 15.4 over a complete account life cycle served by the real
 * server: register, verify, login (also a failed one), refresh, forgot and reset password, promo
 * redemption and account deletion, with the application, web and security loggers at DEBUG. The
 * captured console output (structured JSON, as in production) contains no password, no e-mail
 * token, no access or refresh token, no full e-mail address and no promo code.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(
    properties = {
      "logging.level.com.vitadelta=DEBUG",
      "logging.level.org.springframework.web=DEBUG",
      "logging.level.org.springframework.security=DEBUG",
      // The test's own HTTP client logs the request bodies it sends; that is client-side output.
      "logging.level.org.springframework.web.client=INFO"
    })
class CredentialLogLeakageIntegrationTest extends IdentityIntegrationTest {

  private static final String NEW_PASSWORD = "another-correct-horse-battery";

  private EntityExchangeResult<String> send(
      HttpMethod method, String path, String body, String bearer) {
    return client
        .method(method)
        .uri(path)
        .contentType(MediaType.APPLICATION_JSON)
        .header("X-Forwarded-For", ip)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
        .body(body)
        .exchange()
        .returnResult(String.class);
  }

  @Test
  void noCredentialOrPersonalValueOfAFullAccountLifeCycleIsLogged(CapturedOutput output) {
    List<String> secrets = new ArrayList<>();
    String email = "leak.probe." + randomIp().replace('.', '-') + "@example.org";
    secrets.add(email);
    secrets.add(PASSWORD);
    secrets.add(NEW_PASSWORD);

    register(email, PASSWORD);
    String verifyToken = mail().awaitToken(email, 1);
    secrets.add(verifyToken);
    assertThat(
            post("/api/v1/auth/verify-email", body(Map.of("token", verifyToken)))
                .getStatus()
                .value())
        .isEqualTo(204);

    assertProblem(
        post("/api/v1/auth/login", body(Map.of("email", email, "password", "wrong-" + PASSWORD))),
        401,
        "invalid_credentials");
    Login session = login(email, PASSWORD);
    secrets.add(session.accessToken());
    secrets.add(session.refreshToken());
    EntityExchangeResult<String> rotated = refresh(session.refreshToken());
    assertThat(rotated.getStatus().value()).isEqualTo(200);
    String successor = cookieValue(setCookie(rotated, AuthCookies.REFRESH).orElseThrow());
    secrets.add(successor);
    secrets.add(json(rotated).get("accessToken").asString());
    // A replayed (reused) token outside the grace window, then a garbage cookie.
    clock.advance(java.time.Duration.ofMinutes(1));
    assertProblem(refresh(session.refreshToken()), 401, "refresh_reuse_detected");

    post("/api/v1/auth/forgot-password", body(Map.of("email", email)));
    String resetToken = mail().awaitToken(email, 2);
    secrets.add(resetToken);
    assertThat(
            post(
                    "/api/v1/auth/reset-password",
                    body(Map.of("token", resetToken, "newPassword", NEW_PASSWORD)))
                .getStatus()
                .value())
        .isEqualTo(204);
    Login again = login(email, NEW_PASSWORD);
    secrets.add(again.accessToken());
    secrets.add(again.refreshToken());

    String admin = adminToken();
    secrets.add(admin);
    EntityExchangeResult<String> created =
        send(
            HttpMethod.POST,
            "/api/v1/admin/promo-codes",
            body(Map.of("planCode", "PRO", "durationDays", 30, "maxRedemptions", 2)),
            admin);
    assertThat(created.getStatus().value()).as(created.getResponseBody()).isIn(200, 201);
    String promo = json(created).get("code").asString();
    secrets.add(promo);
    EntityExchangeResult<String> redeemed =
        send(
            HttpMethod.POST,
            "/api/v1/me/subscription/promo",
            body(Map.of("code", promo)),
            again.accessToken());
    assertThat(redeemed.getStatus().value()).as(redeemed.getResponseBody()).isEqualTo(200);
    // A second redemption is rejected (logged with its reason, never with the code).
    send(
        HttpMethod.POST,
        "/api/v1/me/subscription/promo",
        body(Map.of("code", promo)),
        again.accessToken());

    EntityExchangeResult<String> deleted =
        send(
            HttpMethod.DELETE,
            "/api/v1/me",
            body(Map.of("password", NEW_PASSWORD)),
            again.accessToken());
    assertThat(deleted.getStatus().value()).as(deleted.getResponseBody()).isEqualTo(204);

    String all = output.getAll();
    assertThat(all).as("the flow was logged").contains("Account deleted");
    for (String secret : secrets) {
      assertThat(all).as("a credential or personal value reached the log").doesNotContain(secret);
    }
    // Tokens are also absent in part (no prefix of a JWT or a refresh token is logged).
    assertThat(all).doesNotContain(session.accessToken().substring(0, 40));
    assertThat(all).doesNotContain(successor.substring(0, 20));
    assertThat(all).doesNotContain("leak.probe.");
  }
}
