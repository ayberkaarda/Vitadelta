package com.vitadelta.api.identity;

import static com.vitadelta.api.identity.UserRepository.instant;
import static com.vitadelta.api.identity.UserRepository.ts;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Refresh tokens (section 8.1, ADR-0006): 256-bit random values stored as SHA-256 hashes, grouped
 * in families. Rotation revokes the presented token with {@code replaced_by} and issues a successor
 * in the same family. Within {@link #GRACE_WINDOW} after a rotation the same successor is returned
 * again (its plaintext is kept AES-GCM encrypted on the predecessor row only for the window);
 * presenting a revoked token outside the window, or one whose successor was itself rotated or
 * revoked, revokes the whole family.
 */
@Service
public class RefreshTokenService {

  private static final Logger LOG = LoggerFactory.getLogger(RefreshTokenService.class);

  /** Lifetime of a refresh token. */
  public static final Duration LIFETIME = AuthCookies.REFRESH_MAX_AGE;

  /** A token rotated less than this long ago returns the same successor. */
  public static final Duration GRACE_WINDOW = Duration.ofSeconds(20);

  private static final int USER_AGENT_MAX = 255;

  private final JdbcClient jdbc;
  private final UserRepository users;
  private final GraceCipher cipher;
  private final Clock clock;
  private final TransactionTemplate transactions;

  public RefreshTokenService(
      JdbcClient jdbc,
      UserRepository users,
      GraceCipher cipher,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.users = users;
    this.cipher = cipher;
    this.clock = clock;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /** Outcome of {@link #rotate}. */
  public sealed interface Rotation permits Rotated, Rejected {}

  /**
   * The session continues.
   *
   * @param refreshToken the token to set as cookie (a new successor, or the same successor within
   *     the grace window)
   */
  public record Rotated(UserAccount user, String refreshToken) implements Rotation {

    @Override
    public String toString() {
      return "Rotated[user=" + user.id() + ", refreshToken=<redacted>]";
    }
  }

  /** The presented token cannot continue the session. */
  public record Rejected(Reason reason) implements Rotation {}

  /** Why a token was rejected. */
  public enum Reason {
    /** No such token. */
    UNKNOWN,
    /** The token's lifetime has ended. */
    EXPIRED,
    /** A revoked token was presented outside the grace window; the family is now revoked. */
    REUSED,
    /** The account is disabled. */
    DISABLED
  }

  private record TokenRow(
      UUID id,
      UUID userId,
      UUID familyId,
      Instant expiresAt,
      Instant revokedAt,
      UUID replacedBy,
      String graceSuccessorEnc) {}

  private static TokenRow map(ResultSet rs, int row) throws SQLException {
    return new TokenRow(
        rs.getObject("id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getObject("family_id", UUID.class),
        instant(rs, "expires_at"),
        instant(rs, "revoked_at"),
        rs.getObject("replaced_by", UUID.class),
        rs.getString("grace_successor_enc"));
  }

  /**
   * Issues the first token of a new family (login). Runs in the caller's transaction, which must
   * hold the user-row lock ({@link UserRepository#findByIdForUpdate}).
   */
  public String issueNewFamily(UUID userId, String userAgent) {
    Instant now = clock.instant();
    clearExpiredGraceSuccessors(userId, now);
    String token = SecureTokens.newToken();
    insert(UUID.randomUUID(), userId, UUID.randomUUID(), token, userAgent, now);
    return token;
  }

  /** Clears the encrypted successors of the user whose grace window has ended. */
  private void clearExpiredGraceSuccessors(UUID userId, Instant now) {
    jdbc.sql(
            "UPDATE refresh_token SET grace_successor_enc = NULL WHERE user_id = ?"
                + " AND grace_successor_enc IS NOT NULL AND revoked_at <= ?")
        .params(userId, ts(now.minus(GRACE_WINDOW)))
        .update();
  }

  /** The owner of a token, read without a lock (the caller locks the user row next). */
  private Optional<UUID> ownerOf(String hash) {
    return jdbc.sql("SELECT user_id FROM refresh_token WHERE token_hash = ?")
        .param(hash)
        .query(UUID.class)
        .optional();
  }

  private void insert(
      UUID id, UUID userId, UUID familyId, String token, String userAgent, Instant now) {
    jdbc.sql(
            "INSERT INTO refresh_token (id, user_id, token_hash, family_id, expires_at, user_agent,"
                + " created_at) VALUES (?, ?, ?, ?, ?, ?, ?)")
        .params(
            id,
            userId,
            SecureTokens.sha256Hex(token),
            familyId,
            ts(now.plus(LIFETIME)),
            truncate(userAgent),
            ts(now))
        .update();
  }

  private static String truncate(String userAgent) {
    if (userAgent == null || userAgent.isBlank()) {
      return null;
    }
    return userAgent.length() <= USER_AGENT_MAX
        ? userAgent
        : userAgent.substring(0, USER_AGENT_MAX);
  }

  /**
   * Rotates the presented token in its own transaction. Rotation and every revocation (logout,
   * reuse detection, disable, password reset) first lock the owner's {@code app_user} row and only
   * then read or change tokens, so a revocation can never miss a successor of a concurrent,
   * uncommitted rotation. Concurrent refreshes with the same token are serialized: the first
   * rotates, the others receive the same successor within the grace window. A detected reuse
   * commits the family revocation.
   */
  public Rotation rotate(String presented, String userAgent) {
    if (!SecureTokens.isWellFormed(presented)) {
      return new Rejected(Reason.UNKNOWN);
    }
    String hash = SecureTokens.sha256Hex(presented);
    Rotation result = transactions.execute(status -> rotateLocked(hash, userAgent));
    if (result instanceof Rejected(Reason reason) && reason == Reason.REUSED) {
      LOG.warn("Refresh token reuse detected; the token family was revoked");
    }
    return result;
  }

  private Rotation rotateLocked(String hash, String userAgent) {
    Optional<UUID> owner = ownerOf(hash);
    if (owner.isEmpty()) {
      return new Rejected(Reason.UNKNOWN);
    }
    Optional<UserAccount> user = users.findByIdForUpdate(owner.get());
    if (user.isEmpty()) {
      return new Rejected(Reason.UNKNOWN);
    }
    Optional<TokenRow> found =
        jdbc.sql(
                "SELECT id, user_id, family_id, expires_at, revoked_at, replaced_by,"
                    + " grace_successor_enc FROM refresh_token WHERE token_hash = ? FOR UPDATE")
            .param(hash)
            .query(RefreshTokenService::map)
            .optional();
    if (found.isEmpty()) {
      return new Rejected(Reason.UNKNOWN);
    }
    TokenRow token = found.get();
    if (user.get().status() == UserStatus.DISABLED) {
      return new Rejected(Reason.DISABLED);
    }
    Instant now = clock.instant();
    if (token.revokedAt() == null) {
      if (!token.expiresAt().isAfter(now)) {
        return new Rejected(Reason.EXPIRED);
      }
      clearExpiredGraceSuccessors(token.userId(), now);
      String successor = SecureTokens.newToken();
      UUID successorId = UUID.randomUUID();
      insert(successorId, token.userId(), token.familyId(), successor, userAgent, now);
      jdbc.sql(
              "UPDATE refresh_token SET revoked_at = ?, replaced_by = ?, grace_successor_enc = ?"
                  + " WHERE id = ?")
          .params(
              ts(now), successorId, cipher.encrypt(successor, token.id().toString()), token.id())
          .update();
      return new Rotated(user.get(), successor);
    }
    Optional<String> graceSuccessor = graceSuccessor(token, now);
    if (graceSuccessor.isPresent()) {
      return new Rotated(user.get(), graceSuccessor.get());
    }
    revokeFamily(token.familyId(), now);
    return new Rejected(Reason.REUSED);
  }

  private Optional<String> graceSuccessor(TokenRow token, Instant now) {
    if (token.replacedBy() == null
        || token.graceSuccessorEnc() == null
        || !now.isBefore(token.revokedAt().plus(GRACE_WINDOW))) {
      return Optional.empty();
    }
    Optional<TokenRow> successor =
        jdbc.sql(
                "SELECT id, user_id, family_id, expires_at, revoked_at, replaced_by,"
                    + " grace_successor_enc FROM refresh_token WHERE id = ? FOR UPDATE")
            .param(token.replacedBy())
            .query(RefreshTokenService::map)
            .optional();
    if (successor.isEmpty()
        || successor.get().revokedAt() != null
        || !successor.get().expiresAt().isAfter(now)) {
      return Optional.empty();
    }
    return cipher.decrypt(token.graceSuccessorEnc(), token.id().toString());
  }

  private void revokeFamily(UUID familyId, Instant now) {
    jdbc.sql(
            "UPDATE refresh_token SET revoked_at = coalesce(revoked_at, ?),"
                + " grace_successor_enc = NULL WHERE family_id = ?")
        .params(ts(now), familyId)
        .update();
  }

  /**
   * Revokes the family of the presented token (logout). Unknown or malformed tokens are ignored.
   *
   * @return whether a family was found
   */
  public boolean revokeFamilyOf(String presented) {
    if (!SecureTokens.isWellFormed(presented)) {
      return false;
    }
    String hash = SecureTokens.sha256Hex(presented);
    Boolean revoked =
        transactions.execute(status -> {
          Optional<UUID> owner = ownerOf(hash);
          if (owner.isEmpty() || users.findByIdForUpdate(owner.get()).isEmpty()) {
            return false;
          }
          Optional<UUID> family =
              jdbc.sql("SELECT family_id FROM refresh_token WHERE token_hash = ?")
                  .param(hash)
                  .query(UUID.class)
                  .optional();
          family.ifPresent(id -> revokeFamily(id, clock.instant()));
          return family.isPresent();
        });
    return Boolean.TRUE.equals(revoked);
  }

  /**
   * Revokes every family of the user (disable, password reset). Runs in the caller's transaction;
   * takes the user-row lock first (a no-op when the caller already holds it).
   *
   * @return number of tokens changed
   */
  public int revokeAllForUser(UUID userId) {
    users.findByIdForUpdate(userId);
    return jdbc.sql(
            "UPDATE refresh_token SET revoked_at = coalesce(revoked_at, ?),"
                + " grace_successor_enc = NULL"
                + " WHERE user_id = ? AND (revoked_at IS NULL OR grace_successor_enc IS NOT NULL)")
        .params(ts(clock.instant()), userId)
        .update();
  }
}
