package com.vitadelta.api.config.properties;

import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Signing and encryption secrets, bound from {@code VITADELTA_JWT_SECRET}, {@code
 * VITADELTA_JWT_SECRET_PREVIOUS} and {@code VITADELTA_COOKIE_SECRET}. The application refuses to
 * start when the JWT or cookie secret is missing or shorter than {@value #MIN_SECRET_BYTES} bytes
 * (UTF-8). The previous JWT secret is optional and only set during a rotation.
 *
 * <p>{@link #toString()} never reveals a secret.
 *
 * @param jwtSecret HS256 signing secret of access tokens
 * @param jwtSecretPrevious previous signing secret accepted during a rotation, or {@code null}
 * @param cookieSecret HMAC secret of the anonymous cookie and key material for refresh-token grace
 *     encryption
 */
@ConfigurationProperties(prefix = "vitadelta.secrets")
public record SecretsProperties(String jwtSecret, String jwtSecretPrevious, String cookieSecret) {

  /** Minimum secret length in bytes. */
  public static final int MIN_SECRET_BYTES = 32;

  public SecretsProperties {
    jwtSecret = require(jwtSecret, "VITADELTA_JWT_SECRET");
    jwtSecretPrevious =
        jwtSecretPrevious == null || jwtSecretPrevious.isEmpty() ? null : jwtSecretPrevious;
    if (jwtSecretPrevious != null) {
      require(jwtSecretPrevious, "VITADELTA_JWT_SECRET_PREVIOUS");
      if (jwtSecretPrevious.equals(jwtSecret)) {
        throw new IllegalArgumentException(
            "VITADELTA_JWT_SECRET_PREVIOUS must differ from VITADELTA_JWT_SECRET");
      }
    }
    cookieSecret = require(cookieSecret, "VITADELTA_COOKIE_SECRET");
    if (cookieSecret.equals(jwtSecret)) {
      throw new IllegalArgumentException(
          "VITADELTA_COOKIE_SECRET must differ from VITADELTA_JWT_SECRET");
    }
  }

  private static String require(String value, String variable) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(
          variable + " is not set; it must hold at least " + MIN_SECRET_BYTES + " bytes");
    }
    if (value.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
      throw new IllegalArgumentException(
          variable + " is too short; it must hold at least " + MIN_SECRET_BYTES + " bytes");
    }
    return value;
  }

  /** UTF-8 bytes of the JWT signing secret (a copy). */
  public byte[] jwtSecretBytes() {
    return jwtSecret.getBytes(StandardCharsets.UTF_8);
  }

  /** UTF-8 bytes of the previous JWT signing secret, or {@code null} (a copy). */
  public byte[] jwtSecretPreviousBytes() {
    return jwtSecretPrevious == null ? null : jwtSecretPrevious.getBytes(StandardCharsets.UTF_8);
  }

  /** UTF-8 bytes of the cookie secret (a copy). */
  public byte[] cookieSecretBytes() {
    return cookieSecret.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public String toString() {
    return "SecretsProperties[jwtSecret=<redacted>, jwtSecretPrevious="
        + (jwtSecretPrevious == null ? "<unset>" : "<redacted>")
        + ", cookieSecret=<redacted>]";
  }
}
