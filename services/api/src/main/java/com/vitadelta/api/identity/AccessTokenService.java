package com.vitadelta.api.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/**
 * Issues access tokens: HS256 JWT with header {@code kid}, lifetime {@link #LIFETIME}, claims
 * {@code sub} (user id), {@code role}, {@code email_verified} and {@code jti}. There is no plan
 * claim; the plan is always read from the database.
 */
public class AccessTokenService {

  /** Access-token lifetime. */
  public static final Duration LIFETIME = Duration.ofMinutes(15);

  /** Role claim ({@code USER} or {@code ADMIN}). */
  public static final String ROLE = "role";

  /** E-mail verification claim. */
  public static final String EMAIL_VERIFIED = "email_verified";

  private final JwtEncoder encoder;
  private final String keyId;
  private final Clock clock;

  AccessTokenService(JwtEncoder encoder, String keyId, Clock clock) {
    this.encoder = encoder;
    this.keyId = keyId;
    this.clock = clock;
  }

  /** The key id of the current signing secret. */
  public String keyId() {
    return keyId;
  }

  /** A signed access token for the user. */
  public IssuedAccessToken issue(UUID userId, Role role, boolean emailVerified) {
    Instant now = clock.instant();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .subject(userId.toString())
            .claim(ROLE, role.name())
            .claim(EMAIL_VERIFIED, emailVerified)
            .id(UUID.randomUUID().toString())
            .issuedAt(now)
            .expiresAt(now.plus(LIFETIME))
            .build();
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).keyId(keyId).build();
    String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    return new IssuedAccessToken(token, LIFETIME.toSeconds());
  }

  /**
   * An issued access token.
   *
   * @param value the compact JWT
   * @param expiresIn lifetime in seconds
   */
  public record IssuedAccessToken(String value, long expiresIn) {

    @Override
    public String toString() {
      return "IssuedAccessToken[value=<redacted>, expiresIn=" + expiresIn + "]";
    }
  }
}
