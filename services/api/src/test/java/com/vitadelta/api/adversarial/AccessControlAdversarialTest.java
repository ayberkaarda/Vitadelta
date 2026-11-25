package com.vitadelta.api.adversarial;

import static com.vitadelta.api.adversarial.AttackClient.assertApiSecurityHeaders;
import static com.vitadelta.api.adversarial.AttackClient.assertNoLeak;
import static com.vitadelta.api.adversarial.AttackClient.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.adversarial.AttackClient.Resp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalManagementPort;

/**
 * Section 15.7 cases 2 (the parts that exist in Phase 3) and 18, plus routing attacks: path
 * variations that might slip past the route matrix, verbs that are not allowed, mass assignment on
 * {@code PATCH /me} and registration, security headers on error responses and Actuator exposure.
 */
@ExtendWith(OutputCaptureExtension.class)
class AccessControlAdversarialTest extends AdversarialIntegrationTest {

  @LocalManagementPort int managementPort;

  // Case 2 -----------------------------------------------------------------------------------

  @Test
  void adminUserListIsForbiddenForUsersAndUnauthorizedForAnonymous() {
    String user = accessToken(insertUser(true), "USER", true);
    assertProblem(get(API + "/admin/users", user), 403, "forbidden");
    assertProblem(get(API + "/admin/users?q=example", user), 403, "forbidden");
    assertProblem(get(API + "/admin/users", null), 401, "unauthorized");
  }

  @Test
  void adminIdRoutesWithUnknownIdsAnswerNotFoundAndChangeNothing() {
    String admin = adminToken();
    UUID unknown = UUID.randomUUID();
    Resp status =
        req("POST", API + "/admin/users/" + unknown + "/status")
            .bearer(admin)
            .json(Map.of("status", "DISABLED"))
            .send();
    Resp grant =
        req("POST", API + "/admin/users/" + unknown + "/subscription")
            .bearer(admin)
            .json(Map.of("planCode", "PRO", "durationDays", 30))
            .send();
    Resp promo = req("DELETE", API + "/admin/promo-codes/" + randomCode()).bearer(admin).send();
    for (Resp response : List.of(status, grant, promo)) {
      assertThat(response.status()).as(response.toString()).isEqualTo(404);
      assertThat(response.header("content-type")).startsWith("application/problem+json");
      assertNoLeak(response);
    }
    assertThat(count("SELECT count(*) FROM subscription WHERE user_id = ?", unknown)).isZero();

    Resp malformed =
        req("POST", API + "/admin/users/not-a-uuid/status")
            .bearer(admin)
            .json(Map.of("status", "DISABLED"))
            .send();
    assertProblem(malformed, 422, "validation_failed");
  }

  @Test
  void usersCannotChangeTheirOwnStatusOrPlanThroughAdminRoutes() {
    UUID id = insertUser(true);
    String token = accessToken(id, "USER", true);
    req("POST", API + "/admin/users/" + id + "/subscription")
        .bearer(token)
        .json(Map.of("planCode", "PRO", "durationDays", 30))
        .send();
    assertThat(
            count("SELECT count(*) FROM subscription WHERE user_id = ? AND plan_code = 'PRO'", id))
        .isZero();
  }

  // Path variations --------------------------------------------------------------------------

  private static final List<String> ADMIN_PATH_VARIANTS =
      List.of(
          "/api/v1/admin/users/",
          "/api/v1//admin/users",
          "/api/v1/admin//users",
          "//api/v1/admin/users",
          "/api/v1/admin/../admin/users",
          "/api/v1/me/../admin/users",
          "/api/v1/admin/./users",
          "/api/v1/admin/users;jsessionid=0123456789",
          "/api/v1/admin;x=y/users",
          "/api/v1/%61dmin/users",
          "/api/v1/admin%2Fusers",
          "/api/v1/admin%2fusers",
          "/api/v1/admin/users%2F",
          "/api/v1/admin/%2e%2e/admin/users",
          "/api/v1/admin/users%00",
          "/api/v1/admin/users%5C",
          "/api/v1/ADMIN/users",
          "/api/v1/Admin/Users",
          "/api/v1/admin/users.json",
          "/api/v1/admin/users?q=%27%20OR%20%271%27%3D%271");

  @Test
  void pathVariationsNeverReachAdminDataAsUser() {
    String user = accessToken(insertUser(true), "USER", true);
    for (String path : ADMIN_PATH_VARIANTS) {
      Resp response = http.req("GET", path).ip(ip).bearer(user).send();
      assertThat(response.status())
          .as("%s as USER -> %s", path, response)
          .isIn(400, 401, 403, 404, 405);
      assertThat(response.body()).as(path).doesNotContain("\"items\"");
      assertNoLeak(response);
    }
  }

