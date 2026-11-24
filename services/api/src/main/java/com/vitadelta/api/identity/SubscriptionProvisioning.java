package com.vitadelta.api.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * Seam to the plan module: creates the initial subscription of a new account. Registration and the
 * admin bootstrap call it inside the transaction that inserts the user, so the user never exists
 * without an ACTIVE subscription.
 *
 * <p>The identity package registers {@link JdbcSubscriptionProvisioning} only when no other bean of
 * this type exists; the entitlement/billing module replaces it by declaring its own bean.
 */
public interface SubscriptionProvisioning {

  /**
   * Inserts the ACTIVE, open-ended {@code FREE} subscription of a user that has none yet. Must run
   * inside the caller's transaction.
   */
  void provisionFreeSubscription(UUID userId, Instant now);
}
