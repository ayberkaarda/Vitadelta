package com.vitadelta.api.alerts;

import com.vitadelta.api.common.health.OutboxStats;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Statements on {@code outbox_event} (section 8.6, ADR-0008). Every statement uses bound
 * parameters; times come from the application clock so that tests can move them.
 */
@Repository
public class OutboxRepository {

  /** Aggregate of {@code OFFERS_CHANGED}: {@code <productId>:<countryCode>}. */
  static final String PRODUCT_COUNTRY = "PRODUCT_COUNTRY";

  /** Aggregate of {@code NOTIFICATION_CREATED}: the notification id. */
  static final String NOTIFICATION = "NOTIFICATION";

  private static final int MAX_ERROR_LENGTH = 500;

  private final JdbcClient jdbc;

  public OutboxRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * One claimed event.
   *
   * @param attempts failed attempts so far
   */
  public record ClaimedEvent(
      UUID id,
      OutboxEventType type,
      String aggregateId,
      String payload,
      int attempts,
      Instant createdAt,
      long seq) {}

  /** Inserts a due event; joins the caller's transaction. */
  public UUID insert(
      OutboxEventType type, String aggregateType, String aggregateId, String payload, Instant now) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO outbox_event (id, event_type, aggregate_type, aggregate_id, payload,"
                + " created_at, attempts, next_attempt_at)"
                + " VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, 0, ?)")
        .params(id, type.name(), aggregateType, aggregateId, payload, ts(now), ts(now))
        .update();
    return id;
  }

  /**
   * The claim statement. An {@code OFFERS_CHANGED} event is claimable only while no older
   * unprocessed event of the same (product, country) pair exists (older = lower {@code seq}, the
   * insertion order, which for one pair is the order of the committed catalog versions: imports
   * insert their events after the version bump, under its row lock). A failing event therefore
   * holds back only the later events of its own pair until it succeeds or is dead-lettered, and a
   * batch never contains two events of one pair. The anti-join probes the partial index {@code
   * outbox_event_pending_pair_idx}.
   */
  static final String CLAIM_SQL =
      "UPDATE outbox_event SET lease_until = :leaseUntil WHERE id IN ("
          + " SELECT e.id FROM outbox_event e WHERE e.processed_at IS NULL"
          + " AND e.next_attempt_at <= :now AND (e.lease_until IS NULL OR e.lease_until < :now)"
          + " AND (e.event_type <> 'OFFERS_CHANGED' OR NOT EXISTS ("
          + " SELECT 1 FROM outbox_event o WHERE o.processed_at IS NULL"
          + " AND o.event_type = 'OFFERS_CHANGED' AND o.aggregate_id = e.aggregate_id"
          + " AND o.seq < e.seq))"
          + " ORDER BY e.next_attempt_at, e.seq LIMIT :limit"
          + " FOR UPDATE OF e SKIP LOCKED)"
          + " RETURNING id, event_type, aggregate_id, payload::text AS payload, attempts,"
          + " created_at, seq";

  /**
   * Claims up to {@code limit} due events (not processed, due, lease absent or expired, and for
   * {@code OFFERS_CHANGED} no older pending event of the same pair, see {@link #CLAIM_SQL}) with
   * {@code FOR UPDATE SKIP LOCKED} and leases them until {@code leaseUntil}. Must run in its own
   * short transaction.
   *
   * @return the claimed events in insertion order
   */
  public List<ClaimedEvent> claim(Instant now, Instant leaseUntil, int limit) {
    List<ClaimedEvent> claimed =
        jdbc.sql(CLAIM_SQL)
            .param("leaseUntil", ts(leaseUntil))
            .param("now", ts(now))
            .param("limit", limit)
            .query(OutboxRepository::claimed)
            .list();
    return claimed.stream().sorted(Comparator.comparingLong(ClaimedEvent::seq)).toList();
  }

  /**
   * Locks a still pending event ({@code FOR UPDATE}); joins the caller's transaction. The e-mail
   * completion takes this lock before it touches the notification, the same order as the account
   * deletion (outbox events, then the notifications of the cascade), so the two never deadlock.
   *
   * @return whether the event exists and is still pending
   */
  public boolean lockPending(UUID id) {
    return jdbc.sql("SELECT id FROM outbox_event WHERE id = ? AND processed_at IS NULL FOR UPDATE")
        .param(id)
        .query(UUID.class)
        .optional()
        .isPresent();
  }

  /**
   * Marks a pending event processed with {@code outcome} and clears its lease.
   *
   * @return whether the event was still pending
   */
  public boolean markProcessed(UUID id, OutboxOutcome outcome, Instant now) {
    return jdbc.sql(
                "UPDATE outbox_event SET processed_at = ?, outcome = ?, lease_until = NULL"
                    + " WHERE id = ? AND processed_at IS NULL")
            .params(ts(now), outcome.name(), id)
            .update()
        == 1;
  }

  /**
   * Records a failed attempt: {@code attempts + 1}, the error, the next attempt and a cleared
   * lease; or, when this was the last allowed attempt, marks the event {@code DEAD_LETTER}.
   *
   * @return the outcome of the failure handling, empty when the event no longer exists or was
   *     already processed
   */
  public FailureResult recordFailure(
      UUID id, int attemptsBefore, String error, Instant now, int maxAttempts) {
    int attempts = attemptsBefore + 1;
    String lastError = truncate(error);
    if (attempts >= maxAttempts) {
      int rows =
          jdbc.sql(
                  "UPDATE outbox_event SET attempts = ?, last_error = ?, processed_at = ?,"
                      + " outcome = 'DEAD_LETTER', lease_until = NULL"
                      + " WHERE id = ? AND processed_at IS NULL")
              .params(attempts, lastError, ts(now), id)
              .update();
      return rows == 1 ? FailureResult.DEAD_LETTER : FailureResult.GONE;
    }
    Instant next = now.plusSeconds(60L << attempts);
    int rows =
        jdbc.sql(
                "UPDATE outbox_event SET attempts = ?, last_error = ?, next_attempt_at = ?,"
                    + " lease_until = NULL WHERE id = ? AND processed_at IS NULL")
            .params(attempts, lastError, ts(next), id)
            .update();
    return rows == 1 ? FailureResult.RETRY : FailureResult.GONE;
  }

  /** What {@link #recordFailure} did. */
  public enum FailureResult {
    /** Scheduled for another attempt after the back-off. */
    RETRY,
    /** Marked processed with outcome {@code DEAD_LETTER}. */
    DEAD_LETTER,
    /** Nothing changed: the event is gone or already processed. */
    GONE
  }

  /**
   * Deletes the unprocessed {@code NOTIFICATION_CREATED} events of every notification of the user
   * (inside the account deletion transaction, section 5.9).
   *
   * @return deleted events
   */
  public int deletePendingForUser(UUID userId) {
    return jdbc.sql(
            "DELETE FROM outbox_event WHERE processed_at IS NULL"
                + " AND event_type = 'NOTIFICATION_CREATED' AND aggregate_id IN ("
                + " SELECT CAST(id AS varchar) FROM notification WHERE user_id = ?)")
        .param(userId)
        .update();
  }

  /** Backlog counts (one read-only statement, contract schema {@code OutboxStats}). */
  public OutboxStats stats() {
    return jdbc.sql(
            "SELECT count(*) FILTER (WHERE processed_at IS NULL) AS pending,"
                + " count(*) FILTER (WHERE processed_at IS NULL AND attempts > 0) AS failed,"
                + " count(*) FILTER (WHERE outcome = 'DEAD_LETTER') AS dead_letter,"
                + " min(created_at) FILTER (WHERE processed_at IS NULL) AS oldest_pending"
                + " FROM outbox_event")
        .query((rs, i) ->
            new OutboxStats(
                rs.getLong("pending"),
                rs.getLong("failed"),
                rs.getLong("dead_letter"),
                instant(rs, "oldest_pending")))
        .single();
  }

  /**
   * Deletes up to {@code limit} processed events with an outcome in {@code deadLetters ?
   * DEAD_LETTER : the others} processed before {@code cutoff}, together with their {@code
   * alert_delivery} rows, in the caller's transaction.
   *
   * @return deleted events and delivery rows
   */
  public PurgeCount purgeProcessed(boolean deadLetters, Instant cutoff, int limit) {
    List<UUID> ids =
        jdbc.sql(
                "SELECT id FROM outbox_event WHERE processed_at IS NOT NULL"
                    + " AND processed_at < ? AND (outcome = 'DEAD_LETTER') = ?"
                    + " ORDER BY processed_at, id LIMIT ? FOR UPDATE SKIP LOCKED")
            .params(ts(cutoff), deadLetters, limit)
            .query(UUID.class)
            .list();
    if (ids.isEmpty()) {
      return new PurgeCount(0, 0);
    }
    int deliveries =
        jdbc.sql("DELETE FROM alert_delivery WHERE outbox_event_id IN (:ids)")
            .param("ids", ids)
            .update();
    int events = jdbc.sql("DELETE FROM outbox_event WHERE id IN (:ids)").param("ids", ids).update();
    return new PurgeCount(events, deliveries);
  }

  /** Rows removed by one purge batch. */
  public record PurgeCount(int events, int deliveries) {}

  private static ClaimedEvent claimed(ResultSet rs, int row) throws SQLException {
    return new ClaimedEvent(
        rs.getObject("id", UUID.class),
        OutboxEventType.valueOf(rs.getString("event_type")),
        rs.getString("aggregate_id"),
        rs.getString("payload"),
        rs.getInt("attempts"),
        instant(rs, "created_at"),
        rs.getLong("seq"));
  }

  private static String truncate(String error) {
    String value = error == null || error.isBlank() ? "unknown" : error;
    return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
  }

  static OffsetDateTime ts(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }

  static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
