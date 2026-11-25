package com.vitadelta.api.billing;

import com.vitadelta.api.entitlement.CheckoutAvailability;
import org.springframework.stereotype.Component;

/** Checkout is available exactly when a payment provider is configured (ADR-0014). */
@Component
public class ProviderCheckoutAvailability implements CheckoutAvailability {

  private final BillingProperties properties;

  public ProviderCheckoutAvailability(BillingProperties properties) {
    this.properties = properties;
  }

  @Override
  public boolean available() {
    return properties.enabled();
  }
}
