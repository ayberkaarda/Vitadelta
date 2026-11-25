package com.vitadelta.api.billing;

import com.vitadelta.api.entitlement.PlanCode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** What a {@link BillingProvider} is asked to activate, for the user {@link #userId()}. */
public sealed interface ActivationRequest {

  UUID userId();

  /**
   * Redemption of a promo code by the signed-in user. {@link #toString()} hides the code.
   *
   * @param code the code as entered (matched case-insensitively)
   */
  record PromoCode(UUID userId, String code) implements ActivationRequest {

    public PromoCode {
      Objects.requireNonNull(userId, "userId");
      Objects.requireNonNull(code, "code");
    }

    @Override
    public String toString() {
      return "PromoCode[userId=" + userId + "]";
    }
  }

  /**
   * Grant by an administrator.
   *
   * @param grantedBy the administrator's user id
   */
  record ManualGrant(UUID userId, PlanCode planCode, int durationDays, UUID grantedBy)
      implements ActivationRequest {

    public ManualGrant {
      Objects.requireNonNull(userId, "userId");
      Objects.requireNonNull(planCode, "planCode");
      Objects.requireNonNull(grantedBy, "grantedBy");
    }
  }

  /**
   * A paid period of a payment-provider subscription, reported by a verified webhook (ADR-0014).
   *
   * @param externalRef the provider's subscription id
   * @param periodEnd end of the paid period
   */
  record ProviderPeriod(UUID userId, String externalRef, Instant periodEnd)
      implements ActivationRequest {

    public ProviderPeriod {
      Objects.requireNonNull(userId, "userId");
      Objects.requireNonNull(externalRef, "externalRef");
      Objects.requireNonNull(periodEnd, "periodEnd");
    }
  }
}
