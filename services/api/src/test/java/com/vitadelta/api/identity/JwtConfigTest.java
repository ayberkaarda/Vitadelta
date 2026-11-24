package com.vitadelta.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import com.vitadelta.api.config.properties.SecretsProperties;
import com.vitadelta.api.support.TestEnvironment;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Access-token claims, {@code kid} and secret rotation (section 8.1, ADR-0006). */
class JwtConfigTest {

  private final String current = TestEnvironment.randomHex(32);
  private final String previous = TestEnvironment.randomHex(32);
  private final String cookie = TestEnvironment.randomHex(32);
  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-24T10:00:00Z"));
  private final JwtConfig config = new JwtConfig();

  private AccessTokenService issuer(String secret) {
    SecretsProperties secrets = new SecretsProperties(secret, null, cookie);
    return config.accessTokenService(config.accessTokenEncoder(secrets), secrets, clock);
  }

  private JwtDecoder decoder(String secret, String previousSecret) {
    return config.identityJwtDecoder(new SecretsProperties(secret, previousSecret, cookie), clock);
  }

  private static String sign(byte[] secret, String kid, JwtClaimsSet claims) {
    OctetSequenceKey key =
        new OctetSequenceKey.Builder(secret).keyID(kid).algorithm(JWSAlgorithm.HS256).build();
    return new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(key)))
        .encode(
            JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).keyId(kid).build(), claims))
        .getTokenValue();
  }

  @Test
  void tokensCarryTheSpecifiedClaimsAndNoPlan() throws Exception {
    UUID user = UUID.randomUUID();
    AccessTokenService.IssuedAccessToken token = issuer(current).issue(user, Role.ADMIN, true);
    assertThat(token.expiresIn()).isEqualTo(900);
    assertThat(token.toString()).doesNotContain(token.value());

    JWSObject parsed = JWSObject.parse(token.value());
    assertThat(parsed.getHeader().getAlgorithm().getName()).isEqualTo("HS256");
    assertThat(parsed.getHeader().getKeyID())
        .isEqualTo(SecureTokens.sha256Hex(current).substring(0, 16))
        .hasSize(16);

    Jwt jwt = decoder(current, null).decode(token.value());
    assertThat(jwt.getSubject()).isEqualTo(user.toString());
    assertThat(jwt.getClaimAsString("role")).isEqualTo("ADMIN");
    assertThat(jwt.getClaimAsBoolean("email_verified")).isTrue();
    assertThat(jwt.getId()).isNotBlank();
    assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()))
        .isEqualTo(Duration.ofMinutes(15));
    assertThat(jwt.getClaims().keySet())
        .containsExactlyInAnyOrder("sub", "role", "email_verified", "jti", "iat", "exp")
        .doesNotContain("plan");
  }

  @Test
  void thePreviousSecretIsAcceptedByKeyIdDuringARotation() {
    String old = issuer(previous).issue(UUID.randomUUID(), Role.USER, false).value();
    String fresh = issuer(current).issue(UUID.randomUUID(), Role.USER, false).value();

    JwtDecoder rotating = decoder(current, previous);
    assertThat(rotating.decode(old).getClaimAsString("role")).isEqualTo("USER");
    assertThat(rotating.decode(fresh).getClaimAsString("role")).isEqualTo("USER");

    JwtDecoder afterRotation = decoder(current, null);
    assertThatThrownBy(() -> afterRotation.decode(old))
        .isInstanceOf(JwtException.class)
        .hasMessageNotContaining(old);
  }

  @Test
  void unknownMissingOrForgedKeyIdsAndExpiredTokensAreRejected() {
    JwtDecoder decoder = decoder(current, null);
    SecretKeySpec key = new SecretKeySpec(current.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    NimbusJwtEncoder sameSecret = new NimbusJwtEncoder(new ImmutableSecret<>(key));
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .subject("x")
            .claim("role", "ADMIN")
            .issuedAt(clock.instant())
            .expiresAt(clock.instant().plusSeconds(60))
            .build();
    String withoutKid =
        sameSecret
            .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .getTokenValue();
    assertThatThrownBy(() -> decoder.decode(withoutKid)).isInstanceOf(JwtException.class);

    String unknownKid = sign(current.getBytes(StandardCharsets.UTF_8), "0123456789abcdef", claims);
    assertThatThrownBy(() -> decoder.decode(unknownKid)).isInstanceOf(JwtException.class);

    String forged =
        issuer(TestEnvironment.randomHex(32)).issue(UUID.randomUUID(), Role.ADMIN, true).value();
    assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(JwtException.class);
    String forgedWithCurrentKid =
        sign(
            TestEnvironment.randomHex(32).getBytes(StandardCharsets.UTF_8),
            JwtConfig.keyId(current.getBytes(StandardCharsets.UTF_8)),
            claims);
    assertThatThrownBy(() -> decoder.decode(forgedWithCurrentKid)).isInstanceOf(JwtException.class);
    assertThatThrownBy(() -> decoder.decode("not-a-jwt")).isInstanceOf(JwtException.class);

    String token = issuer(current).issue(UUID.randomUUID(), Role.USER, true).value();
    clock.advance(Duration.ofMinutes(17));
    assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
  }
}
