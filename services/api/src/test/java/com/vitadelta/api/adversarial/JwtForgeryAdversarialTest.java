package com.vitadelta.api.adversarial;

import static com.vitadelta.api.adversarial.AttackClient.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.vitadelta.api.adversarial.AttackClient.Resp;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Section 15.7 case 1: forged, altered, expired and foreign-key access tokens are rejected with
 * {@code 401}; a genuine USER token never reaches {@code /admin/**} ({@code 403}).
 */
class JwtForgeryAdversarialTest extends AdversarialIntegrationTest {

  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder B64D = Base64.getUrlDecoder();

  private void assertRejected(String token) {
    for (String path : List.of(API + "/me", API + "/admin/users")) {
      Resp response = get(path, token);
      assertProblem(response, 401, "unauthorized");
      assertThat(response.header("www-authenticate")).startsWith("Bearer");
    }
  }

  private static String b64(String json) {
    return B64.encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }

  private UUID user() {
    return insertUser(true);
  }

  @Test
  void aGenuineTokenWorksSoTheRejectionsBelowAreMeaningful() {
    Resp me = get(API + "/me", accessToken(user(), "USER", true));
    assertThat(me.status()).as(me.toString()).isEqualTo(200);
  }

  @Test
  void tamperedSignatureIsRejected() {
    String token = accessToken(user(), "USER", true);
    int dot = token.lastIndexOf('.');
    char[] signature = token.substring(dot + 1).toCharArray();
    int middle = signature.length / 2;
    signature[middle] = signature[middle] == 'A' ? 'B' : 'A';
    assertRejected(token.substring(0, dot + 1) + new String(signature));
    assertRejected(token.substring(0, dot + 1));
  }

  @Test
  void algNoneIsRejectedWithAndWithoutKeyId() {
    UUID id = user();
    String claims =
        b64(
            "{\"sub\":\""
                + id
                + "\",\"role\":\"ADMIN\",\"email_verified\":true,\"jti\":\"x\",\"exp\":"
                + (clock.instant().getEpochSecond() + 600)
                + "}");
    assertRejected(
        b64("{\"alg\":\"none\",\"typ\":\"JWT\",\"kid\":\"" + currentKeyId() + "\"}")
            + "."
            + claims
            + ".");
    assertRejected(b64("{\"alg\":\"none\",\"typ\":\"JWT\"}") + "." + claims + ".");
    assertRejected(
        b64("{\"alg\":\"NONE\",\"kid\":\"" + currentKeyId() + "\"}") + "." + claims + ".");
  }

  @Test
  void expiredTokenIsRejected() {
    UUID id = user();
    String expired =
        sign(
            currentSecret(),
            currentKeyId(),
            JWSAlgorithm.HS256,
            claims(
                id.toString(),
                "USER",
                true,
                clock.instant().minus(Duration.ofMinutes(20)),
                Duration.ofMinutes(15)));
    assertRejected(expired);

    String issued = accessToken(id, "USER", true);
    assertThat(get(API + "/me", issued).status()).isEqualTo(200);
    clock.advance(Duration.ofMinutes(17));
    assertRejected(issued);
  }

  @Test
  void editedRoleClaimFailsSignatureValidation() {
    UUID id = user();
    String token = accessToken(id, "USER", true);
    String[] parts = token.split("\\.");
    String payload = new String(B64D.decode(parts[1]), StandardCharsets.UTF_8);
    assertThat(payload).contains("\"role\":\"USER\"");
    String forged =
        parts[0]
            + "."
            + b64(payload.replace("\"role\":\"USER\"", "\"role\":\"ADMIN\""))
            + "."
            + parts[2];
    assertRejected(forged);
    String otherSubject =
        parts[0]
            + "."
            + b64(payload.replace(id.toString(), UUID.randomUUID().toString()))
            + "."
            + parts[2];
    assertRejected(otherSubject);
  }

  @Test
  void userTokenCannotReachAdminRoutes() {
    UUID id = user();
    String token = accessToken(id, "USER", true);
    assertProblem(get(API + "/admin/users", token), 403, "forbidden");
    assertProblem(get(API + "/admin/promo-codes", token), 403, "forbidden");
    assertProblem(get(API + "/admin/health", token), 403, "forbidden");
    assertProblem(
        req("POST", API + "/admin/promo-codes")
            .bearer(token)
            .json(Map.of("planCode", "PRO", "durationDays", 30, "maxRedemptions", 100))
            .send(),
        403,
        "forbidden");
    assertProblem(
        req("POST", API + "/admin/users/" + id + "/subscription")
            .bearer(token)
            .json(Map.of("planCode", "PRO", "durationDays", 3650))
            .send(),
        403,
        "forbidden");
    assertProblem(
        req("POST", API + "/admin/users/" + id + "/status")
            .bearer(token)
            .json(Map.of("status", "ACTIVE"))
            .send(),
        403,
        "forbidden");
    assertThat(count("SELECT count(*) FROM promo_code WHERE max_redemptions = 100")).isZero();
    assertThat(
            count("SELECT count(*) FROM subscription WHERE user_id = ? AND plan_code = 'PRO'", id))
        .isZero();
  }

  @Test
  void unknownOrMissingKeyIdIsRejectedEvenWithTheRightSecret() {
    UUID id = user();
    var claims = claims(id.toString(), "USER", true, clock.instant(), Duration.ofMinutes(15));
    assertRejected(sign(currentSecret(), "0123456789abcdef", JWSAlgorithm.HS256, claims));
    assertRejected(sign(currentSecret(), null, JWSAlgorithm.HS256, claims));
    assertRejected(sign(currentSecret(), currentKeyId() + "00", JWSAlgorithm.HS256, claims));
  }

  @Test
  void tokenSignedWithAPreviousSecretIsRejectedWhenNoPreviousSecretIsConfigured() {
    UUID id = user();
    byte[] previous = randomSecret(48).getBytes(StandardCharsets.UTF_8);
    var claims = claims(id.toString(), "ADMIN", true, clock.instant(), Duration.ofMinutes(15));
    assertRejected(
        sign(
            previous,
            com.vitadelta.api.identity.JwtConfig.keyId(previous),
            JWSAlgorithm.HS256,
            claims));
    assertRejected(sign(previous, currentKeyId(), JWSAlgorithm.HS256, claims));
  }

  @Test
  void otherMacAlgorithmsWithTheRightSecretAreRejected() {
    UUID id = user();
    var claims = claims(id.toString(), "USER", true, clock.instant(), Duration.ofMinutes(15));
    assertRejected(sign(currentSecret(), currentKeyId(), JWSAlgorithm.HS512, claims));
    assertRejected(sign(currentSecret(), currentKeyId(), JWSAlgorithm.HS384, claims));
  }

  @Test
  void tokensWithoutRequiredClaimsAreRejected() {
    UUID id = user();
    assertRejected(
        sign(
            currentSecret(),
            currentKeyId(),
            JWSAlgorithm.HS256,
            claims(id.toString(), null, true, clock.instant(), Duration.ofMinutes(15))));
    assertRejected(
        sign(
            currentSecret(),
            currentKeyId(),
            JWSAlgorithm.HS256,
            claims(null, "USER", true, clock.instant(), Duration.ofMinutes(15))));
  }

  @Test
  void validlySignedTokensForMissingOrMalformedSubjectsNeverServeData() {
    String missing = accessToken(UUID.randomUUID(), "USER", true);
    assertProblem(get(API + "/me", missing), 401, "unauthorized");
    assertProblem(get(API + "/session/quota", missing), 401, "unauthorized");
    String malformed =
        sign(
            currentSecret(),
            currentKeyId(),
            JWSAlgorithm.HS256,
            claims("admin", "USER", true, clock.instant(), Duration.ofMinutes(15)));
    assertProblem(get(API + "/me", malformed), 401, "unauthorized");
  }

  @Test
  void garbageAndQueryParameterTokensAreNotAccepted() {
    assertRejected("not.a.jwt");
    assertRejected("x".repeat(4000));
    String token = accessToken(user(), "USER", true);
    Resp viaQuery = req("GET", API + "/me?access_token=" + token).send();
    assertProblem(viaQuery, 401, "unauthorized");
    Resp basic = req("GET", API + "/me").header("Authorization", "Basic YWRtaW46YWRtaW4=").send();
    assertProblem(basic, 401, "unauthorized");
  }
}
