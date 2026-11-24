package com.vitadelta.api.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * One {@code app_user} row. {@link #toString()} masks the e-mail and never shows the password hash.
 *
 * @param displayCurrency preferred display currency, {@code null} for the country's native currency
 */
public record UserAccount(
    UUID id,
    String email,
    String passwordHash,
    Instant emailVerifiedAt,
    Role role,
    UserStatus status,
    String defaultCountryCode,
    String displayCurrency,
    String locale,
    boolean emailAlertsEnabled,
    Instant lastLoginAt,
    Instant createdAt,
    Instant updatedAt) {

  /** Whether the e-mail address is verified. */
  public boolean emailVerified() {
    return emailVerifiedAt != null;
  }

  @Override
  public String toString() {
    return "UserAccount[id=" + id + ", role=" + role + ", status=" + status + "]";
  }
}
