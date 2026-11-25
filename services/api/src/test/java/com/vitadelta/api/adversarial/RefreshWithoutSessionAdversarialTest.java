package com.vitadelta.api.adversarial;

import static com.vitadelta.api.adversarial.AttackClient.assertApiSecurityHeaders;
import static com.vitadelta.api.adversarial.AttackClient.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.adversarial.AttackClient.Req;
import com.vitadelta.api.adversarial.AttackClient.Resp;
import com.vitadelta.api.identity.SecureTokens;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The cookie-less {@code POST /auth/refresh} (contract: {@code 204} when no {@code vd_refresh}
 * cookie or only an empty one is sent) attacked as a session oracle and as a bypass: the forgery
 * guard and the per-IP limit still run first, a garbage cookie is still a {@code 401} that clears
 * the cookie, and a {@code 204} never mints, rotates or revokes anything, never resets a lockout
 * and answers byte-identically whether or not someone else holds a session (section 15.7 cases 4, 5
 * and 6 applied to the new answer).
 */
class RefreshWithoutSessionAdversarialTest extends AdversarialIntegrationTest {

  private static final String REFRESH = API + "/auth/refresh";

  private Req guarded() {
    return req("POST", REFRESH).header("X-Requested-With", "vitadelta");
  }

  private static void assertNoContent(Resp response) {
    assertThat(response.status()).as(response.toString()).isEqualTo(204);
    assertThat(response.body()).as("a 204 has no body").isEmpty();
    assertThat(response.setCookies()).as("a 204 sets and clears no cookie").isEmpty();
    assertThat(response.header("content-type")).isNull();
    assertThat(response.headers().allValues("cache-control")).containsExactly("no-store");
    assertApiSecurityHeaders(response);
  }

  /** Headers of a response without the per-response {@code Date} and {@code X-Request-Id}. */
  private static Map<String, List<String>> stableHeaders(Resp response) {
    Map<String, List<String>> headers = response.comparableHeaders();
    headers.remove("x-request-id");
    return headers;
  }

  private List<String> tokenRows(UUID userId) {
    return jdbc.sql(
            "SELECT token_hash || '|' || coalesce(revoked_at::text, 'live') FROM refresh_token"
                + " WHERE user_id = ? ORDER BY token_hash")
        .param(userId)
        .query(String.class)
        .list();
  }

  private long allTokenRows() {
    return count("SELECT count(*) FROM refresh_token");
  }

  private Resp login(String sourceIp, String email, String password) {
    return http.req("POST", API + "/auth/login")
        .ip(sourceIp)
        .json(Map.of("email", email, "password", password))
        .send();
  }

  @Test
  void withoutACookieTheAnswerIsAnEmptyNoContentAfterTheForgeryGuard() {
    long before = allTokenRows();
    assertNoContent(guarded().json("{}").send());
    assertNoContent(guarded().cookie("vd_refresh", "").json("{}").send());
    assertNoContent(guarded().header("Cookie", "vd_refresh=").json("{}").send());
    assertNoContent(guarded().header("Cookie", "vd_refresh=;").json("{}").send());
    assertNoContent(guarded().cookie("vd_anon", "abc.def").json("{}").send());
    assertNoContent(guarded().cookie("VD_REFRESH", "not-the-cookie-name").json("{}").send());
    assertNoContent(guarded().cookie("vd_refresh_x", "other-cookie").json("{}").send());
    assertThat(allTokenRows()).as("a 204 creates no refresh token").isEqualTo(before);

    // The forgery guard still runs first: no header, a wrong header, a foreign origin, a form.
    assertProblem(req("POST", REFRESH).json("{}").send(), 403, "forbidden");
    assertProblem(
        req("POST", REFRESH).header("X-Requested-With", "XMLHttpRequest").json("{}").send(),
        403,
        "forbidden");
    assertProblem(
        guarded().header("Origin", "https://evil.example").json("{}").send(), 403, "forbidden");
    assertProblem(
        guarded().body("a=b", "application/x-www-form-urlencoded").send(), 403, "forbidden");
    assertProblem(guarded().body("{}", "text/plain").send(), 403, "forbidden");
    assertProblem(guarded().send(), 403, "forbidden");
  }

