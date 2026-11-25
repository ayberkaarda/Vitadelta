package com.vitadelta.api.adversarial;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.vitadelta.api.adversarial.AttackClient.Resp;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * Section 15.7, account deletion against the payment provider: a deleted account must never keep
 * being charged and must never be recreated or re-entitled by a webhook. Two orders are attacked: a
 * checkout paid on the hosted page after the account was deleted (the session stays payable for
 * hours), and a deletion that commits while a webhook for the same user is in flight.
 */
class StripeAccountDeletionAdversarialTest extends StripeAdversarialTest {

  private record Account(UUID id, String token) {}

  private Account registeredAccount() {
    String email = uniqueEmail();
    UUID id = registerVerified(email);
    return new Account(id, login(email, PASSWORD).accessToken());
  }

  private void deleteAccount(Account account) {
    Resp deleted =
        req("DELETE", API + "/me")
            .bearer(account.token())
            .json(Map.of("password", PASSWORD))
            .send();
    assertThat(deleted.status()).as(deleted.toString()).isEqualTo(204);
  }

  private void stripeCancels(String subscription) {
    STRIPE.stubFor(
        WireMock.delete(urlPathEqualTo("/v1/subscriptions/" + subscription))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"id\":\""
                            + subscription
                            + "\",\"object\":\"subscription\","
                            + "\"status\":\"canceled\"}")));
  }

  private void assertNothingOf(UUID userId) {
    assertThat(count("SELECT count(*) FROM app_user WHERE id = ?", userId)).isZero();
    assertThat(count("SELECT count(*) FROM subscription WHERE user_id = ?", userId)).isZero();
    assertThat(billingRows(userId)).isZero();
  }

  /** Waits for the API to cancel {@code subscription} at Stripe. */
  private void assertCanceledAtStripe(String subscription) {
    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() ->
            assertThat(
                    STRIPE.findAll(
                        deleteRequestedFor(urlPathEqualTo("/v1/subscriptions/" + subscription))))
                .as(
                    "the orphaned Stripe subscription %s of a deleted account is canceled",
                    subscription)
                .isNotEmpty());
  }

  @Test
  void aCheckoutPaidAfterTheAccountWasDeletedIsCanceledNotLeftCharging() {
    stripeServesCheckout();
    Account account = registeredAccount();
    Resp checkout = req("POST", CHECKOUT).bearer(account.token()).json("{}").send();
    assertThat(checkout.status()).as(checkout.toString()).isEqualTo(200);

    // The user deletes the account, then completes the still-open hosted checkout.
    deleteAccount(account);
    String subscription = stripeId("sub_");
    String customer = stripeId("cus_");
    stub(subscription(subscription, customer, "active", account.id(), end(30), "paid"));
    stripeCancels(subscription);
    String eventId = stripeId("evt_");

    assertAck(deliver(checkoutCompleted(eventId, account.id(), subscription, customer)));
    assertAck(deliver(invoiceEvent(stripeId("evt_"), "invoice.paid", subscription)));

    assertThat(outcome(eventId)).isEqualTo("user_missing");
    assertNothingOf(account.id());
    assertCanceledAtStripe(subscription);
  }

  @Test
  void aDeletionThatCommitsWhileAWebhookIsInFlightLeavesNothingCharging() throws Exception {
    Account account = registeredAccount();
    String subscription = stripeId("sub_");
    String customer = stripeId("cus_");
    // Stripe answers slowly: the webhook has passed its duplicate check and is reading Stripe
    // when the deletion commits.
    STRIPE.stubFor(
        WireMock.get(urlPathEqualTo("/v1/subscriptions/" + subscription))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withFixedDelay(1500)
                    .withBody(
                        subscription(
                                subscription, customer, "active", account.id(), end(30), "paid")
                            .json())));
    stripeCancels(subscription);
    String body = checkoutCompleted(stripeId("evt_"), account.id(), subscription, customer);
    String clientIp = randomIp();
    String header = sign(body);
    CompletableFuture<Resp> inFlight =
        CompletableFuture.supplyAsync(() ->
            http.req("POST", WEBHOOK)
                .ip(clientIp)
                .header("Stripe-Signature", header)
                .body(body, "application/json")
                .send());
    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .until(() ->
            !STRIPE
                .findAll(
                    WireMock.getRequestedFor(urlPathEqualTo("/v1/subscriptions/" + subscription)))
                .isEmpty());
    deleteAccount(account);

    Resp delivered = inFlight.get(30, TimeUnit.SECONDS);
    assertThat(delivered.status()).as(delivered.toString()).isIn(200, 409, 503);
    assertNoBillingLeak(delivered);
    assertNothingOf(account.id());
    assertCanceledAtStripe(subscription);
  }

  private void stripeServesCheckout() {
    STRIPE.stubFor(
        WireMock.post(urlPathEqualTo("/v1/checkout/sessions"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"id\":\"cs_test_deleted\",\"object\":\"checkout.session\","
                            + "\"url\":\"https://checkout.stripe.com/c/pay/cs_test_deleted\"}")));
  }
}
