package com.vitadelta.api.entitlement;

import java.util.UUID;

/**
 * Published by {@link SubscriptionService} inside the per-user transaction of the {@link
 * SubscriptionExpiryJob} that downgraded an expired PRO subscription to FREE (section 8.3). The
 * alerts module (Phase 5) creates the {@code PLAN_EXPIRED} notification from it, never counted
 * against the monthly cap.
 *
 * @param expiredSubscriptionId the PRO row now {@code EXPIRED}
 * @param freeSubscriptionId the new ACTIVE FREE row
 * @param deactivatedAlertSubscriptions alert subscriptions set inactive because they exceed the
 *     FREE limit (newest first)
 */
public record SubscriptionExpiredEvent(
    UUID userId,
    UUID expiredSubscriptionId,
    UUID freeSubscriptionId,
    int deactivatedAlertSubscriptions) {}
