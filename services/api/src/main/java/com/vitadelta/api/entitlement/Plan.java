package com.vitadelta.api.entitlement;

import com.vitadelta.api.identity.AccountPlanSummary.PlanLimits;
import java.math.BigDecimal;

/**
 * One {@code plan} row. A {@code null} limit means unlimited; the price fields are all {@code null}
 * (FREE) or all set.
 *
 * @param priceAmount list price in {@code priceCurrency} (the plan's native currency)
 * @param billingPeriod {@code MONTHLY} when priced
 */
public record Plan(
    PlanCode code,
    String name,
    Integer savedProductsLimit,
    Integer alertSubscriptionsLimit,
    Integer notificationsPerMonthLimit,
    BigDecimal priceAmount,
    String priceCurrency,
    String billingPeriod) {

  /** Whether the plan has a list price. */
  public boolean priced() {
    return priceAmount != null;
  }

  /** The limits as rendered by {@code GET /me} (contract schema {@code PlanLimits}). */
  public PlanLimits limits() {
    return new PlanLimits(
        code.name(), name, savedProductsLimit, alertSubscriptionsLimit, notificationsPerMonthLimit);
  }
}
