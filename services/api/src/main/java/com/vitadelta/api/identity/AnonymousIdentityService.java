package com.vitadelta.api.identity;

import static com.vitadelta.api.identity.UserRepository.ts;

import com.vitadelta.api.config.properties.SecretsProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The anonymous principal (section 8.1): cookie {@code vd_anon} = {@code <id>.<signature>}, where
 * {@code id} is a 128-bit random value and {@code signature} its HMAC-SHA256 under {@code
 * VITADELTA_COOKIE_SECRET}, both base64url. The server row {@code anonymous_identity} is keyed by
 * the SHA-256 of the id and created with {@code INSERT ... ON CONFLICT (id_hash) DO NOTHING}, so
 * concurrent first requests never fail or create two rows. A missing or badly signed cookie is
 * replaced by a new identity.
 */
@Service
public class AnonymousIdentityService {

  private static final int ID_BYTES = 16;

  private final JdbcClient jdbc;
  private final AuthCookies cookies;
  private final byte[] signingKey;
  private final Clock clock;

  public AnonymousIdentityService(
      JdbcClient jdbc, AuthCookies cookies, SecretsProperties secrets, Clock clock) {
    this.jdbc = jdbc;
    this.cookies = cookies;
    this.signingKey = secrets.cookieSecretBytes();
    this.clock = clock;
  }

  /**
   * The anonymous identity of a request.
   *
   * @param idHash SHA-256 (hex) of the identity id, the key of {@code anonymous_identity}
   * @param created whether the identity was created by this request (the cookie was set)
   */
  public record AnonymousIdentity(String idHash, boolean created) {}

  /** The identity hash of a validly signed {@code vd_anon} cookie, if any. */
  public Optional<String> currentIdHash(HttpServletRequest request) {
    return AuthCookies.read(request, AuthCookies.ANONYMOUS).flatMap(this::verify);
  }

  /**
   * Resolves the identity of the request, creating it (row and {@code Set-Cookie}) when the cookie
   * is missing or invalid, and records that it was seen now.
   */
  public AnonymousIdentity resolve(HttpServletRequest request, HttpServletResponse response) {
    Optional<String> existing = currentIdHash(request);
    boolean created = existing.isEmpty();
    String idHash;
    if (created) {
      byte[] id = SecureTokens.randomBytes(ID_BYTES);
      idHash = SecureTokens.sha256Hex(id);
      response.addHeader(HttpHeaders.SET_COOKIE, cookies.anonymous(sign(id)));
    } else {
      idHash = existing.get();
    }
    ensureRow(idHash, clock.instant());
    return new AnonymousIdentity(idHash, created);
  }

  /** Creates the row if it does not exist yet. */
  void ensureRow(String idHash, Instant now) {
    jdbc.sql(
            "INSERT INTO anonymous_identity (id_hash, queries_used, first_seen_at, last_seen_at)"
                + " VALUES (?, 0, ?, ?) ON CONFLICT (id_hash) DO NOTHING")
        .params(idHash, ts(now), ts(now))
        .update();
  }

  /**
   * Links the identity to the account created from it (registration). Runs in the caller's
   * transaction; an identity that is already converted is left unchanged.
   */
  public void convert(String idHash, UUID userId) {
    jdbc.sql(
            "UPDATE anonymous_identity SET converted_user_id = ?, last_seen_at = ?"
                + " WHERE id_hash = ? AND converted_user_id IS NULL")
        .params(userId, ts(clock.instant()), idHash)
        .update();
  }

  /** The cookie value of the identity id. */
  String sign(byte[] id) {
    String encodedId = SecureTokens.encode(id);
    return encodedId
        + "."
        + SecureTokens.encode(
            SecureTokens.hmacSha256(signingKey, encodedId.getBytes(StandardCharsets.US_ASCII)));
  }

  /** The identity hash of a correctly signed cookie value. */
  Optional<String> verify(String value) {
    int dot = value.indexOf('.');
    if (dot < 1 || dot != value.lastIndexOf('.')) {
      return Optional.empty();
    }
    String encodedId = value.substring(0, dot);
    byte[] id;
    byte[] signature;
    try {
      id = SecureTokens.decode(encodedId);
      signature = SecureTokens.decode(value.substring(dot + 1));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    if (id.length != ID_BYTES) {
      return Optional.empty();
    }
    byte[] expected =
        SecureTokens.hmacSha256(signingKey, encodedId.getBytes(StandardCharsets.US_ASCII));
    if (!MessageDigest.isEqual(expected, signature) || !encodedId.equals(SecureTokens.encode(id))) {
      return Optional.empty();
    }
    return Optional.of(SecureTokens.sha256Hex(id));
  }
}
