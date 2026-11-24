package com.vitadelta.api.identity;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.vitadelta.api.config.properties.SecretsProperties;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Access-token signing and validation (section 8.1, ADR-0006): HS256 over {@code
 * VITADELTA_JWT_SECRET}, header {@code kid} = first 16 hex characters of the SHA-256 of the secret.
 * The decoder accepts the current secret and, during a rotation, {@code
 * VITADELTA_JWT_SECRET_PREVIOUS}, selected by {@code kid}; a token with a missing or unknown {@code
 * kid} is rejected. {@code SecurityConfig} enables the resource-server filter with this decoder.
 */
@Configuration(proxyBeanMethods = false)
public class JwtConfig {

  /** Number of hex characters of the key id. */
  public static final int KEY_ID_LENGTH = 16;

  /** The key id of {@code secret}. */
  public static String keyId(byte[] secret) {
    return SecureTokens.sha256Hex(secret).substring(0, KEY_ID_LENGTH);
  }

  /** Nimbus encoder over the current secret; the JWK carries the key id. */
  @Bean
  JwtEncoder accessTokenEncoder(SecretsProperties secrets) {
    byte[] secret = secrets.jwtSecretBytes();
    OctetSequenceKey key =
        new OctetSequenceKey.Builder(secret)
            .keyID(keyId(secret))
            .algorithm(JWSAlgorithm.HS256)
            .build();
    return new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(key)));
  }

  @Bean
  AccessTokenService accessTokenService(
      JwtEncoder accessTokenEncoder, SecretsProperties secrets, Clock clock) {
    return new AccessTokenService(accessTokenEncoder, keyId(secrets.jwtSecretBytes()), clock);
  }

  /**
   * The resource-server decoder. Marked {@code @Fallback} so that a decoder declared elsewhere (for
   * example a test configuration) is the unique candidate {@code SecurityConfig} picks up.
   */
  @Bean
  @ConditionalOnMissingBean(JwtDecoder.class)
  @Fallback
  JwtDecoder identityJwtDecoder(SecretsProperties secrets, Clock clock) {
    Map<String, JwtDecoder> decoders = new LinkedHashMap<>();
    decoders.put(keyId(secrets.jwtSecretBytes()), decoder(secrets.jwtSecretBytes(), clock));
    byte[] previous = secrets.jwtSecretPreviousBytes();
    if (previous != null) {
      decoders.put(keyId(previous), decoder(previous, clock));
    }
    return new KeyIdJwtDecoder(decoders);
  }

  static JwtDecoder decoder(byte[] secret, Clock clock) {
    SecretKey key = new SecretKeySpec(secret, "HmacSHA256");
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    JwtTimestampValidator timestamps = new JwtTimestampValidator();
    timestamps.setClock(clock);
    OAuth2TokenValidator<Jwt> validator =
        new DelegatingOAuth2TokenValidator<>(
            timestamps,
            new JwtClaimValidator<Object>(JwtClaimNames.EXP, Objects::nonNull),
            new JwtClaimValidator<Object>(JwtClaimNames.SUB, Objects::nonNull),
            new JwtClaimValidator<Object>(AccessTokenService.ROLE, Objects::nonNull));
    decoder.setJwtValidator(validator);
    return decoder;
  }
}
