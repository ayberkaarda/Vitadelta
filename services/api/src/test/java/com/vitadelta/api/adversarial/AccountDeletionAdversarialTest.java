package com.vitadelta.api.adversarial;

import static com.vitadelta.api.adversarial.AttackClient.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.adversarial.AttackClient.Resp;
import com.vitadelta.api.identity.SecureTokens;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Section 15.7 case 10: {@code DELETE /me} leaves no row of the user in any section 5.9 cascade
 * table that exists in Phase 3, unlinks the converted anonymous identity, kills the old refresh
 * cookie and access token, and logs the deleted user id without the e-mail address.
 */
@ExtendWith(OutputCaptureExtension.class)
class AccountDeletionAdversarialTest extends AdversarialIntegrationTest {

  /** Cascade tables of section 5.9 that exist in Phase 3, keyed by their user column. */
  private static final List<String> CASCADE_TABLES =
      List.of(
          "refresh_token",
          "email_token",
          "subscription",
          "promo_redemption",
          "saved_product",
          "alert_subscription",
          "usage_counter");

  private long rowsOf(UUID userId) {
    long total = count("SELECT count(*) FROM app_user WHERE id = ?", userId);
    for (String table : CASCADE_TABLES) {
      total += count("SELECT count(*) FROM " + table + " WHERE user_id = ?", userId);
    }
    return total;
  }

  @Test
  void deletionRemovesEveryRowAndKillsTheSession(CapturedOutput output) {
    Resp anonymous = get(API + "/session/quota", null);
    String anonCookie = anonymous.cookieValue("vd_anon");
    String idHash = SecureTokens.sha256Hex(SecureTokens.decode(anonCookie.split("\\.")[0]));

    String email = uniqueEmail();
    Resp registered =
        req("POST", API + "/auth/register")
            .cookie("vd_anon", anonCookie)
            .json(Map.of("email", email, "password", PASSWORD))
            .send();
    assertThat(registered.status()).isEqualTo(202);
    String verifyToken = mail().awaitToken(email, 1);
    assertThat(postJson(API + "/auth/verify-email", Map.of("token", verifyToken)).status())
        .isEqualTo(204);
    UUID id = userId(email);
    assertThat(
            count(
                "SELECT count(*) FROM anonymous_identity WHERE id_hash = ? AND converted_user_id"
                    + " = ?",
                idHash,
                id))
        .isEqualTo(1);

    Session session = login(email, PASSWORD);
    Session second = login(email, PASSWORD);
    refresh(second.refreshToken());

    String admin = adminToken();
    Resp created =
        req("POST", API + "/admin/promo-codes")
            .bearer(admin)
            .json(Map.of("planCode", "PRO", "durationDays", 30, "maxRedemptions", 5))
            .send();
    assertThat(created.status()).as(created.toString()).isEqualTo(201);
    String code = created.json().get("code").asString();
    Resp redeemed =
        req("POST", API + "/me/subscription/promo")
            .bearer(session.accessToken())
            .json(Map.of("code", code))
            .send();
    assertThat(redeemed.status()).as(redeemed.toString()).isEqualTo(200);

    ip = randomIp();
    assertThat(postJson(API + "/auth/forgot-password", Map.of("email", email)).status())
        .isEqualTo(202);
    org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(15))
        .until(() ->
            mail().to(email).stream().anyMatch(m -> m.textBody().contains("/auth/reset#token=")));
    String resetToken =
        RecordingMailSender.token(
            mail().to(email).stream()
                .filter(m -> m.textBody().contains("/auth/reset#token="))
                .findFirst()
                .orElseThrow()
                .textBody());

    for (int i = 0; i < 3; i++) {
      UUID product = product();
      saveProduct(id, product);
      alert(id, product, "PRICE_DROP", true);
      alert(id, product, "BACK_IN_STOCK", false);
    }
    jdbc.sql(
            "INSERT INTO usage_counter (user_id, period_month, notifications_created)"
                + " VALUES (?, ?, 7)")
        .params(id, LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1))
        .update();
    for (String table : CASCADE_TABLES) {
      assertThat(count("SELECT count(*) FROM " + table + " WHERE user_id = ?", id))
          .as("arranged rows in %s", table)
          .isPositive();
    }

    Resp wrong =
        req("DELETE", API + "/me")
            .bearer(session.accessToken())
            .json(Map.of("password", "not-my-password"))
            .send();
    assertProblem(wrong, 401, "invalid_credentials");
    assertThat(count("SELECT count(*) FROM app_user WHERE id = ?", id)).isEqualTo(1);

    Resp deleted =
        req("DELETE", API + "/me")
            .bearer(session.accessToken())
            .json(Map.of("password", PASSWORD))
            .send();
    assertThat(deleted.status()).as(deleted.toString()).isEqualTo(204);
    assertThat(deleted.setCookie("vd_refresh").orElseThrow()).contains("Max-Age=0");

    assertThat(rowsOf(id)).isZero();
    assertThat(count("SELECT count(*) FROM anonymous_identity WHERE converted_user_id = ?", id))
        .isZero();
    assertThat(count("SELECT count(*) FROM promo_code WHERE code = CAST(? AS citext)", code))
        .as("the promo code itself stays")
        .isEqualTo(1);

    Resp oldRefresh = refresh(session.refreshToken());
    assertThat(oldRefresh.status()).isEqualTo(401);
    assertThat(oldRefresh.body()).doesNotContain("accessToken");
    assertThat(refresh(second.refreshToken()).status()).isEqualTo(401);
    assertProblem(get(API + "/me", session.accessToken()), 401, "unauthorized");
    assertProblem(get(API + "/session/quota", session.accessToken()), 401, "unauthorized");
    Resp promoAfter =
        req("POST", API + "/me/subscription/promo")
            .bearer(session.accessToken())
            .json(Map.of("code", code))
            .send();
    assertThat(promoAfter.status()).as(promoAfter.toString()).isEqualTo(401);
    assertThat(
            req("DELETE", API + "/me")
                .bearer(session.accessToken())
                .json(Map.of("password", PASSWORD))
                .send()
                .status())
        .isEqualTo(401);
    Resp resetAfter =
        postJson(
            API + "/auth/reset-password",
            Map.of("token", resetToken, "newPassword", "a-new-passphrase-after-deletion"));
    assertProblem(resetAfter, 400, "token_invalid");
    ip = randomIp();
    assertProblem(
        postJson(API + "/auth/login", Map.of("email", email, "password", PASSWORD)),
        401,
        "invalid_credentials");
    assertThat(rowsOf(id)).as("nothing was recreated by the later requests").isZero();

    String logs = output.getAll();
    assertThat(logs).contains("userId=" + id).doesNotContain(email);
  }
}
