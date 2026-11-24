package com.vitadelta.api.adversarial;

import static com.vitadelta.api.adversarial.AttackClient.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.adversarial.AttackClient.Resp;
import com.vitadelta.api.identity.SecureTokens;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Section 15.7 case 14: e-mail tokens are single-use, superseded by newer ones, all invalidated by
 * a successful reset, bound to their kind, time-limited, and never written to the log.
 */
@ExtendWith(OutputCaptureExtension.class)
class EmailTokenAbuseAdversarialTest extends AdversarialIntegrationTest {

  private static final String NEW_PASSWORD = "saffron-glacier-meadow-42";

  private Resp reset(String token, String password) {
    ip = randomIp();
    return postJson(API + "/auth/reset-password", Map.of("token", token, "newPassword", password));
  }

  private String forgot(String email, int expectedMails) {
    ip = randomIp();
    assertThat(postJson(API + "/auth/forgot-password", Map.of("email", email)).status())
        .isEqualTo(202);
    return mail().awaitToken(email, expectedMails);
  }

  @Test
  void resetTokensAreSingleUseSupersededAndInvalidatedTogether(CapturedOutput output) {
    String email = uniqueEmail();
    UUID id = registerVerified(email);
    String first = forgot(email, 2);
    clock.advance(Duration.ofMinutes(10).plusSeconds(1));
    String second = forgot(email, 3);
    assertThat(second).isNotEqualTo(first);

    assertProblem(reset(first, NEW_PASSWORD), 400, "token_invalid");

    String planted = SecureTokens.newToken();
    jdbc.sql(
            "INSERT INTO email_token (id, user_id, kind, token_hash, expires_at, created_at)"
                + " VALUES (?, ?, 'RESET_PASSWORD', ?, ?, ?)")
        .params(
            UUID.randomUUID(),
            id,
            SecureTokens.sha256Hex(planted),
            Timestamp.from(clock.instant().plus(Duration.ofMinutes(30))),
            Timestamp.from(clock.instant()))
        .update();

    Session before = login(email, PASSWORD);
    Resp done = reset(second, NEW_PASSWORD);
    assertThat(done.status()).as(done.toString()).isEqualTo(204);
    assertProblem(reset(second, "another-fresh-passphrase"), 400, "token_invalid");
    assertProblem(reset(planted, "another-fresh-passphrase"), 400, "token_invalid");
    assertThat(refresh(before.refreshToken()).status())
        .as("a reset revokes every session")
        .isEqualTo(401);
    login(email, NEW_PASSWORD);

    assertThat(output.getAll())
        .doesNotContain(first)
        .doesNotContain(second)
        .doesNotContain(planted)
        .doesNotContain(NEW_PASSWORD)
        .doesNotContain(PASSWORD)
        .doesNotContain(before.refreshToken())
        .doesNotContain(before.accessToken());
  }

  @Test
  void tokensAreBoundToTheirKind() {
    String email = uniqueEmail();
    register(email, PASSWORD);
    String verifyToken = mail().awaitToken(email, 1);
    assertProblem(reset(verifyToken, NEW_PASSWORD), 400, "token_invalid");
    String resetToken = forgot(email, 2);
    ip = randomIp();
    assertProblem(
        postJson(API + "/auth/verify-email", Map.of("token", resetToken)), 400, "token_invalid");
    assertThat(
            count(
                "SELECT count(*) FROM app_user WHERE email = CAST(? AS citext) AND"
                    + " email_verified_at IS NOT NULL",
                email))
        .isZero();
    ip = randomIp();
    assertThat(postJson(API + "/auth/verify-email", Map.of("token", verifyToken)).status())
        .isEqualTo(204);
    assertThat(reset(resetToken, NEW_PASSWORD).status()).isEqualTo(204);
  }

  @Test
  void expiredTokensAreRejected() {
    String email = uniqueEmail();
    registerVerified(email);
    String token = forgot(email, 2);
    clock.advance(Duration.ofMinutes(61));
    assertProblem(reset(token, NEW_PASSWORD), 400, "token_expired");
    ip = randomIp();
    assertProblem(
        postJson(API + "/auth/login", Map.of("email", email, "password", NEW_PASSWORD)),
        401,
        "invalid_credentials");
  }

  @Test
  void resendInvalidatesOlderVerificationTokens() {
    String email = uniqueEmail();
    register(email, PASSWORD);
    String first = mail().awaitToken(email, 1);
    clock.advance(Duration.ofMinutes(10).plusSeconds(1));
    ip = randomIp();
    assertThat(postJson(API + "/auth/resend-verification", Map.of("email", email)).status())
        .isEqualTo(202);
    String second = mail().awaitToken(email, 2);
    ip = randomIp();
    assertProblem(
        postJson(API + "/auth/verify-email", Map.of("token", first)), 400, "token_invalid");
    ip = randomIp();
    assertThat(postJson(API + "/auth/verify-email", Map.of("token", second)).status())
        .isEqualTo(204);
  }

  @Test
  void guessedAndMalformedTokensAreRejectedUniformly() {
    for (String guess :
        new String[] {SecureTokens.newToken(), "A".repeat(43), SecureTokens.newToken() + "A"}) {
      ip = randomIp();
      assertProblem(
          postJson(API + "/auth/verify-email", Map.of("token", guess)), 400, "token_invalid");
    }
    ip = randomIp();
    assertProblem(
        postJson(API + "/auth/verify-email", Map.of("token", "../../etc/passwd")),
        422,
        "validation_failed");
  }

  @Test
  void resetWithAWeakPasswordDoesNotBurnTheToken() {
    String email = uniqueEmail();
    registerVerified(email);
    String token = forgot(email, 2);
    assertProblem(reset(token, "short"), 422, "validation_failed");
    assertProblem(reset(token, "1234567890"), 422, "validation_failed");
    assertThat(reset(token, NEW_PASSWORD).status()).isEqualTo(204);
  }
}