  @Test
  void pathVariationsNeverReachTheProfileAnonymously() {
    List<String> paths =
        List.of(
            "/api/v1/me/",
            "/api/v1//me",
            "/api/v1/%6De",
            "/api/v1/ME",
            "/api/v1/me;x=1",
            "/api/v1/health/../me",
            "/api/v1/auth/../me",
            "/api/v1/auth/%2e%2e/me",
            "/api/v1/plans/../me");
    for (String path : paths) {
      Resp response = http.req("GET", path).ip(ip).send();
      assertThat(response.status())
          .as("%s anonymous -> %s", path, response)
          .isIn(400, 401, 403, 404, 405);
      assertThat(response.body()).as(path).doesNotContain("\"email\"");
      assertNoLeak(response);
    }
  }

  @Test
  void verbsThatAreNotAllowedAreRejectedWithoutLeaks() {
    String user = accessToken(insertUser(true), "USER", true);
    record Attempt(String method, String path, String bearer) {}
    List<Attempt> attempts =
        List.of(
            new Attempt("PUT", API + "/auth/login", null),
            new Attempt("DELETE", API + "/auth/login", null),
            new Attempt("GET", API + "/auth/login", null),
            new Attempt("PATCH", API + "/auth/refresh", null),
            new Attempt("TRACE", API + "/health", null),
            new Attempt("DELETE", API + "/health", null),
            new Attempt("POST", API + "/health", null),
            new Attempt("PUT", API + "/me", user),
            new Attempt("POST", API + "/me", user),
            new Attempt("DELETE", API + "/plans", null),
            new Attempt("GET", API + "/me/subscription/promo", user),
            new Attempt("PUT", API + "/admin/users", user));
    for (Attempt attempt : attempts) {
      Resp response =
          http.req(attempt.method(), attempt.path())
              .ip(ip)
              .bearer(attempt.bearer())
              .json("{}")
              .send();
      assertThat(response.status())
          .as("%s %s -> %s", attempt.method(), attempt.path(), response)
          .isIn(400, 401, 403, 404, 405);
      assertNoLeak(response);
    }
  }

  // Mass assignment --------------------------------------------------------------------------

  @Test
  void patchMeIgnoresRoleVerificationStatusAndEmail() {
    String email = uniqueEmail();
    register(email, PASSWORD);
    UUID id = userId(email);
    Session session = login(email, PASSWORD);
    Resp patched =
        req("PATCH", API + "/me")
            .bearer(session.accessToken())
            .json(
                Map.of(
                    "locale",
                    "tr",
                    "role",
                    "ADMIN",
                    "emailVerified",
                    true,
                    "email_verified",
                    true,
                    "status",
                    "DISABLED",
                    "email",
                    "takeover@example.org",
                    "id",
                    UUID.randomUUID().toString(),
                    "plan",
                    Map.of("code", "PRO")))
            .send();
    assertThat(patched.status()).as(patched.toString()).isIn(200, 422);
    Map<String, Object> row =
        jdbc.sql("SELECT role, status, email, email_verified_at FROM app_user WHERE id = ?")
            .param(id)
            .query()
            .singleRow();
    assertThat(row.get("role")).isEqualTo("USER");
    assertThat(row.get("status")).isEqualTo("ACTIVE");
    assertThat(String.valueOf(row.get("email"))).isEqualTo(email);
    assertThat(row.get("email_verified_at")).isNull();
    assertThat(
            count("SELECT count(*) FROM subscription WHERE user_id = ? AND plan_code = 'PRO'", id))
        .isZero();

    Resp onlyForbidden =
        req("PATCH", API + "/me")
            .bearer(session.accessToken())
            .json(Map.of("role", "ADMIN"))
            .send();
    assertProblem(onlyForbidden, 422, "validation_failed");
  }

  @Test
  void registrationIgnoresRoleAndVerificationFields() {
    String email = uniqueEmail();
    Resp response =
        postJson(
            API + "/auth/register",
            Map.of(
                "email",
                email,
                "password",
                PASSWORD,
                "role",
                "ADMIN",
                "emailVerified",
                true,
                "status",
                "ACTIVE",
                "emailVerifiedAt",
                "2020-01-01T00:00:00Z"));
    assertThat(response.status()).as(response.toString()).isIn(202, 422);
    jdbc.sql("SELECT role, email_verified_at FROM app_user WHERE email = CAST(? AS citext)")
        .param(email)
        .query()
        .listOfRows()
        .forEach(row -> {
          assertThat(row.get("role")).isEqualTo("USER");
          assertThat(row.get("email_verified_at")).isNull();
        });
  }

  // Request id and health (case 18) ----------------------------------------------------------

