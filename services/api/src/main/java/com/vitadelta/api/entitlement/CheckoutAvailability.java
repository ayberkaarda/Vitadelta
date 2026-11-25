package com.vitadelta.api.entitlement;

/**
 * Whether PRO can be bought through the payment provider's hosted checkout (contract property
 * {@code Plan.checkoutAvailable}). Implemented by the billing package from its configuration; the
 * plans endpoint only reports it and decides nothing for the caller.
 */
@FunctionalInterface
public interface CheckoutAvailability {

  /** {@code true} when a payment provider is configured. */
  boolean available();
}
