package com.vitadelta.api.identity;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Argon2id password hashing (ADR-0006) through Spring Security's {@link Argon2PasswordEncoder} on
 * BouncyCastle with explicit parameters, never the library defaults: salt 16 bytes, hash 32 bytes,
 * parallelism 1, memory 19456 KiB, 2 iterations.
 *
 * <p>{@link #verifyAgainstDummy(CharSequence)} verifies a password against a fixed dummy hash with
 * the same parameters, so that a login for an unknown e-mail costs the same time as one for a known
 * e-mail.
 */
@Component
public class PasswordHasher {

  public static final int SALT_LENGTH = 16;
  public static final int HASH_LENGTH = 32;
  public static final int PARALLELISM = 1;
  public static final int MEMORY_KIB = 19456;
  public static final int ITERATIONS = 2;

  private final Argon2PasswordEncoder encoder =
      new Argon2PasswordEncoder(SALT_LENGTH, HASH_LENGTH, PARALLELISM, MEMORY_KIB, ITERATIONS);
  private final String dummyHash = encoder.encode(SecureTokens.newToken());

  /** The Argon2id hash of {@code password} in PHC string format. */
  public String hash(CharSequence password) {
    return encoder.encode(password);
  }

  /** Whether {@code password} matches {@code hash}. */
  public boolean matches(CharSequence password, String hash) {
    return encoder.matches(password, hash);
  }

  /** Verifies {@code password} against the dummy hash; always false. */
  public boolean verifyAgainstDummy(CharSequence password) {
    return encoder.matches(password, dummyHash);
  }
}
