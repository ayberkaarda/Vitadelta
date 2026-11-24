package com.vitadelta.api.billing;

/**
 * Billing port (section 8.8, ADR-0010): turns an activation request into an ACTIVE or extended
 * subscription. v1 adapters: {@link PromoCodeBillingProvider} ({@code POST /me/subscription/promo})
 * and {@link ManualGrantBillingProvider} ({@code POST /admin/users/{id}/subscription}); a payment
 * provider adapter is optional Phase 8 work. Every adapter changes the subscription through {@code
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
