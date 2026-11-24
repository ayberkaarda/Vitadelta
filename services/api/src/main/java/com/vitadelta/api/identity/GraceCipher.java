package com.vitadelta.api.identity;

import com.vitadelta.api.config.properties.SecretsProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM encryption of the successor refresh token kept during the grace window (section 8.1).
 * The key is HMAC-SHA256 of a fixed label under {@code VITADELTA_COOKIE_SECRET}, so it is distinct
 * from the anonymous-cookie signing key. The predecessor token id is bound as associated data, so a
 * ciphertext copied to another row does not decrypt. Output: base64url of {@code iv || ciphertext}.
 */
@Component
public class GraceCipher {

  private static final byte[] LABEL =
      "vitadelta/refresh-grace-successor/v1".getBytes(StandardCharsets.UTF_8);
  private static final int IV_LENGTH = 12;
  private static final int TAG_BITS = 128;

  private final SecretKey key;

  public GraceCipher(SecretsProperties secrets) {
    this.key =
        new SecretKeySpec(SecureTokens.hmacSha256(secrets.cookieSecretBytes(), LABEL), "AES");
  }

  /** Encrypts {@code token}, binding it to {@code associatedData}. */
  public String encrypt(String token, String associatedData) {
    byte[] iv = SecureTokens.randomBytes(IV_LENGTH);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
      byte[] ciphertext = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
      return SecureTokens.encode(
          ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("AES-GCM encryption failed", e);
    }
  }

  /** Decrypts a value of {@link #encrypt}; empty when it was altered or bound to other data. */
  public Optional<String> decrypt(String encrypted, String associatedData) {
    try {
      byte[] bytes = SecureTokens.decode(encrypted);
      if (bytes.length <= IV_LENGTH) {
        return Optional.empty();
      }
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, bytes, 0, IV_LENGTH));
      cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
      byte[] plain = cipher.doFinal(bytes, IV_LENGTH, bytes.length - IV_LENGTH);
      return Optional.of(new String(plain, StandardCharsets.UTF_8));
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
