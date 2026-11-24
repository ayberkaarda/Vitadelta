package com.vitadelta.api.entitlement;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by {@link SubscriptionService} inside the transaction that activated or extended a
 * subscription (promo code or manual grant, section 8.8). Listeners that write ({@code
 * PLAN_ACTIVATED} notification, Phase 5) take part in that transaction with a plain
 * {@code @EventListener}, or use {@code @TransactionalEventListener} to act after commit only.
 *
 * @param extended whether an existing PRO period was extended instead of a new one started
 */
public record SubscriptionActivatedEvent(
    UUID userId,
    UUID subscriptionId,
    PlanCode planCode,
    SubscriptionSource source,
    Instant currentPeriodEnd,
    boolean extended) {}