  @Test
  void aGarbageOrDeadCookieIsStillUnauthorizedAndClearsTheCookie() {
    String email = uniqueEmail();
    registerVerified(email);
    Session session = login(email, PASSWORD);
    Resp logout =
        req("POST", API + "/auth/logout")
            .header("X-Requested-With", "vitadelta")
            .cookie("vd_refresh", session.refreshToken())
            .json("{}")
            .send();
    assertThat(logout.status()).isEqualTo(204);

    List<String> cookies =
        List.of(
            "garbage",
            "x",
            "A".repeat(4000),
            SecureTokens.newToken(),
            SecureTokens.newToken().substring(1),
            " ",
            "%00",
            "\"\"");
    List<Resp> answers = new ArrayList<>();
    for (String value : cookies) {
      Resp response = guarded().cookie("vd_refresh", value).json("{}").send();
      if (value.isBlank() || value.startsWith("%") || value.startsWith("\"")) {
        // The container may read these values as empty or drop the cookie: then it is no cookie.
        assertThat(response.status()).as("'%s' -> %s", value, response).isIn(204, 401);
        continue;
      }
      assertProblem(response, 401, "unauthorized");
      assertThat(response.setCookie("vd_refresh").orElseThrow())
          .as("the rejected cookie is cleared")
          .contains("Max-Age=0");
      assertThat(response.body()).doesNotContain("accessToken");
      answers.add(response);
    }
    // A logged-out token is dead and cleared as well.
    Resp loggedOut = guarded().cookie("vd_refresh", session.refreshToken()).json("{}").send();
    assertThat(loggedOut.status()).as(loggedOut.toString()).isEqualTo(401);
    assertThat(loggedOut.setCookie("vd_refresh").orElseThrow()).contains("Max-Age=0");
    assertThat(loggedOut.body()).doesNotContain("accessToken");
    // Unknown, random and malformed tokens are one answer: a token is not a validity oracle.
    String first = answers.getFirst().body().replaceAll("\"requestId\":\"[^\"]*\"", "");
    for (Resp answer : answers) {
      assertThat(answer.body().replaceAll("\"requestId\":\"[^\"]*\"", "")).isEqualTo(first);
      assertThat(stableHeaders(answer)).isEqualTo(stableHeaders(answers.getFirst()));
    }
  }

  @Test
  void theCookieLessAnswerIsStillRateLimitedPerClientIp() {
    String client = randomIp();
    for (int i = 0; i < 60; i++) {
      Resp response =
          http.req("POST", REFRESH)
              .ip(client)
              .header("X-Requested-With", "vitadelta")
              .json("{}")
              .send();
      assertThat(response.status()).as("request %d: %s", i + 1, response).isEqualTo(204);
    }
    Resp limited =
        http.req("POST", REFRESH)
            .ip(client)
            .header("X-Requested-With", "vitadelta")
            .json("{}")
            .send();
    assertProblem(limited, 429, "rate_limited");
    assertThat(Long.parseLong(limited.header("retry-after"))).isPositive();
    assertThat(limited.setCookies()).isEmpty();

    // A cookie does not escape the limit, and spoofed left entries do not either.
    assertProblem(
        http.req("POST", REFRESH)
            .ip(client)
            .header("X-Requested-With", "vitadelta")
            .cookie("vd_refresh", "garbage")
            .json("{}")
            .send(),
        429,
        "rate_limited");
    assertProblem(
        http.req("POST", REFRESH)
            .ip(randomIp() + ", " + client)
            .header("X-Requested-With", "vitadelta")
            .json("{}")
            .send(),
        429,
        "rate_limited");
  }

