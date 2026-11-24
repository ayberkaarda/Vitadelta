package com.vitadelta.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitadelta.api.config.properties.SecretsProperties;
import com.vitadelta.api.config.properties.WebProperties;
import com.vitadelta.api.support.TestEnvironment;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Token generation, hashing, the grace-successor cipher and the cookie builders. */
class TokenCryptoTest {

  private final SecretsProperties secrets =
      new SecretsProperties(TestEnvironment.randomHex(32), null, TestEnvironment.randomHex(32));

  @Test
  void tokensAre256BitBase64UrlAndHashedWithSha256() {
    Set<String> tokens = new HashSet<>();
    for (int i = 0; i < 100; i++) {
      String token = SecureTokens.newToken();
      assertThat(token).hasSize(SecureTokens.TOKEN_LENGTH).matches("^[A-Za-z0-9_-]{43}$");
      assertThat(SecureTokens.decode(token)).hasSize(32);
      assertThat(SecureTokens.isWellFormed(token)).isTrue();
      tokens.add(token);
    }
    assertThat(tokens).hasSize(100);
    assertThat(SecureTokens.isWellFormed(null)).isFalse();
    assertThat(SecureTokens.isWellFormed("short")).isFalse();
    assertThat(SecureTokens.isWellFormed("+".repeat(43))).isFalse();
    assertThat(SecureTokens.sha256Hex("abc"))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  @Test
  void graceSuccessorsDecryptOnlyWithTheirKeyAndRow() {
    GraceCipher cipher = new GraceCipher(secrets);
    String token = SecureTokens.newToken();
    String encrypted = cipher.encrypt(token, "row-1");
    assertThat(encrypted).doesNotContain(token).hasSizeLessThanOrEqualTo(200);
    assertThat(cipher.encrypt(token, "row-1")).isNotEqualTo(encrypted);
    assertThat(cipher.decrypt(encrypted, "row-1")).hasValue(token);
    assertThat(cipher.decrypt(encrypted, "row-2")).isEmpty();
    String tampered = (encrypted.charAt(0) == 'A' ? "B" : "A") + encrypted.substring(1);
    assertThat(cipher.decrypt(tampered, "row-1")).isEmpty();
    assertThat(cipher.decrypt("AAAA", "row-1")).isEmpty();
    assertThat(cipher.decrypt("not base64 !", "row-1")).isEmpty();
    GraceCipher other =
        new GraceCipher(
            new SecretsProperties(
                TestEnvironment.randomHex(32), null, TestEnvironment.randomHex(32)));
    assertThat(other.decrypt(encrypted, "row-1")).isEmpty();
  }

  @Test
  void cookiesCarryTheSpecifiedFlags() {
    AuthCookies secure =
        new AuthCookies(
            new WebProperties(
                List.of(TestEnvironment.WEB_ORIGIN), TestEnvironment.WEB_BASE_URL, true, true));
    assertThat(secure.refresh("value"))
        .startsWith("vd_refresh=value; Path=/api/v1/auth; Max-Age=2592000")
        .contains("; Secure", "; HttpOnly", "; SameSite=Strict");
    assertThat(secure.clearRefresh()).contains("vd_refresh=;", "Max-Age=0", "Path=/api/v1/auth");
    assertThat(secure.anonymous("id.sig"))
        .startsWith("vd_anon=id.sig; Path=/; Max-Age=15552000")
        .contains("Secure", "HttpOnly", "SameSite=Lax");
    assertThat(secure.clearAnonymous()).contains("vd_anon=;", "Max-Age=0");

    AuthCookies development =
        new AuthCookies(
            new WebProperties(
                List.of("http://localhost:4200"), "http://localhost:4200", false, false));
    assertThat(development.refresh("value")).doesNotContain("Secure").contains("HttpOnly");
  }

  @Test
  void emailsAreNormalized() {
    assertThat(Emails.normalize("  User.Name@Example.ORG ")).isEqualTo("user.name@example.org");
    assertThat(Emails.normalize(null)).isEmpty();
  }

  @Test
  void recordsNeverPrintSecrets() {
    UserAccount user =
        new UserAccount(
            java.util.UUID.randomUUID(),
            "secret.person@example.org",
            "$argon2id$hash",
            null,
            Role.USER,
            UserStatus.ACTIVE,
            null,
            null,
            "en",
            true,
            null,
            java.time.Instant.EPOCH,
            java.time.Instant.EPOCH);
    assertThat(user.toString()).doesNotContain("secret.person").doesNotContain("argon2");
    assertThat(new IdentityApi.LoginRequest("secret.person@example.org", "hunter2-password"))
        .asString()
        .doesNotContain("secret.person")
        .doesNotContain("hunter2");
    assertThat(new IdentityApi.RegisterRequest("a@b.org", "hunter2-password", null, null))
        .asString()
        .doesNotContain("hunter2");
    assertThat(new IdentityApi.ResetPasswordRequest("tok", "hunter2-password"))
        .asString()
        .doesNotContain("hunter2")
        .doesNotContain("tok,");
    assertThat(new IdentityApi.TokenRequest("tokenvalue")).asString().doesNotContain("tokenvalue");
    assertThat(new IdentityApi.EmailRequest("a@b.org")).asString().doesNotContain("a@b.org");
    assertThat(new IdentityApi.DeleteAccountRequest("hunter2"))
        .asString()
        .doesNotContain("hunter2");
    assertThat(
            new IdentityApi.AuthTokenResponse(
                    "eyJ.access.token", "Bearer", 900, IdentityApi.UserProfile.of(user))
                .toString())
        .doesNotContain("eyJ.access.token");
    assertThat(new RefreshTokenService.Rotated(user, "refresh-value").toString())
        .doesNotContain("refresh-value");
    assertThat(
            new AuthService.Session(
                    user, new AccessTokenService.IssuedAccessToken("eyJ.a.b", 900), "refresh")
                .toString())
        .doesNotContain("eyJ.a.b")
        .doesNotContain("refresh,");
    assertThatThrownBy(() -> SecureTokens.decode("***"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
