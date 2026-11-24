package com.vitadelta.api.adversarial;

import static com.vitadelta.api.adversarial.AttackClient.assertNoLeak;
import static com.vitadelta.api.adversarial.AttackClient.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.adversarial.AttackClient.Resp;
import com.vitadelta.api.identity.SecureTokens;
import com.vitadelta.api.support.TestEnvironment;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Section 15.7 case 4 (CSRF, login CSRF, CORS) and the cookie model of section 8.1: flags of {@code
 * vd_refresh} and {@code vd_anon}, forged {@code vd_anon} signatures, and the forgery guard of the
 * cookie-scoped auth endpoints reached through alternative spellings of their paths.
 */
class CsrfCookieAdversarialTest extends AdversarialIntegrationTest {

  private static final String EVIL = "https://evil.example";

  private String freshRefreshToken(String email) {
    return login(email, PASSWORD).refreshToken();
  }

  private boolean familyAlive(String refreshToken) {
    return count(
            "SELECT count(*) FROM refresh_token WHERE token_hash = ? AND revoked_at IS NULL",
            SecureTokens.sha256Hex(refreshToken))
        == 1;
  }

  @Test
  void refreshAndLogoutWithoutRequestedWithAreRejected() {
    String email = uniqueEmail();
    registerVerified(email);
    String token = freshRefreshToken(email);

    Resp refresh = req("POST", API + "/auth/refresh").cookie("vd_refresh", token).json("{}").send();
    assertProblem(refresh, 403, "forbidden");
    assertThat(refresh.setCookie("vd_refresh")).isEmpty();

    Resp wrongValue =
        req("POST", API + "/auth/refresh")
            .header("X-Requested-With", "XMLHttpRequest")
            .cookie("vd_refresh", token)
            .json("{}")
            .send();
    assertProblem(wrongValue, 403, "forbidden");

    Resp logout = req("POST", API + "/auth/logout").cookie("vd_refresh", token).json("{}").send();
    assertProblem(logout, 403, "forbidden");
    assertThat(familyAlive(token)).as("a forged logout must not revoke the session").isTrue();

    Resp form =
        req("POST", API + "/auth/refresh")
            .header("X-Requested-With", "vitadelta")
            .cookie("vd_refresh", token)
            .body("a=b", "application/x-www-form-urlencoded")
            .send();
    assertProblem(form, 403, "forbidden");
    assertThat(familyAlive(token)).isTrue();
  }

  @Test
  void loginCsrfThroughFormsTextOrForeignOriginsSetsNoSession() {
    String email = uniqueEmail();
    registerVerified(email);
    String form = "email=" + email.replace("@", "%40") + "&password=" + PASSWORD;
    List<Resp> attempts =
        List.of(
            req("POST", API + "/auth/login").body(form, "application/x-www-form-urlencoded").send(),
            req("POST", API + "/auth/login").body(form, "multipart/form-data; boundary=x").send(),
            req("POST", API + "/auth/login")
                .body(
                    "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}", "text/plain")
                .send(),
            req("POST", API + "/auth/login")
                .body("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}", null)
                .send(),
            req("POST", API + "/auth/login")
                .header("Origin", EVIL)
                .json(Map.of("email", email, "password", PASSWORD))
                .send(),
            req("POST", API + "/auth/login")
                .header("Origin", "null")
                .json(Map.of("email", email, "password", PASSWORD))
                .send(),
            req("POST", API + "/auth/login")
                .header("Origin", TestEnvironment.WEB_ORIGIN + ".evil.example")
                .json(Map.of("email", email, "password", PASSWORD))
                .send(),
            req("POST", API + "/auth/register")
                .header("Origin", EVIL)
                .json(Map.of("email", uniqueEmail(), "password", PASSWORD))
                .send());
    for (Resp attempt : attempts) {
      assertThat(attempt.status()).as(attempt.toString()).isEqualTo(403);
      assertThat(attempt.setCookie("vd_refresh")).as(attempt.toString()).isEmpty();
      assertThat(attempt.body()).doesNotContain("accessToken");
      assertNoLeak(attempt);
    }
    Resp allowed =
        req("POST", API + "/auth/login")
            .header("Origin", TestEnvironment.WEB_ORIGIN)
            .json(Map.of("email", email, "password", PASSWORD))
            .send();
    assertThat(allowed.status()).as(allowed.toString()).isEqualTo(200);
  }

