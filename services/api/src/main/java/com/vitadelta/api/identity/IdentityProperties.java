package com.vitadelta.api.identity;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the identity package. Every value has a default; only the location of the breached
 * password list depends on the deployment.
 *
 * @param weakPasswordsFile the breached-password list ({@code weak_passwords.txt}, one password per
 *     line); when {@code null} it is {@code <vitadelta.seed.data-dir>/reference/weak_passwords.txt}
 * @param anonymousRetention anonymous identities not seen for this long are purged (default 180
 *     days, the {@code vd_anon} cookie max-age)
 * @param emailTokenRetention e-mail tokens expired or used longer ago than this are purged (default
 *     7 days)
 * @param refreshTokenRetention refresh tokens expired or revoked longer ago than this are purged
 *     (default 30 days)
 * @param housekeepingBatchSize rows deleted per short transaction by the housekeeping job (default
 *     1000)
 */
@ConfigurationProperties(prefix = "vitadelta.identity")
public record IdentityProperties(
    Path weakPasswordsFile,
    @DefaultValue("180d") Duration anonymousRetention,
    @DefaultValue("7d") Duration emailTokenRetention,
    @DefaultValue("30d") Duration refreshTokenRetention,
    @DefaultValue("1000") int housekeepingBatchSize) {

  public IdentityProperties {
    requirePositive(anonymousRetention, "vitadelta.identity.anonymous-retention");
    requirePositive(emailTokenRetention, "vitadelta.identity.email-token-retention");
    requirePositive(refreshTokenRetention, "vitadelta.identity.refresh-token-retention");
    if (housekeepingBatchSize < 1) {
      throw new IllegalArgumentException(
          "vitadelta.identity.housekeeping-batch-size must be >= 1: " + housekeepingBatchSize);
    }
  }

  private static void requirePositive(Duration value, String name) {
    if (value == null || value.isNegative() || value.isZero()) {
      throw new IllegalArgumentException(name + " must be a positive duration");
    }
  }
}