  @Test
  void hostileRequestIdsAreReplacedInResponsesAndLogs(CapturedOutput output) {
    String longId = "A".repeat(10_000);
    Resp longResponse = req("GET", API + "/health").requestId(longId).send();
    assertThat(longResponse.status()).isEqualTo(200);
    assertThat(longResponse.header("x-request-id")).isNotEqualTo(longId).hasSizeLessThan(65);

    String spaced = "abc def ghi";
    Resp spacedResponse = req("GET", API + "/health").requestId(spaced).send();
    assertThat(spacedResponse.header("x-request-id")).isNotEqualTo(spaced);
    assertThat(spacedResponse.header("x-request-id")).matches("^[A-Za-z0-9-]{1,64}$");

    String injected = "id-1\",\"level\":\"ERROR";
    Resp injectedResponse = req("GET", API + "/health").requestId(injected).send();
    assertThat(injectedResponse.header("x-request-id")).matches("^[A-Za-z0-9-]{1,64}$");

    String exact = "A".repeat(65);
    assertThat(req("GET", API + "/health").requestId(exact).send().header("x-request-id"))
        .isNotEqualTo(exact);
    String accepted = "adv-" + UUID.randomUUID();
    assertThat(req("GET", API + "/health").requestId(accepted).send().header("x-request-id"))
        .isEqualTo(accepted);

    // A request that logs a line: the logged request id is the generated one, never the input.
    String email = uniqueEmail();
    register(email, PASSWORD);
    Session session = login(email, PASSWORD);
    Resp deleted =
        req("DELETE", API + "/me")
            .bearer(session.accessToken())
            .requestId(longId)
            .json(Map.of("password", PASSWORD))
            .send();
    assertThat(deleted.status()).as(deleted.toString()).isEqualTo(204);
    String generated = deleted.header("x-request-id");
    assertThat(generated).isNotEqualTo(longId);
    String logs = output.getAll();
    assertThat(logs).doesNotContain("A".repeat(100)).doesNotContain(spaced).contains(generated);
  }

  @Test
  void publicHealthReturnsOnlyTheStatus() {
    Resp health = http.req("GET", API + "/health").send();
    assertThat(health.status()).isEqualTo(200);
    assertThat(health.json().size()).isEqualTo(1);
    assertThat(health.json().get("status").asString()).isIn("UP", "DEGRADED");
    assertApiSecurityHeaders(health);

    String user = accessToken(insertUser(true), "USER", true);
    assertProblem(get(API + "/admin/health", user), 403, "forbidden");
    assertProblem(get(API + "/admin/health", null), 401, "unauthorized");
  }

  @Test
  void actuatorIsNotOnTheApiPortAndExposesNoDetail() {
    for (String path : List.of("/actuator", "/actuator/health", "/actuator/env", "/error")) {
      Resp response = http.req("GET", path).send();
      assertThat(response.status()).as("%s -> %s", path, response).isIn(401, 403, 404);
      assertNoLeak(response);
    }
    AttackClient management = new AttackClient(managementPort);
    Resp health = management.req("GET", "/actuator/health").send();
    assertThat(health.status()).isEqualTo(200);
    assertThat(health.json().size()).as(health.body()).isEqualTo(1);
    for (String path :
        List.of(
            "/actuator/env",
            "/actuator/heapdump",
            "/actuator/threaddump",
            "/actuator/configprops",
            "/actuator/beans",
            "/actuator/mappings",
            "/actuator/loggers")) {
      Resp response = management.req("GET", path).send();
      assertThat(response.status()).as("%s -> %s", path, response).isIn(401, 403, 404);
    }
  }

  // Headers on error responses ---------------------------------------------------------------

  @Test
  void securityHeadersAreSetOnErrorResponses() {
    String user = accessToken(insertUser(true), "USER", true);
    List<Resp> responses =
        List.of(
            get(API + "/me", null),
            get(API + "/admin/users", user),
            get(API + "/no-such-route", user),
            req("PUT", API + "/auth/login").json("{}").send(),
            req("POST", API + "/auth/login").json("{\"email\":1}").send(),
            req("POST", API + "/auth/refresh").json("{}").send(),
            req("POST", API + "/auth/login").json("{not json").send());
    for (Resp response : responses) {
      assertThat(response.status()).as(response.toString()).isGreaterThanOrEqualTo(400);
      assertApiSecurityHeaders(response);
      assertNoLeak(response);
    }
  }

  @Test
  void authResponsesAreNeverCached() {
    List<Resp> responses =
        List.of(
            req("POST", API + "/auth/login")
                .json(Map.of("email", uniqueEmail(), "password", "x"))
                .send(),
            req("POST", API + "/auth/refresh").json("{}").send(),
            req("POST", API + "/auth/forgot-password").json(Map.of("email", uniqueEmail())).send(),
            req("POST", API + "/auth/verify-email").json("{}").send());
    for (Resp response : responses) {
      assertThat(response.headers().allValues("cache-control"))
          .as(response.toString())
          .containsExactly("no-store");
    }
  }
}
