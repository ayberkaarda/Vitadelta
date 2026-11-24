package com.vitadelta.api.identity;

import static com.vitadelta.api.identity.UserRepository.instant;
import static com.vitadelta.api.identity.UserRepository.ts;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Single-use e-mail tokens (section 8.2): 256-bit CSPRNG values, base64url, stored as SHA-256
 * hashes; {@code VERIFY_EMAIL} valid 24 hours, {@code RESET_PASSWORD} 1 hour. Issuing a token marks
 * every older unused token of the same kind and user as used. All methods run in the caller's
 * transaction.
 */
@Service
public class EmailTokenService {

  /** Kind of an e-mail token. */
  public enum Kind {
    VERIFY_EMAIL(Duration.ofHours(24)),
    RESET_PASSWORD(Duration.ofHours(1));

    private final Duration lifetime;

    Kind(Duration lifetime) {
      this.lifetime = lifetime;
    }

    public Duration lifetime() {
      return lifetime;
    }
  }

  /** Result of {@link #consume}. */
  public sealed interface Consumption permits Consumed, Invalid, Expired {}

  /** The token was valid and is now used. */
  public record Consumed(UUID userId) implements Consumption {}

  /** Unknown, already used, or of another kind. */
  public record Invalid() implements Consumption {}

  /** Known and unused, but past its lifetime. */
  public record Expired() implements Consumption {}

  private final JdbcClient jdbc;

  public EmailTokenService(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Invalidates older tokens of the kind and issues a new one; returns the raw token. */
  public String issue(UUID userId, Kind kind, Instant now) {
    invalidateAll(userId, kind, now);
    String token = SecureTokens.newToken();
    jdbc.sql(
            "INSERT INTO email_token (id, user_id, kind, token_hash, expires_at, created_at)"
                + " VALUES (?, ?, ?, ?, ?, ?)")
        .params(
            UUID.randomUUID(),
            userId,
            kind.name(),
            SecureTokens.sha256Hex(token),
            ts(now.plus(kind.lifetime())),
            ts(now))
        .update();
    return token;
  }

  /** Marks every unused token of the kind and user as used. */
  public int invalidateAll(UUID userId, Kind kind, Instant now) {
    return jdbc.sql(
            "UPDATE email_token SET used_at = ? WHERE user_id = ? AND kind = ? AND used_at IS NULL")
        .params(ts(now), userId, kind.name())
        .update();
  }

  private record Row(UUID id, UUID userId, Instant expiresAt, Instant usedAt) {}

  /** Consumes the token (row locked, so concurrent uses yield one success). */
  public Consumption consume(String token, Kind kind, Instant now) {
    if (!SecureTokens.isWellFormed(token)) {
      return new Invalid();
    }
    Optional<Row> row =
        jdbc.sql(
                "SELECT id, user_id, expires_at, used_at FROM email_token"
                    + " WHERE token_hash = ? AND kind = ? FOR UPDATE")
            .params(SecureTokens.sha256Hex(token), kind.name())
            .query((rs, n) ->
                new Row(
                    rs.getObject("id", UUID.class),
                    rs.getObject("user_id", UUID.class),
                    instant(rs, "expires_at"),
                    instant(rs, "used_at")))
            .optional();
    if (row.isEmpty() || row.get().usedAt() != null) {
      return new Invalid();
    }
    if (!row.get().expiresAt().isAfter(now)) {
      return new Expired();
    }
    jdbc.sql("UPDATE email_token SET used_at = ? WHERE id = ?")
        .params(ts(now), row.get().id())
        .update();
    return new Consumed(row.get().userId());
  }
}