  @Test
  void corsPreflightFromAForeignOriginFails() {
    for (String path : List.of(API + "/auth/login", API + "/auth/refresh", API + "/me")) {
      Resp preflight =
          req("OPTIONS", path)
              .header("Origin", EVIL)
              .header("Access-Control-Request-Method", "POST")
              .header("Access-Control-Request-Headers", "content-type,x-requested-with")
              .send();
      assertThat(preflight.status()).as("%s -> %s", path, preflight).isEqualTo(403);
      assertThat(preflight.header("access-control-allow-origin")).isNull();
      assertThat(preflight.header("access-control-allow-credentials")).isNull();
    }
    Resp foreignGet = req("GET", API + "/plans").header("Origin", EVIL).send();
    assertThat(foreignGet.header("access-control-allow-origin")).isNull();

    Resp allowed =
        req("OPTIONS", API + "/auth/login")
            .header("Origin", TestEnvironment.WEB_ORIGIN)
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "content-type")
            .send();
    assertThat(allowed.status()).isEqualTo(200);
    assertThat(allowed.header("access-control-allow-origin")).isEqualTo(TestEnvironment.WEB_ORIGIN);
    assertThat(allowed.header("access-control-allow-credentials")).isNull();
  }

  /**
   * The guard compares the raw request URI with a fixed set of paths, while request mapping matches
   * decoded paths. Every spelling that reaches the refresh or logout handler must still be subject
   * to the {@code X-Requested-With} check.
   */
  @Test
  void forgeryGuardCannotBeBypassedWithAlternativePathSpellings() {
    String email = uniqueEmail();
    registerVerified(email);
    List<String> refreshSpellings =
        List.of(
            "/api/v1/auth/%72efresh",
            "/api/v1/auth/refres%68",
            "/api/v1/%61uth/refresh",
            "/api/v1/auth/refresh/",
            "/api/v1/auth//refresh",
            "/api/v1/auth/./refresh",
            "/api/v1/auth/x/../refresh",
            "/api/v1/auth/refresh;a=b",
            "/api/v1/AUTH/refresh");
    List<String> bypasses = new java.util.ArrayList<>();
    for (String path : refreshSpellings) {
      String token = freshRefreshToken(email);
      Resp response =
          http.req("POST", path).ip(randomIp()).cookie("vd_refresh", token).json("{}").send();
      if (response.status() == 200
          || response.body().contains("accessToken")
          || !familyAlive(token)) {
        bypasses.add("POST " + path + " without X-Requested-With -> " + response.status());
      }
    }
    for (String path : List.of("/api/v1/auth/%6Cogout", "/api/v1/auth/logou%74")) {
      String token = freshRefreshToken(email);
      Resp response =
          http.req("POST", path).ip(randomIp()).cookie("vd_refresh", token).json("{}").send();
      if (!familyAlive(token)) {
        bypasses.add(
            "POST "
                + path
                + " without X-Requested-With revoked the session -> "
                + response.status());
      }
    }
    assertThat(bypasses).as("forgery-guard bypasses").isEmpty();
  }

  @Test
  void loginGuardCannotBeBypassedWithAnEncodedPath() {
    String email = uniqueEmail();
    registerVerified(email);
    for (String path : List.of("/api/v1/auth/%6Cogin", "/api/v1/auth/logi%6E")) {
      Resp response =
          http.req("POST", path)
              .ip(randomIp())
              .body("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}", "text/plain")
              .send();
      assertThat(response.setCookie("vd_refresh"))
          .as("POST %s as text/plain -> %s", path, response)
          .isEmpty();
      assertThat(response.status()).as(path).isNotEqualTo(200);
    }
  }

  @Test
  void refreshCookieFlags() {
    String email = uniqueEmail();
    registerVerified(email);
    Resp login = postJson(API + "/auth/login", Map.of("email", email, "password", PASSWORD));
    String cookie = login.setCookie("vd_refresh").orElseThrow();
    assertThat(cookie)
        .contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/v1/auth", "Max-Age=2592000")
        .doesNotContain("Domain=");
    assertThat(login.cookieValue("vd_refresh")).matches("^[A-Za-z0-9_-]{43}$");
    assertThat(login.body()).doesNotContain(login.cookieValue("vd_refresh"));

    Resp rotated = refresh(login.cookieValue("vd_refresh"));
    assertThat(rotated.setCookie("vd_refresh").orElseThrow())
        .contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/v1/auth");
    Resp logout =
        req("POST", API + "/auth/logout")
            .header("X-Requested-With", "vitadelta")
            .cookie("vd_refresh", rotated.cookieValue("vd_refresh"))
            .json("{}")
            .send();
    assertThat(logout.setCookie("vd_refresh").orElseThrow())
        .contains("Max-Age=0", "Path=/api/v1/auth", "HttpOnly", "Secure");
  }

  @Test
  void anonymousCookieFlagsAndForgedSignatures() {
    Resp first = get(API + "/session/quota", null);
    assertThat(first.status()).isEqualTo(200);
    String header = first.setCookie("vd_anon").orElseThrow();
    assertThat(header)
        .contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/", "Max-Age=15552000")
        .doesNotContain("Domain=");
    String cookie = first.cookieValue("vd_anon");
    String[] parts = cookie.split("\\.");
    assertThat(parts).hasSize(2);
    String idHash = SecureTokens.sha256Hex(SecureTokens.decode(parts[0]));
    jdbc.sql("UPDATE anonymous_identity SET queries_used = 2 WHERE id_hash = ?")
        .param(idHash)
        .update();

    Resp genuine = req("GET", API + "/session/quota").cookie("vd_anon", cookie).send();
    assertThat(genuine.json().get("queriesUsed").asInt()).isEqualTo(2);
    assertThat(genuine.setCookie("vd_anon")).isEmpty();

    char[] signature = parts[1].toCharArray();
    signature[10] = signature[10] == 'A' ? 'B' : 'A';
    List<String> forgeries =
        List.of(
            parts[0] + "." + new String(signature),
            parts[0] + ".",
            parts[0],
            parts[0] + "." + parts[1] + "." + parts[1],
            parts[0] + "=." + parts[1],
            SecureTokens.encode(SecureTokens.randomBytes(16)) + "." + parts[1],
            "' OR '1'='1",
            "x".repeat(3000));
    for (String forged : forgeries) {
      Resp response = req("GET", API + "/session/quota").cookie("vd_anon", forged).send();
      assertThat(response.status()).as(forged).isEqualTo(200);
      assertThat(response.json().get("queriesUsed").asInt()).as(forged).isZero();
      assertThat(response.cookieValue("vd_anon")).as(forged).isNotEqualTo(forged);
    }
    assertThat(count("SELECT queries_used FROM anonymous_identity WHERE id_hash = ?", idHash))
        .isEqualTo(2);
  }

  @Test
  void refreshCookieOfOneUserWithTheAccessTokenOfAnotherStaysWithTheCookieOwner() {
    String alice = uniqueEmail();
    String bob = uniqueEmail();
    registerVerified(alice);
    registerVerified(bob);
    Session aliceSession = login(alice, PASSWORD);
    Session bobSession = login(bob, PASSWORD);

    Resp mixed =
        req("POST", API + "/auth/refresh")
            .header("X-Requested-With", "vitadelta")
            .bearer(bobSession.accessToken())
            .cookie("vd_refresh", aliceSession.refreshToken())
            .json("{}")
            .send();
    assertThat(mixed.status()).as(mixed.toString()).isEqualTo(200);
    assertThat(mixed.json().get("user").get("email").asString()).isEqualTo(alice);

    Resp logout =
        req("POST", API + "/auth/logout")
            .header("X-Requested-With", "vitadelta")
            .bearer(aliceSession.accessToken())
            .cookie("vd_refresh", bobSession.refreshToken())
            .json("{}")
            .send();
    assertThat(logout.status()).isEqualTo(204);
    assertThat(familyAlive(mixed.cookieValue("vd_refresh"))).isTrue();
    assertThat(
            count(
                "SELECT count(*) FROM refresh_token WHERE user_id = ? AND revoked_at IS NULL",
                userId(bob)))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM refresh_token WHERE user_id = ? AND revoked_at IS NULL"
                    + " AND created_at <= ?",
                userId(alice),
                Timestamp.from(clock.instant())))
        .isGreaterThanOrEqualTo(1);
  }
}
