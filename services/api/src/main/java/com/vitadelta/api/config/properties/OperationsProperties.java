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
 */
@ConfigurationProperties(prefix = "vitadelta.operations")
public record OperationsProperties(
    @DefaultValue("true") boolean jobsEnabled,
    @DefaultValue("30") int unverifiedAccountTtlDays,
    String privacyContact) {

  public OperationsProperties {
    if (unverifiedAccountTtlDays < 1) {
      throw new IllegalArgumentException(
          "VITADELTA_UNVERIFIED_ACCOUNT_TTL_DAYS must be >= 1: " + unverifiedAccountTtlDays);
    }
    privacyContact =
        privacyContact == null || privacyContact.isBlank() ? null : privacyContact.strip();
  }
}
