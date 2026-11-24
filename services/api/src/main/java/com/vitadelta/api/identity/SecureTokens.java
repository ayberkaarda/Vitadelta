package com.vitadelta.api.identity;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Random token values and hashes. Tokens are 256-bit CSPRNG values encoded as unpadded base64url
 * (43 characters); only their SHA-256 hashes (64 lowercase hex characters) are stored.
 */
public final class SecureTokens {

  /** Length of an encoded 256-bit token. */
  public static final int TOKEN_LENGTH = 43;

  private static final Pattern TOKEN_FORMAT = Pattern.compile("^[A-Za-z0-9_-]{43}$");
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  private SecureTokens() {}

  /** A new 256-bit token, base64url without padding. */
  public static String newToken() {
    return encode(randomBytes(32));
  }

  /** {@code length} random bytes. */
  public static byte[] randomBytes(int length) {
    byte[] bytes = new byte[length];
    RANDOM.nextBytes(bytes);
    return bytes;
  }

  /** Whether {@code value} has the shape of a token issued by {@link #newToken()}. */
  public static boolean isWellFormed(String value) {
    return value != null && TOKEN_FORMAT.matcher(value).matches();
  }

  /** SHA-256 of the UTF-8 bytes of {@code value} as lowercase hex. */
  public static String sha256Hex(String value) {
    return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
  }

  /** SHA-256 of {@code value} as lowercase hex. */
  public static String sha256Hex(byte[] value) {
    return HexFormat.of().formatHex(sha256(value));
  }

  /** SHA-256 of {@code value}. */
  public static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  /** HMAC-SHA256 of {@code data} under {@code key}. */
  public static byte[] hmacSha256(byte[] key, byte[] data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(data);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is not available", e);
    }
  }

  /** Unpadded base64url. */
  public static String encode(byte[] bytes) {
    return ENCODER.encodeToString(bytes);
  }

  /**
   * Decodes unpadded base64url.
   *
   * @throws IllegalArgumentException when {@code value} is not base64url
   */
  public static byte[] decode(String value) {
    return DECODER.decode(value);
  }
}
