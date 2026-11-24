package com.vitadelta.api.billing;

import com.vitadelta.api.common.problem.FieldViolation;
import com.vitadelta.api.common.problem.ProblemException;
import com.vitadelta.api.entitlement.PlanCode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Administration of promo codes (section 8.9): generate, list, delete. */
@Service
public class PromoCodeService {

  /** Attempts to find an unused code before giving up (a collision of 80 random bits). */
  static final int GENERATION_ATTEMPTS = 5;

  private static final Logger LOG = LoggerFactory.getLogger(PromoCodeService.class);

  private final PromoCodeRepository promoCodes;
  private final PromoCodeGenerator generator;
  private final Clock clock;

  public PromoCodeService(
      PromoCodeRepository promoCodes, PromoCodeGenerator generator, Clock clock) {
    this.promoCodes = promoCodes;
    this.generator = generator;
    this.clock = clock;
  }

  /**
   * Generates and stores a new code; the response is the only place the code is returned.
   *
   * @throws ProblemException {@code 422 validation_failed} when {@code expiresAt} is not in the
   *     future
   */
  public PromoCodeRecord create(
      String prefix,
      PlanCode planCode,
      int durationDays,
      int maxRedemptions,
      Instant expiresAt,
      UUID createdBy) {
    Instant now = clock.instant();
    if (expiresAt != null && !expiresAt.isAfter(now)) {
      throw ProblemException.validation(
          "The request contains invalid fields.",
          List.of(
              new FieldViolation(
                  "expiresAt", "not_in_future", "The expiry must be in the future.")));
    }
    for (int attempt = 0; attempt < GENERATION_ATTEMPTS; attempt++) {
      PromoCodeRecord code =
          new PromoCodeRecord(
              generator.generate(prefix),
              planCode,
              durationDays,
              maxRedemptions,
              0,
              expiresAt,
              createdBy);
      if (promoCodes.insertIfAbsent(code, now)) {
        LOG.info(
            "Promo code created by {} ({} days, {} redemptions)",
            createdBy,
            durationDays,
            maxRedemptions);
        return code;
      }
    }
    throw new IllegalStateException("no unused promo code found");
  }

  /** Every code, newest first. */
  public List<PromoCodeRecord> list() {
    return promoCodes.findAll();
  }

  /**
   * Deletes a code (case-insensitively) together with its redemptions.
   *
   * @return whether it existed
   */
  public boolean delete(String code) {
    return promoCodes.delete(code);
  }
}
