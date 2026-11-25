package com.vitadelta.api.billing;

/**
 * Billing port (section 8.8, ADR-0010): turns an activation request into an ACTIVE or extended
 * subscription. Adapters: {@link PromoCodeBillingProvider} ({@code POST /me/subscription/promo}),
 * {@link ManualGrantBillingProvider} ({@code POST /admin/users/{id}/subscription}) and, when a
 * payment provider is configured, {@link StripeBillingProvider} (paid periods reported by verified
 * Stripe webhooks, ADR-0014). Every adapter changes the subscription through {@code
 * SubscriptionService} under the user's row lock.
 */
public interface BillingProvider {

  /**
   * Activates or extends the subscription described by {@code request}.
   *
   * @throws IllegalArgumentException when the request kind is not handled by this adapter
   */
  ActivationResult activate(ActivationRequest request);
}
