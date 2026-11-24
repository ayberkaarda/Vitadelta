package com.vitadelta.api.entitlement;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Conversions between {@link Instant} and {@code timestamptz} values of the plan tables. */
public final class SqlTime {

  private SqlTime() {}

  /** {@code instant} as a UTC timestamp parameter ({@code null} stays {@code null}). */
  public static OffsetDateTime ts(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }

  /** The instant of a {@code timestamptz} column, or {@code null}. */
  public static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
