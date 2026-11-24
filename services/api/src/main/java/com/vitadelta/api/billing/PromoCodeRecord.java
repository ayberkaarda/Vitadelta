package com.vitadelta.api.billing;

import com.vitadelta.api.entitlement.PlanCode;
import java.time.Instant;
import java.util.UUID;

/**
 * One {@code promo_code} row (contract schema {@code PromoCode}).
 *
 * @param expiresAt {@code null} for a code that never expires
 * @param createdBy the creating administrator, {@code null} once that account is deleted
 */
public record PromoCodeRecord(
    String code,
    PlanCode planCode,
    int durationDays,
    int maxRedemptions,
    int redeemedCount,
    Instant expiresAt,
    UUID createdBy) {

  /** Whether the code is past its expiry at {@code now}. */
  public boolean expired(Instant now) {
    return expiresAt != null && !expiresAt.isAfter(now);
  }
}
