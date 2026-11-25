package com.vitadelta.api.alerts;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Statements on {@code notification} (section 8.6). */
@Repository
public class NotificationRepository {

  private static final int MAX_ERROR_LENGTH = 200;

  private final JdbcClient jdbc;

  public NotificationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * One stored notification.
   *
   * @param payload the jsonb payload as text
   */
  public record StoredNotification(
      UUID id,
      UUID userId,
      NotificationType type,
      String payload,
      Instant createdAt,
      Instant readAt,
      Instant emailSentAt) {}

  /** Inserts a notification (not a cap notice); joins the caller's transaction. */
  public UUID insert(UUID userId, NotificationType type, String payload, Instant now) {
    if (type == NotificationType.NOTIFICATION_CAP_REACHED) {
      throw new IllegalArgumentException("cap notices are inserted with insertCapNotice");
    }
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO notification (id, user_id, type, payload, created_at)"
                + " VALUES (?, ?, ?, CAST(? AS jsonb), ?)")
        .params(id, userId, type.name(), payload, OutboxRepository.ts(now))
        .update();
    return id;
  }

  /**
   * Inserts the month's single {@code NOTIFICATION_CAP_REACHED} notice unless it exists (the unique
   * index decides, also for concurrent callers).
   *
   * @return the id of the new notice, empty when the month already has one
   */
  public Optional<UUID> insertCapNotice(
      UUID userId, LocalDate periodMonth, String payload, Instant now) {
    UUID id = UUID.randomUUID();
    int rows =
        jdbc.sql(
                "INSERT INTO notification (id, user_id, type, payload, cap_period_month,"
                    + " created_at) VALUES (?, ?, 'NOTIFICATION_CAP_REACHED', CAST(? AS jsonb), ?,"
                    + " ?) ON CONFLICT (user_id, cap_period_month)"
                    + " WHERE type = 'NOTIFICATION_CAP_REACHED' DO NOTHING")
            .params(id, userId, payload, periodMonth, OutboxRepository.ts(now))
            .update();
    return rows == 1 ? Optional.of(id) : Optional.empty();
  }

  public Optional<StoredNotification> find(UUID id) {
    return jdbc.sql(
            "SELECT id, user_id, type, payload::text AS payload, created_at, read_at,"
                + " email_sent_at FROM notification WHERE id = ?")
        .param(id)
        .query(NotificationRepository::stored)
        .optional();
  }

  /** The caller's notifications, newest first ({@code created_at} desc, {@code id} desc). */
  public List<StoredNotification> page(UUID userId, boolean unreadOnly, long offset, int limit) {
    return jdbc.sql(
            "SELECT id, user_id, type, payload::text AS payload, created_at, read_at,"
                + " email_sent_at FROM notification WHERE user_id = :user"
                + " AND (:unreadOnly = false OR read_at IS NULL)"
                + " ORDER BY created_at DESC, id DESC OFFSET :offset LIMIT :limit")
        .param("user", userId)
        .param("unreadOnly", unreadOnly)
        .param("offset", offset)
        .param("limit", limit)
        .query(NotificationRepository::stored)
        .list();
  }

  public long count(UUID userId, boolean unreadOnly) {
    return jdbc.sql(
            "SELECT count(*) FROM notification WHERE user_id = :user"
                + " AND (:unreadOnly = false OR read_at IS NULL)")
        .param("user", userId)
        .param("unreadOnly", unreadOnly)
        .query(Long.class)
        .single();
  }

  /**
   * Sets {@code read_at} when it is {@code null}; an already read notification keeps its time.
   *
   * @return whether the notification exists and belongs to the user
   */
  public boolean markRead(UUID userId, UUID id, Instant now) {
    return jdbc.sql(
                "UPDATE notification SET read_at = coalesce(read_at, ?)"
                    + " WHERE id = ? AND user_id = ?")
            .params(OutboxRepository.ts(now), id, userId)
            .update()
        == 1;
  }

  /** Marks every unread notification of the user read (one statement). */
  public int markAllRead(UUID userId, Instant now) {
    return jdbc.sql("UPDATE notification SET read_at = ? WHERE user_id = ? AND read_at IS NULL")
        .params(OutboxRepository.ts(now), userId)
        .update();
  }

  /**
   * Sets {@code email_sent_at} unless already set.
   *
   * @return whether the notification still exists
   */
  public boolean markEmailSent(UUID id, Instant now) {
    return jdbc.sql(
                "UPDATE notification SET email_sent_at = coalesce(email_sent_at, ?),"
                    + " email_error = NULL WHERE id = ?")
            .params(OutboxRepository.ts(now), id)
            .update()
        == 1;
  }

  /** Records the class of a delivery failure. */
  public void markEmailFailed(UUID id, String error) {
    String value =
        error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    jdbc.sql("UPDATE notification SET email_error = ? WHERE id = ?").params(value, id).update();
  }

  private static StoredNotification stored(ResultSet rs, int row) throws SQLException {
    return new StoredNotification(
        rs.getObject("id", UUID.class),
        rs.getObject("user_id", UUID.class),
        NotificationType.valueOf(rs.getString("type")),
        rs.getString("payload"),
        OutboxRepository.instant(rs, "created_at"),
        OutboxRepository.instant(rs, "read_at"),
        OutboxRepository.instant(rs, "email_sent_at"));
  }
}