  @Test
  void aNoContentNeverMintsRotatesOrRevokesTheSessionsOfAnyone() {
    String email = uniqueEmail();
    UUID id = registerVerified(email);
    Session victim = login(email, PASSWORD);
    List<String> rowsBefore = tokenRows(id);
    long allBefore = allTokenRows();

    // Everything an attacker may know about the victim except the cookie itself.
    List<Resp> probes =
        List.of(
            guarded().bearer(victim.accessToken()).json("{}").send(),
            guarded().header("User-Agent", "victim-browser/1.0").json("{}").send(),
            guarded().cookie("vd_refresh", "").bearer(victim.accessToken()).json("{}").send(),
            guarded().json(Map.of("refreshToken", victim.refreshToken())).send(),
            guarded().json(Map.of("email", email, "userId", id.toString())).send(),
            req("POST", REFRESH + "?vd_refresh=" + victim.refreshToken())
                .header("X-Requested-With", "vitadelta")
                .json("{}")
                .send(),
            guarded().header("X-Refresh-Token", victim.refreshToken()).json("{}").send());
    for (Resp probe : probes) {
      assertNoContent(probe);
      assertThat(probe.body()).doesNotContain("accessToken");
    }
    assertThat(tokenRows(id)).as("nothing rotated or revoked").isEqualTo(rowsBefore);
    assertThat(allTokenRows()).as("nothing minted").isEqualTo(allBefore);

    // The victim's session still rotates normally afterwards.
    Resp rotated = refresh(victim.refreshToken());
    assertThat(rotated.status()).as(rotated.toString()).isEqualTo(200);
  }

  @Test
  void theAnswerIsIdenticalWhetherOrNotAnotherUserHoldsASession() {
    String email = uniqueEmail();
    registerVerified(email);
    Resp withoutSession = guarded().json("{}").send();
    Session victim = login(email, PASSWORD);
    Resp withSession = guarded().json("{}").send();
    Resp withSessionAndBearer = guarded().bearer(victim.accessToken()).json("{}").send();
    req("POST", API + "/auth/logout")
        .header("X-Requested-With", "vitadelta")
        .cookie("vd_refresh", victim.refreshToken())
        .json("{}")
        .send();
    Resp afterLogout = guarded().json("{}").send();
    for (Resp response : List.of(withSession, withSessionAndBearer, afterLogout)) {
      assertNoContent(response);
      assertThat(stableHeaders(response)).isEqualTo(stableHeaders(withoutSession));
      assertThat(response.body()).isEqualTo(withoutSession.body());
    }
  }

  @Test
  void aNoContentNeverResetsTheLoginLockouts() {
    // Per-account back-off: ten failures from ten addresses, then cookie-less refreshes.
    String email = uniqueEmail();
    registerVerified(email);
    for (int i = 0; i < 10; i++) {
      assertThat(login(randomIp(), email, "definitely-wrong-password").status()).isEqualTo(401);
    }
    for (int i = 0; i < 20; i++) {
      assertNoContent(
          http.req("POST", REFRESH)
              .ip(randomIp())
              .header("X-Requested-With", "vitadelta")
              .json(Map.of("email", email))
              .send());
    }
    Resp correct = login(randomIp(), email, PASSWORD);
    assertProblem(correct, 429, "rate_limited");
    assertThat(correct.setCookie("vd_refresh")).isEmpty();

    // Per-IP login limit: ten failures from one address, then cookie-less refreshes from it.
    String client = randomIp();
    for (int i = 0; i < 10; i++) {
      assertThat(login(client, uniqueEmail(), "definitely-wrong-password").status()).isEqualTo(401);
    }
    for (int i = 0; i < 20; i++) {
      assertNoContent(
          http.req("POST", REFRESH)
              .ip(client)
              .header("X-Requested-With", "vitadelta")
              .json("{}")
              .send());
    }
    assertProblem(login(client, uniqueEmail(), "definitely-wrong-password"), 429, "rate_limited");
  }
}
