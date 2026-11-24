package com.vitadelta.api.entitlement;

/**
 * {@code subscription.source} (contract schema {@code SubscriptionSource}): {@code MANUAL} for the
 * FREE subscription of registration and downgrade and for admin grants, {@code PROMO} for
 * promo-code activations, {@code PROVIDER} for a payment provider (not used in v1).
 */
public enum SubscriptionSource {
  MANUAL,
  PROMO,
  PROVIDER
}
