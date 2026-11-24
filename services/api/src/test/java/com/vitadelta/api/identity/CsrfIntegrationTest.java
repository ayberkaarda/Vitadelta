package com.vitadelta.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

/**
 * Request-forgery rules of section 8.1: JSON content type and the Origin allow-list on login,
 * register, refresh and logout; {@code X-Requested-With: vitadelta} on refresh and logout.
 */
class CsrfIntegrationTest extends IdentityIntegrationTest {

  private EntityExchangeResult<String> form(String path, String body) {
    return client
        .post()
        .uri(path)
        .header("X-Forwarded-For", ip)
        .header("X-Requested-With", "vitadelta")
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body(body)
        .exchange()
        .returnResult(String.class);
  }

  @Test
  void crossSiteFormPostsAreRejected() {
    String email = uniqueEmail();
    registerVerified(email);
    String formBody = "email=" + email + "&password=" + PASSWORD;
    for (String path :
        new String[] {
          "/api/v1/auth/login",
          "/api/v1/auth/register",
          "/api/v1/auth/refresh",
          "/api/v1/auth/logout"
        }) {
      EntityExchangeResult<String> result = form(path, formBody);
      assertProblem(result, 403, "forbidden");
      assertThat(result.getResponseHeaders().containsHeader(HttpHeaders.SET_COOKIE)).isFalse();
    }
    EntityExchangeResult<String> textPlain =
        client
            .post()
            .uri("/api/v1/auth/login")
            .header("X-Forwarded-For", ip)
            .contentType(MediaType.TEXT_PLAIN)
            .body("{}")
            .exchange()
            .returnResult(String.class);
    assertProblem(textPlain, 403, "forbidden");
    EntityExchangeResult<String> noType =
        client
            .post()
            .uri("/api/v1/auth/logout")
            .header("X-Forwarded-For", ip)
            .header("X-Requested-With", "vitadelta")
            .exchange()
            .returnResult(String.class);
    assertProblem(noType, 403, "forbidden");
  }

  @Test
  void foreignOriginsAreRejectedAndTheWebOriginIsAccepted() {
    String email = uniqueEmail();
    registerVerified(email);
    String credentials = body(Map.of("email", email, "password", PASSWORD));
    EntityExchangeResult<String> foreign =
        post("/api/v1/auth/login", credentials, HttpHeaders.ORIGIN, "https://evil.example");
    assertThat(foreign.getStatus().value()).isEqualTo(403);
    assertThat(foreign.getResponseHeaders().containsHeader(HttpHeaders.SET_COOKIE)).isFalse();
    EntityExchangeResult<String> nullOrigin =
        post("/api/v1/auth/login", credentials, HttpHeaders.ORIGIN, "null");
    assertThat(nullOrigin.getStatus().value()).isEqualTo(403);

    EntityExchangeResult<String> allowed =
        post("/api/v1/auth/login", credentials, HttpHeaders.ORIGIN, testOrigin());
    assertThat(allowed.getStatus().value()).isEqualTo(200);
    EntityExchangeResult<String> sameOriginWithoutHeader = post("/api/v1/auth/login", credentials);
    assertThat(sameOriginWithoutHeader.getStatus().value()).isEqualTo(200);
  }

  @Test
  void refreshAndLogoutRequireTheRequestedWithHeader() {
    String email = uniqueEmail();
    registerVerified(email);
    Login session = login(email, PASSWORD);
    String cookie = AuthCookies.REFRESH + "=" + session.refreshToken();
    assertProblem(post("/api/v1/auth/refresh", "{}", HttpHeaders.COOKIE, cookie), 403, "forbidden");
    assertProblem(
        post(
            "/api/v1/auth/refresh",
            "{}",
            "X-Requested-With",
            "XMLHttpRequest",
            HttpHeaders.COOKIE,
            cookie),
        403,
        "forbidden");
    assertProblem(post("/api/v1/auth/logout", "{}", HttpHeaders.COOKIE, cookie), 403, "forbidden");
    assertThat(refresh(session.refreshToken()).getStatus().value()).isEqualTo(200);
  }
}
