package com.vitadelta.api.billing;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Generates promo codes (section 8.8): the optional prefix (upper case) and a hyphen, followed by
 * 16 characters of Crockford base32 (alphabet {@code 0-9A-Z} without {@code I L O U}) from a
 * CSPRNG: 80 bits of entropy.
 */
@Component
public class PromoCodeGenerator {

  /** Crockford base32 alphabet. */
  static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

  /** Random characters of every code. */
  public static final int RANDOM_LENGTH = 16;

  /** Allowed prefix (contract schema {@code CreatePromoCodeRequest.prefix}). */
  public static final String PREFIX_REGEX = "^[A-Za-z0-9]{1,16}$";

  /** Every code (contract schema {@code PromoCodeValue}). */
  public static final String CODE_REGEX =
      "^([A-Za-z0-9]{1,16}-)?[0-9A-HJKMNP-TV-Za-hjkmnp-tv-z]{16}$";

  private static final Pattern PREFIX = Pattern.compile(PREFIX_REGEX);

  private final SecureRandom random = new SecureRandom();

  /**
   * A new code.
   *
   * @param prefix optional human-readable prefix ({@code null} for none)
   * @throws IllegalArgumentException when the prefix does not match {@link #PREFIX_REGEX}
   */
  public String generate(String prefix) {
    if (prefix != null && !PREFIX.matcher(prefix).matches()) {
      throw new IllegalArgumentException("invalid promo code prefix");
    }
    byte[] bytes = new byte[RANDOM_LENGTH * 5 / 8];
    random.nextBytes(bytes);
    StringBuilder code = new StringBuilder(40);
    if (prefix != null) {
      code.append(prefix.toUpperCase(Locale.ROOT)).append('-');
    }
    long buffer = 0;
    int bits = 0;
    for (byte value : bytes) {
      buffer = (buffer << 8) | (value & 0xFF);
      bits += 8;
      while (bits >= 5) {
        bits -= 5;
        code.append(ALPHABET.charAt((int) ((buffer >>> bits) & 0x1F)));
      }
    }
    return code.toString();
  }
}
