package com.vitadelta.api.identity;

import java.util.UUID;

/**
 * Seam to the plan module: the plan, the ACTIVE subscription and the usage meters of a user, as
 * rendered by {@code GET /me} and {@code GET /session/quota}.
 *
 * <p>The identity package registers {@link JdbcAccountUsageProvider} only when no other bean of
 * this type exists; the entitlement module replaces it (for example to read limits through the
 * cached {@code PlanService}).
 */
public interface AccountUsageProvider {

  /**
   * The plan summary of the user.
   *
   * @throws IllegalStateException when the user has no ACTIVE subscription (an invariant violation)
   */
  AccountPlanSummary summary(UUID userId);
}
