package com.vitadelta.api.entitlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitadelta.api.entitlement.SubscriptionService.Activation;
import com.vitadelta.api.identity.SubscriptionProvisioning;
import com.vitadelta.api.mail.EmailMessage;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

/** Subscription lifecycle of {@link SubscriptionService} (section 8.3). */
class SubscriptionLifecycleIntegrationTest extends EntitlementIntegrationTest {

  @Autowired SubscriptionProvisioning provisioning;
  @Autowired SubscriptionRepository repository;

  private Activation activate(UUID userId, int days, SubscriptionSource source) {
    return transactions.execute(
        status -> subscriptionService.activate(userId, PlanCode.PRO, days, source));
  }

  private static Instant instant(Object value) {
    return ((Timestamp) value).toInstant();
  }

  @Test
  void registrationProvisioningIsTheEntitlementModule() {
    assertThat(provisioning).isInstanceOf(SubscriptionService.class);
    TestUser user = user();
    List<Map<String, Object>> rows = subscriptions(user.id());
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0))
        .containsEntry("plan_code", "FREE")
        .containsEntry("status", "ACTIVE")
        .containsEntry("source", "MANUAL")
        .containsEntry("current_period_end", null);
    assertThatThrownBy(() -> provisioning.provisionFreeSubscription(user.id(), clock.instant()))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  @Test
  void activatingProCancelsFreeAndStartsAPeriod() {
    TestUser user = user();
    Instant now = clock.instant();
    Activation activation = activate(user.id(), 30, SubscriptionSource.PROMO);
    assertThat(activation.extended()).isFalse();
    Subscription pro = activation.subscription();
    assertThat(pro.planCode()).isEqualTo(PlanCode.PRO);
    assertThat(pro.source()).isEqualTo(SubscriptionSource.PROMO);
    assertThat(pro.startedAt()).isEqualTo(now);
    assertThat(pro.currentPeriodEnd()).isEqualTo(now.plus(Duration.ofDays(30)));

    List<Map<String, Object>> rows = subscriptions(user.id());
    assertThat(rows).hasSize(2);
    assertThat(rows)
        .anySatisfy(row -> {
          assertThat(row).containsEntry("plan_code", "FREE").containsEntry("status", "CANCELED");
          assertThat(instant(row.get("canceled_at"))).isEqualTo(now);
        });
    assertThat(repository.findActive(user.id())).contains(pro);
    assertThat(activeCount(user.id())).isEqualTo(1);

    List<EmailMessage> mails = mail().await(user.email(), 1);
    assertThat(mails.get(0).subject()).isEqualTo("Your VitaDelta Pro plan is active");
    assertThat(events.activated)
        .contains(
            new SubscriptionActivatedEvent(
                user.id(),
                pro.id(),
                PlanCode.PRO,
                SubscriptionSource.PROMO,
                pro.currentPeriodEnd(),
                false));
  }

  @Test
  void anActiveProPeriodIsExtended() {
    TestUser user = user();
    Instant now = clock.instant();
    Activation first = activate(user.id(), 30, SubscriptionSource.PROMO);
    clock.advance(Duration.ofDays(10));
    Activation second = activate(user.id(), 15, SubscriptionSource.MANUAL);
    assertThat(second.extended()).isTrue();
    assertThat(second.subscription().id()).isEqualTo(first.subscription().id());
    assertThat(second.subscription().source()).isEqualTo(SubscriptionSource.PROMO);
    assertThat(second.subscription().currentPeriodEnd()).isEqualTo(now.plus(Duration.ofDays(45)));
    assertThat(repository.findActive(user.id()).orElseThrow().currentPeriodEnd())
        .isEqualTo(now.plus(Duration.ofDays(45)));
    assertThat(subscriptions(user.id())).hasSize(2);
    mail().await(user.email(), 2);
  }

  @Test
  void anEndedButNotYetDowngradedPeriodRestartsFromNow() {
    TestUser user = user();
    activate(user.id(), 30, SubscriptionSource.PROMO);
    clock.advance(Duration.ofDays(40));
    Activation renewed = activate(user.id(), 30, SubscriptionSource.PROMO);
    assertThat(renewed.extended()).isTrue();
    assertThat(renewed.subscription().currentPeriodEnd())
        .isEqualTo(clock.instant().plus(Duration.ofDays(30)));
  }

  @Test
  void activationRejectsInvalidArgumentsAndNeedsATransaction() {
    TestUser user = user();
    assertThatThrownBy(() -> activate(user.id(), 30, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() ->
            transactions.execute(status ->
                subscriptionService.activate(
                    user.id(), PlanCode.FREE, 30, SubscriptionSource.MANUAL)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> activate(user.id(), 0, SubscriptionSource.MANUAL))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> activate(UUID.randomUUID(), 30, SubscriptionSource.MANUAL))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() ->
            subscriptionService.activate(user.id(), PlanCode.PRO, 30, SubscriptionSource.MANUAL))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThat(activeCount(user.id())).isEqualTo(1);
    assertThat(repository.findAll(user.id())).hasSize(1);
  }

  @Test
  void aSubscriptionRowRendersTheContractShape() {
    TestUser user = user();
    Subscription free = repository.findActive(user.id()).orElseThrow();
    Contract.assertConforms("Subscription", JSON.writeValueAsString(free.view()));
    assertThat(free.view().planCode()).isEqualTo("FREE");
    assertThat(free.view().status()).isEqualTo("ACTIVE");
  }
}
