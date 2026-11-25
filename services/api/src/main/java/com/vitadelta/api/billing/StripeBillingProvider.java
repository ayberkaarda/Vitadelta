package com.vitadelta.api.billing;

import com.vitadelta.api.entitlement.SubscriptionService;
import com.vitadelta.api.entitlement.SubscriptionService.ProviderApplication;
import com.vitadelta.api.entitlement.SubscriptionService.ProviderChange;
import com.vitadelta.api.identity.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Payment-provider adapter of the billing port (ADR-0014): applies a paid period of a Stripe
 * subscription ({@link ActivationRequest.ProviderPeriod}) with source {@code PROVIDER} and the
 * Stripe subscription id as {@code external_ref}, through {@link
 * SubscriptionService#applyProviderPeriod} under the user's row lock. It joins the caller's
 * transaction (the webhook transaction that also records the event id) or opens its own.
 */
@Component
public class StripeBillingProvider implements BillingProvider {

  private final UserRepository users;
  private final SubscriptionService subscriptions;
  private final TransactionTemplate transactions;

  public StripeBillingProvider(
      UserRepository users,
      SubscriptionService subscriptions,
      PlatformTransactionManager transactionManager) {
    this.users = users;
    this.subscriptions = subscriptions;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /**
   * Applies the period of an {@link ActivationRequest.ProviderPeriod} request.
   *
   * @return the ACTIVE subscription afterwards; {@code extended} is true for a renewal
   * @throws IllegalStateException when the user does not exist
   */
  @Override
  public ActivationResult activate(ActivationRequest request) {
    ProviderApplication application = apply(request);
    return new ActivationResult(
        application.subscription().planCode(),
        application.subscription(),
        application.change() == ProviderChange.EXTENDED);
  }

  /** Like {@link #activate}, with the kind of change made. */
  public ProviderApplication apply(ActivationRequest request) {
    if (!(request instanceof ActivationRequest.ProviderPeriod period)) {
      throw new IllegalArgumentException("not a provider period: " + request);
    }
    return transactions.execute(status -> {
      if (users.findByIdForUpdate(period.userId()).isEmpty()) {
        throw new IllegalStateException("user does not exist");
      }
      return subscriptions.applyProviderPeriod(
          period.userId(), period.externalRef(), period.periodEnd());
    });
  }
}
