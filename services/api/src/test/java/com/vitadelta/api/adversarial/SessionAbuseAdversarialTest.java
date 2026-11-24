package com.vitadelta.api.adversarial;

import static com.vitadelta.api.adversarial.AttackClient.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.adversarial.AttackClient.Resp;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Section 15.7 cases 5 (refresh-token reuse and the grace window) and 13 (disabled accounts). */
class SessionAbuseAdversarialTest extends AdversarialIntegrationTest {

  private long liveTokens(UUID userId) {
    return count(
        "SELECT count(*) FROM refresh_token WHERE user_id = ? AND revoked_at IS NULL", userId);
  }

  @Test
  void replayAfterTheGraceWindowRevokesTheWholeFamily() {
    String email = uniqueEmail();
    UUID id = registerVerified(email);
    Session stolen = login(email, PASSWORD);
    Session otherDevice = login(email, PASSWORD);

    Resp rotated = refresh(stolen.refreshToken());
    assertThat(rotated.status()).isEqualTo(200);
    String successor = rotated.cookieValue("vd_refresh");
    clock.advance(Duration.ofSeconds(21));

    Resp replay = refresh(stolen.refreshToken());
    assertProblem(replay, 401, "refresh_reuse_detected");
    assertThat(replay.setCookie("vd_refresh").orElseThrow()).contains("Max-Age=0");
    assertThat(replay.body()).doesNotContain("accessToken");

    assertProblem(refresh(successor), 401, "refresh_reuse_detected");
    assertThat(
            count(
                "SELECT count(*) FROM refresh_token WHERE user_id = ? AND revoked_at IS NULL"
                    + " AND family_id = (SELECT family_id FROM refresh_token WHERE token_hash = ?)",
                id,
                com.vitadelta.api.identity.SecureTokens.sha256Hex(successor)))
        .isZero();
    assertThat(refresh(otherDevice.refreshToken()).status())
        .as("another family of the user is not affected")
        .isEqualTo(200);
  }

  @Test
  void replayWithinTheGraceWindowReturnsTheSameSuccessorAndRevokesNothing() {
    String email = uniqueEmail();
    UUID id = registerVerified(email);
    Session session = login(email, PASSWORD);

    Resp first = refresh(session.refreshToken());
    String successor = first.cookieValue("vd_refresh");
    clock.advance(Duration.ofSeconds(10));
    Resp second = refresh(session.refreshToken());
    assertThat(second.status()).as(second.toString()).isEqualTo(200);
    assertThat(second.cookieValue("vd_refresh")).isEqualTo(successor);
    assertThat(liveTokens(id)).isEqualTo(1);

    Resp next = refresh(successor);
    assertThat(next.status()).as("the family is still alive").isEqualTo(200);
    assertThat(next.cookieValue("vd_refresh")).isNotEqualTo(successor);
  }

  @Test
  void graceWindowBoundaryIsExclusiveAtTwentySeconds() {
    String email = uniqueEmail();
    registerVerified(email);
    Session session = login(email, PASSWORD);
    refresh(session.refreshToken());
    clock.advance(Duration.ofSeconds(20).plusNanos(1_000));
    assertProblem(refresh(session.refreshToken()), 401, "refresh_reuse_detected");
  }

  @Test
  void refreshAfterLogoutIsDead() {
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
    Resp afterLogout = refresh(session.refreshToken());
    assertThat(afterLogout.status()).isEqualTo(401);
    assertThat(afterLogout.body()).doesNotContain("accessToken");
  }

  @Test
  void disablingRevokesEveryFamilyAndBlocksRefreshAndLogin() {
    String email = uniqueEmail();
    UUID id = registerVerified(email);
    Session one = login(email, PASSWORD);
    Session two = login(email, PASSWORD);
    Resp rotated = refresh(two.refreshToken());
    assertThat(rotated.status()).isEqualTo(200);
    String admin = adminToken();

    Resp disabled =
        req("POST", API + "/admin/users/" + id + "/status")
            .bearer(admin)
            .json(Map.of("status", "DISABLED"))
            .send();
    assertThat(disabled.status()).as(disabled.toString()).isEqualTo(200);
    assertThat(liveTokens(id)).isZero();
    assertThat(
            count(
                "SELECT count(*) FROM refresh_token WHERE user_id = ? AND grace_successor_enc IS"
                    + " NOT NULL",
                id))
        .isZero();

    Resp refreshOne = refresh(one.refreshToken());
    assertProblem(refreshOne, 403, "account_disabled");
    assertThat(refreshOne.body()).doesNotContain("accessToken");
    assertProblem(refresh(rotated.cookieValue("vd_refresh")), 403, "account_disabled");
    assertProblem(refresh(two.refreshToken()), 403, "account_disabled");

    Resp wrongPassword =
        postJson(API + "/auth/login", Map.of("email", email, "password", "not-the-password"));
    assertProblem(wrongPassword, 401, "invalid_credentials");
    ip = randomIp();
    Resp rightPassword =
        postJson(API + "/auth/login", Map.of("email", email, "password", PASSWORD));
    assertProblem(rightPassword, 403, "account_disabled");
    assertThat(rightPassword.setCookie("vd_refresh")).isEmpty();
    assertThat(liveTokens(id)).isZero();

    Resp forgot = postJson(API + "/auth/forgot-password", Map.of("email", email));
    assertThat(forgot.status()).isEqualTo(202);
    assertThat(
            count(
                "SELECT count(*) FROM email_token WHERE user_id = ? AND kind = 'RESET_PASSWORD'",
                id))
        .as("a disabled account gets no reset token")
        .isZero();

    jdbc.sql("UPDATE app_user SET status = 'ACTIVE' WHERE id = ?").param(id).update();
    assertThat(refresh(one.refreshToken()).status())
        .as("re-enabling does not resurrect revoked sessions")
        .isEqualTo(401);
  }
}
