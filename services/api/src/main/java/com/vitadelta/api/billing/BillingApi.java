package com.vitadelta.api.billing;

import com.vitadelta.api.identity.AccountPlanSummary.SubscriptionView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;

/** Request and response bodies of the billing endpoints (contract schemas of the same names). */
public final class BillingApi {

  /**
   * Longest period one grant or promo code may add (about ten years); keeps every period end inside
   * the timestamp range.
   */
  public static final int MAX_DURATION_DAYS = 3650;

  /** The only plan that can be granted or redeemed. */
  static final String PRO_ONLY = "^PRO$";

  private BillingApi() {}

  /**
   * {@code POST /me/subscription/promo}. Any string is accepted here: a value that is not a
   * well-formed code is a code guess like an unknown one and is answered by the redemption with the
   * same {@code 400 promo_invalid} after the rate limits; only a missing or {@code null} code is a
   * malformed request ({@code 422}). {@link #toString()} hides the code.
   */
  public record PromoRedeemRequest(@NotNull String code) {

    @Override
    public String toString() {
      return "PromoRedeemRequest[code=<hidden>]";
    }
  }

  /** Response of {@code POST /me/subscription/promo}. */
  public record SubscriptionActivation(String plan, SubscriptionView subscription) {}

  /** {@code POST /admin/users/{id}/subscription}. */
  public record GrantSubscriptionRequest(
      @NotNull @Pattern(regexp = PRO_ONLY) String planCode,
      @NotNull @Min(1) @Max(MAX_DURATION_DAYS) Integer durationDays) {}

  /**
   * {@code POST /admin/promo-codes}.
   *
   * @param expiresAt {@code null} for a code that never expires; otherwise in the future
   */
  public record CreatePromoCodeRequest(
      @Pattern(regexp = PromoCodeGenerator.PREFIX_REGEX) String prefix,
      @NotNull @Pattern(regexp = PRO_ONLY) String planCode,
      @NotNull @Min(1) @Max(MAX_DURATION_DAYS) Integer durationDays,
      @NotNull @Min(1) Integer maxRedemptions,
      Instant expiresAt) {}

  /** Response of {@code GET /admin/promo-codes}. */
  public record PromoCodeList(List<PromoCodeRecord> items) {}

  /**
   * Response of {@code POST /billing/checkout-session} and {@code POST /billing/portal-session}.
   *
   * @param url absolute https URL of the provider's hosted page
   */
  public record BillingRedirect(String url) {}

  /** Response of {@code POST /billing/webhooks/stripe}; {@code received} is always true. */
  public record WebhookAck(boolean received) {

    /** The acknowledgement of every accepted delivery. */
    public static final WebhookAck RECEIVED = new WebhookAck(true);
  }
}
