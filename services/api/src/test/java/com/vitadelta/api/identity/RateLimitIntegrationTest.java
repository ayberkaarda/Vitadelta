package com.vitadelta.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

/**
 * Rate limits of section 8.2 per client IP (resolved with the trusted-proxy rule), per account and
 * per recipient.
 */
class RateLimitIntegrationTest extends IdentityIntegrationTest {

  private static void assertRateLimited(EntityExchangeResult<String> result) {
    assertProblem(result, 429, "rate_limited");
    assertThat(Long.parseLong(result.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER)))
        .isPositive();
  }

  private EntityExchangeResult<String> failedLogin(String email) {
    return post("/api/v1/auth/login", body(Map.of("email", email, "password", "wrong-password")));
  }

  @Test
  void loginIsLimitedPerClientIp() {
    String email = uniqueEmail();
    for (int i = 0; i < 10; i++) {
      assertThat(failedLogin(uniqueEmail()).getStatus().value()).isEqualTo(401);
    }
    assertRateLimited(failedLogin(email));
    String other = ip;
    ip = randomIp();
    assertThat(failedLogin(email).getStatus().value()).isEqualTo(401);
    ip = other;
    assertRateLimited(failedLogin(email));
  }

  @Test
  void theRightMostForwardedEntryIsTheClientIp() {
    String proxied = randomIp();
    ip = proxied;
    for (int i = 0; i < 10; i++) {
      failedLogin(uniqueEmail());
    }
    ip = "203.0.113.7, " + proxied;
    assertRateLimited(failedLogin(uniqueEmail()));
    ip = proxied + ", 203.0.113.7";
    assertThat(failedLogin(uniqueEmail()).getStatus().value()).isEqualTo(401);
  }

  @Test
  void registerAndEmailEndpointsAreLimitedPerClientIp() {
    for (int i = 0; i < 5; i++) {
      register(uniqueEmail(), PASSWORD);
    }
    assertRateLimited(
        post("/api/v1/auth/register", body(Map.of("email", uniqueEmail(), "password", PASSWORD))));

    ip = randomIp();
    for (int i = 0; i < 3; i++) {
      post("/api/v1/auth/forgot-password", body(Map.of("email", uniqueEmail())));
      post("/api/v1/auth/resend-verification", body(Map.of("email", uniqueEmail())));
    }
    assertRateLimited(post("/api/v1/auth/forgot-password", body(Map.of("email", uniqueEmail()))));

    ip = randomIp();
    for (int i = 0; i < 10; i++) {
      post("/api/v1/auth/verify-email", body(Map.of("token", SecureTokens.newToken())));
    }
    assertRateLimited(
        post(
            "/api/v1/auth/reset-password",
            body(Map.of("token", SecureTokens.newToken(), "newPassword", PASSWORD))));

    ip = randomIp();
    for (int i = 0; i < 60; i++) {
      refresh(SecureTokens.newToken());
    }
    assertRateLimited(refresh(SecureTokens.newToken()));
  }

  @Test
  void accountBackOffAppliesToAnySubmittedEmailAndResetsOnSuccess() {
    String unknown = uniqueEmail();
    for (int i = 0; i < 10; i++) {
      ip = randomIp();
      assertThat(failedLogin(unknown).getStatus().value()).isEqualTo(401);
    }
    ip = randomIp();
    EntityExchangeResult<String> blocked = failedLogin(unknown);
    assertRateLimited(blocked);
    assertThat(blocked.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("60");

    String email = uniqueEmail();
    registerVerified(email);
    for (int i = 0; i < 10; i++) {
      ip = randomIp();
      failedLogin(email);
    }
    ip = randomIp();
    assertRateLimited(
        post("/api/v1/auth/login", body(Map.of("email", email, "password", PASSWORD))));
    clock.advance(Duration.ofSeconds(61));
    ip = randomIp();
    assertThat(failedLogin(email).getStatus().value()).isEqualTo(401);
    ip = randomIp();
    assertThat(failedLogin(email).getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER))
        .isEqualTo("120");
    clock.advance(Duration.ofSeconds(121));
    ip = randomIp();
    login(email, PASSWORD);
    ip = randomIp();
    assertThat(failedLogin(email).getStatus().value()).isEqualTo(401);
  }

  @Test
  void recipientLimitSuppressesMailButStillAccepts() {
    String email = uniqueEmail();
    registerVerified(email);
    int before = mail().to(email).size();
    for (int i = 0; i < 3; i++) {
      ip = randomIp();
      EntityExchangeResult<String> result =
          post("/api/v1/auth/forgot-password", body(Map.of("email", email)));
      assertThat(result.getStatus().value()).isEqualTo(202);
    }
    mail().awaitMessages(email, before + 1);
    clock.advance(Duration.ofMinutes(10).plusSeconds(1));
    ip = randomIp();
    post("/api/v1/auth/forgot-password", body(Map.of("email", email)));
    assertThat(mail().awaitMessages(email, before + 2)).hasSize(before + 2);
  }
}
