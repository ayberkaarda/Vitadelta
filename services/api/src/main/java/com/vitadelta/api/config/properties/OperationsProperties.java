package com.vitadelta.api.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Operational switches and retention.
 *
 * @param jobsEnabled whether scheduled jobs run ({@code VITADELTA_JOBS_ENABLED}, default true;
 *     false disables every scheduler, used by the restore drill and the seed run)
 * @param unverifiedAccountTtlDays days after which never-logged-in unverified accounts are deleted
 *     ({@code VITADELTA_UNVERIFIED_ACCOUNT_TTL_DAYS}, default 30)
 * @param privacyContact contact shown in the privacy notice ({@code VITADELTA_PRIVACY_CONTACT}), or
 *     {@code null}
 * @param requirePrivacyContact whether startup fails without a privacy contact; false only in the
 *     {@code dev} profile
 */
@ConfigurationProperties(prefix = "vitadelta.operations")
public record OperationsProperties(
    @DefaultValue("true") boolean jobsEnabled,
    @DefaultValue("30") int unverifiedAccountTtlDays,
    String privacyContact,
    @DefaultValue("true") boolean requirePrivacyContact) {

  /** Longest accepted privacy contact. */
  public static final int MAX_PRIVACY_CONTACT_LENGTH = 500;

  public OperationsProperties {
    if (unverifiedAccountTtlDays < 1) {
      throw new IllegalArgumentException(
          "VITADELTA_UNVERIFIED_ACCOUNT_TTL_DAYS must be >= 1: " + unverifiedAccountTtlDays);
    }
    privacyContact =
        privacyContact == null || privacyContact.isBlank() ? null : privacyContact.strip();
    if (privacyContact == null && requirePrivacyContact) {
      throw new IllegalArgumentException(
          "VITADELTA_PRIVACY_CONTACT is not set; the privacy notice needs a controller contact");
    }
    if (privacyContact != null
        && (privacyContact.length() > MAX_PRIVACY_CONTACT_LENGTH
            || privacyContact.codePoints().anyMatch(Character::isISOControl))) {
      throw new IllegalArgumentException(
          "VITADELTA_PRIVACY_CONTACT must be at most "
              + MAX_PRIVACY_CONTACT_LENGTH
              + " characters without control characters");
    }
  }
}
