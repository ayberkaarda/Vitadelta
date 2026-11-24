package com.vitadelta.api.billing;

import com.vitadelta.api.entitlement.PlanCode;
import com.vitadelta.api.entitlement.Subscription;

/**
 * Outcome of a {@link BillingProvider} activation.
 *
 * @param subscription the ACTIVE subscription now in effect
 * @param extended whether an existing PRO period was extended
 */
public record ActivationResult(PlanCode plan, Subscription subscription, boolean extended) {}
