package com.vitadelta.api.billing;

import com.vitadelta.api.identity.UserDeletingEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Stops the payments of a deleted account (ADR-0014). Inside the deletion transaction ({@link
 * UserDeletingEvent}) it collects the user's Stripe subscriptions that have not ended; after the
 * deletion commits it cancels each of them at Stripe ({@code DELETE /v1/subscriptions/{id}}),
 * outside any transaction. The deletion never waits for or depends on Stripe: a failed cancellation
 * is logged with the subscription id and counted in {@code
 * vitadelta.billing.cancel_on_delete.failed} for the operator (runbook); the provider rows
 * themselves are removed by the deletion cascade.
 */
@Component
public class BillingAccountDeletionListener {

  private static final Logger LOG = LoggerFactory.getLogger(BillingAccountDeletionListener.class);

  private final BillingProperties properties;
  private final BillingAccountRepository accounts;
  private final StripeApi stripe;
  private final MeterRegistry meters;

  public BillingAccountDeletionListener(
      BillingProperties properties,
      BillingAccountRepository accounts,
      StripeApi stripe,
      MeterRegistry meters) {
    this.properties = properties;
    this.accounts = accounts;
    this.stripe = stripe;
    this.meters = meters;
  }

  @EventListener
  public void onUserDeleting(UserDeletingEvent event) {
    List<String> live = accounts.liveSubscriptions(event.userId());
    if (live.isEmpty()) {
      return;
    }
    if (!properties.enabled()) {
      LOG.warn(
          "Deleted user {} has {} Stripe subscription(s) that cannot be canceled without a"
              + " configured provider",
          event.userId(),
          live.size());
      meters.counter("vitadelta.billing.cancel_on_delete.failed").increment(live.size());
      return;
    }
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              cancel(event.userId(), live);
            }
          });
    } else {
      cancel(event.userId(), live);
    }
  }

  private void cancel(UUID userId, List<String> subscriptions) {
    for (String subscription : subscriptions) {
      try {
        stripe.cancelSubscription(subscription);
        meters.counter("vitadelta.billing.cancel_on_delete.canceled").increment();
        LOG.info("Stripe subscription {} of deleted user {} canceled", subscription, userId);
      } catch (StripeApiException e) {
        meters.counter("vitadelta.billing.cancel_on_delete.failed").increment();
        LOG.warn(
            "Stripe subscription {} of deleted user {} could not be canceled: {}",
            subscription,
            userId,
            e.getMessage());
      }
    }
  }
}
