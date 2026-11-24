package com.vitadelta.api.adversarial;

import static com.vitadelta.api.adversarial.AttackClient.assertApiSecurityHeaders;
import static com.vitadelta.api.adversarial.AttackClient.assertNoLeak;
import static com.vitadelta.api.adversarial.AttackClient.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.adversarial.AttackClient.Req;
import com.vitadelta.api.adversarial.AttackClient.Resp;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Section 15.7 case 7 on the endpoints that exist: bodies over the 64 kB limit are rejected with
 * {@code 413} problem+json before anything is processed, whether the length is declared or the body
 * is chunked; plus oversized individual fields (10 000-character passwords) and oversized headers.
 */
class OversizedInputAdversarialTest extends AdversarialIntegrationTest {

  private static final int LIMIT = 64 * 1024;

  /** A syntactically valid JSON object of more than {@code size} bytes. */
  private static String paddedJson(String email, int size) {
    String head = "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"pad\":\"";
    return head + "a".repeat(size - head.length() + 1) + "\"}";
  }

  @Test
  void oversizedRegistrationIsRejectedBeforeAnyAccountIsCreated() {
    for (boolean chunked : List.of(false, true)) {
      String email = uniqueEmail();
      Req request = req("POST", API + "/auth/register").json(paddedJson(email, LIMIT + 10));
      Resp response = (chunked ? request.chunked() : request).send();
      assertProblem(response, 413, "validation_failed");
      assertThat(count("SELECT count(*) FROM app_user WHERE email = CAST(? AS citext)", email))
          .as("chunked=%s", chunked)
          .isZero();
      assertThat(mail().to(email)).isEmpty();
    }
  }

  @Test
  void oneByteOverTheLimitIsRejectedAndTheLimitItselfIsAccepted() {
    String head =
        "{\"email\":\"" + uniqueEmail() + "\",\"password\":\"" + PASSWORD + "\",\"pad\":\"";
    String exact = head + "a".repeat(LIMIT - head.length() - 2) + "\"}";
    assertThat(exact.length()).isEqualTo(LIMIT);
    Resp ok = req("POST", API + "/auth/login").json(exact).send();
    assertThat(ok.status()).as(ok.toString()).isIn(401, 422);
    Resp over = req("POST", API + "/auth/login").json(exact.replace("\"}", "a\"}")).send();
    assertProblem(over, 413, "validation_failed");
  }

  @Test
  void oversizedBodiesAreRejectedOnEveryWritingEndpoint() {
    String user = accessToken(insertUser(true), "USER", true);
    String admin = adminToken();
    String big = "{\"x\":\"" + "b".repeat(LIMIT + 100) + "\"}";
    record Target(String method, String path, String bearer) {}
    List<Target> targets =
        List.of(
            new Target("POST", API + "/auth/login", null),
            new Target("POST", API + "/auth/register", null),
            new Target("POST", API + "/auth/verify-email", null),
            new Target("POST", API + "/auth/resend-verification", null),
            new Target("POST", API + "/auth/forgot-password", null),
            new Target("POST", API + "/auth/reset-password", null),
            new Target("PATCH", API + "/me", user),
            new Target("DELETE", API + "/me", user),
            new Target("POST", API + "/me/subscription/promo", user),
            new Target("POST", API + "/admin/promo-codes", admin),
            new Target(
                "POST", API + "/admin/users/" + java.util.UUID.randomUUID() + "/status", admin));
    for (Function<Req, Req> mode : List.<Function<Req, Req>>of(r -> r, Req::chunked)) {
      for (Target target : targets) {
        Resp response =
            mode.apply(
                    http.req(target.method(), target.path())
                        .ip(randomIp())
                        .bearer(target.bearer())
                        .json(big))
                .send();
        assertProblem(response, 413, "validation_failed");
      }
    }
  }

  @Test
  void rejectedDeclaredLengthOversizedBodiesCarryTheApiSecurityHeaders() {
    Resp declared =
        req("POST", API + "/auth/login").json(paddedJson(uniqueEmail(), LIMIT + 10)).send();
    assertThat(declared.status()).isEqualTo(413);
    assertApiSecurityHeaders(declared);
    assertThat(declared.headers().allValues("cache-control")).containsExactly("no-store");
  }

  @Test
  void rejectedChunkedOversizedBodiesCarryTheApiSecurityHeaders() {
    Resp chunked =
        req("POST", API + "/auth/login")
            .json(paddedJson(uniqueEmail(), LIMIT + 10))
            .chunked()
            .send();
    assertThat(chunked.status()).isEqualTo(413);
    assertApiSecurityHeaders(chunked);
  }

  @Test
  void tenThousandCharacterPasswordsNeitherFailNorStall() {
    String longPassword = "Zq7-".repeat(2500);
    String email = uniqueEmail();
    Resp registered =
        postJson(API + "/auth/register", Map.of("email", email, "password", longPassword));
    assertThat(registered.status()).as(registered.toString()).isIn(202, 422);
    assertThat(registered.nanos()).isLessThan(10_000_000_000L);
    assertNoLeak(registered);

    Resp login =
        postJson(
            API + "/auth/login", Map.of("email", uniqueEmail(), "password", "p".repeat(60_000)));
    assertProblem(login, 401, "invalid_credentials");
    assertThat(login.nanos()).isLessThan(10_000_000_000L);

    if (registered.status() == 202) {
      String token = mail().awaitToken(email, 1);
      assertThat(postJson(API + "/auth/verify-email", Map.of("token", token)).status())
          .isEqualTo(204);
      Resp ok = postJson(API + "/auth/login", Map.of("email", email, "password", longPassword));
      assertThat(ok.status()).as("the full password is significant").isEqualTo(200);
      Resp truncated =
          postJson(
              API + "/auth/login",
              Map.of("email", email, "password", longPassword.substring(0, 72)));
      assertProblem(truncated, 401, "invalid_credentials");
    }
  }

  @Test
  void oversizedFieldsAreValidationErrorsNotServerErrors() {
    String longLocal = "a".repeat(300) + "@example.org";
    for (String path :
        List.of(
            API + "/auth/register",
            API + "/auth/login",
            API + "/auth/forgot-password",
            API + "/auth/resend-verification")) {
      Resp response = postJson(path, Map.of("email", longLocal, "password", PASSWORD));
      assertProblem(response, 422, "validation_failed");
    }
    Resp token = postJson(API + "/auth/verify-email", Map.of("token", "a".repeat(5000)));
    assertProblem(token, 422, "validation_failed");
  }

  @Test
  void oversizedHeadersAreRejectedWithoutLeaks() {
    Resp response = req("GET", API + "/health").header("X-Padding", "h".repeat(20_000)).send();
    assertThat(response.status()).as(response.toString()).isIn(400, 431);
    assertNoLeak(response);
  }

  @Test
  void deeplyNestedJsonIsRejectedWithoutAServerError() {
    String nested = "{\"email\":" + "[".repeat(5000) + "]".repeat(5000) + "}";
    Resp response = req("POST", API + "/auth/login").json(nested).send();
    assertProblem(response, 422, "validation_failed");
  }
}
