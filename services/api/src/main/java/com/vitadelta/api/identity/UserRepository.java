package com.vitadelta.api.identity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Data access of {@code app_user} and the country lookups of the identity flows. */
@Repository
public class UserRepository {

  static final String COLUMNS =
      "id, email, password_hash, email_verified_at, role, status, default_country_code,"
          + " display_currency, locale, email_alerts_enabled, last_login_at, created_at,"
          + " updated_at";

  private final JdbcClient jdbc;

  public UserRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** {@code instant} as a UTC timestamp parameter ({@code null} stays {@code null}). */
  static OffsetDateTime ts(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }

  /** The instant of a {@code timestamptz} column, or {@code null}. */
  static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  static UserAccount map(ResultSet rs, int row) throws SQLException {
    return new UserAccount(
        rs.getObject("id", UUID.class),
        rs.getString("email"),
        rs.getString("password_hash"),
        instant(rs, "email_verified_at"),
        Role.valueOf(rs.getString("role")),
        UserStatus.valueOf(rs.getString("status")),
        rs.getString("default_country_code"),
        rs.getString("display_currency"),
        rs.getString("locale"),
        rs.getBoolean("email_alerts_enabled"),
        instant(rs, "last_login_at"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  public Optional<UserAccount> findById(UUID id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM app_user WHERE id = ?")
        .param(id)
        .query(UserRepository::map)
        .optional();
  }

  /** The user row locked with {@code FOR UPDATE} (serializes changes of one user). */
  public Optional<UserAccount> findByIdForUpdate(UUID id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM app_user WHERE id = ? FOR UPDATE")
        .param(id)
        .query(UserRepository::map)
        .optional();
  }

  /**
   * Lookup by e-mail, case-insensitive: the parameter is cast to {@code citext} (a plain text
   * parameter would select the case-sensitive {@code text = text} operator).
   */
  public Optional<UserAccount> findByEmail(String email) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM app_user WHERE email = CAST(? AS citext)")
        .param(email)
        .query(UserRepository::map)
        .optional();
  }

  /**
   * Inserts the user unless the e-mail exists ({@code ON CONFLICT DO NOTHING}).
   *
   * @return whether the row was inserted
   */
  public boolean insertIfAbsent(UserAccount user) {
    return jdbc.sql(
                "INSERT INTO app_user (id, email, password_hash, email_verified_at, role, status,"
                    + " default_country_code, display_currency, locale, email_alerts_enabled,"
                    + " last_login_at, created_at, updated_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                    + " ON CONFLICT (email) DO NOTHING")
            .params(
                user.id(),
                user.email(),
                user.passwordHash(),
                ts(user.emailVerifiedAt()),
                user.role().name(),
                user.status().name(),
                user.defaultCountryCode(),
                user.displayCurrency(),
                user.locale(),
                user.emailAlertsEnabled(),
                ts(user.lastLoginAt()),
                ts(user.createdAt()),
                ts(user.updatedAt()))
            .update()
        == 1;
  }

  public void markLoggedIn(UUID id, Instant now) {
    jdbc.sql("UPDATE app_user SET last_login_at = ? WHERE id = ?").params(ts(now), id).update();
  }

  /** Sets {@code email_verified_at} unless it is already set. */
  public void markVerified(UUID id, Instant now) {
    jdbc.sql(
            "UPDATE app_user SET email_verified_at = coalesce(email_verified_at, ?),"
                + " updated_at = ? WHERE id = ?")
        .params(ts(now), ts(now), id)
        .update();
  }

  public void updatePassword(UUID id, String passwordHash, Instant now) {
    jdbc.sql("UPDATE app_user SET password_hash = ?, updated_at = ? WHERE id = ?")
        .params(passwordHash, ts(now), id)
        .update();
  }

  public void updateStatus(UUID id, UserStatus status, Instant now) {
    jdbc.sql("UPDATE app_user SET status = ?, updated_at = ? WHERE id = ?")
        .params(status.name(), ts(now), id)
        .update();
  }

  /** Writes the editable profile settings of {@code user}. */
  public void updateSettings(UserAccount user, Instant now) {
    jdbc.sql(
            "UPDATE app_user SET display_currency = ?, locale = ?, default_country_code = ?,"
                + " email_alerts_enabled = ?, updated_at = ? WHERE id = ?")
        .params(
            user.displayCurrency(),
            user.locale(),
            user.defaultCountryCode(),
            user.emailAlertsEnabled(),
            ts(now),
            user.id())
        .update();
  }

  /** Deletes the user; dependent rows go with the cascade of section 5.9. */
  public boolean delete(UUID id) {
    return jdbc.sql("DELETE FROM app_user WHERE id = ?").param(id).update() == 1;
  }

  public boolean adminExists() {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM app_user WHERE role = 'ADMIN')")
        .query(Boolean.class)
        .single();
  }

  public boolean countryExists(String code) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM country WHERE code = ?)")
        .param(code)
        .query(Boolean.class)
        .single();
  }

  /** The default locale of a country, if the country exists. */
  public Optional<String> countryDefaultLocale(String code) {
    return jdbc.sql("SELECT default_locale FROM country WHERE code = ?")
        .param(code)
        .query(String.class)
        .optional();
  }
}
