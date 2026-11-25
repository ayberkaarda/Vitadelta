package com.vitadelta.api.billing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.vitadelta.api.entitlement.SqlTime;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

/**
 * Overlapping checkouts and subscriptions of one user (ADR-0014). Checkout only refuses while a
 * Stripe subscription has not ended; a second subscription that slips through (two checkouts paid
 * before the first webhook, parallel requests, a late completion event) is never canceled by the
 * API: PRO follows the subscription paid furthest ahead, the overlap is counted and logged, and the
 * operator cancels and refunds the surplus subscription.
 */
class StripeOverlapIntegrationTest extends StripeIntegrationTest {

  private static final String SESSIONS = "/v1/checkout/sessions";

  @Autowired MeterRegistry meters;

  private Instant end(int days) {
    return clock.instant().plus(Duration.ofDays(days)).truncatedTo(ChronoUnit.SECONDS);
  }

  private void sessionsAnswer(int status) {
    STRIPE.stubFor(
        WireMock.post(urlPathEqualTo(SESSIONS))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        status == 200
                            ? "{\"id\":\"cs_test_Overlap\","
                                + "\"url\":\"https://checkout.stripe.com/c/pay/cs_test_Overlap\"}"
                            : "{\"error\":{\"type\":\"api_error\"}}")));
  }

  private void cancelAnswers(String subscription, int status) {
    STRIPE.stubFor(
        WireMock.delete(urlPathEqualTo("/v1/subscriptions/" + subscription))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        status == 200
                            ? "{\"id\":\"" + subscription + "\",\"status\":\"canceled\"}"
                            : "{\"error\":{\"type\":\"api_error\"}}")));
  }

  private int cancels(String subscription) {
    return STRIPE
        .findAll(deleteRequestedFor(urlPathEqualTo("/v1/subscriptions/" + subscription)))
        .size();
  }

  private void completed(TestUser user, String subscription, String customer) {
    assertStatus(
        webhook(checkoutCompleted(stripeId("evt_"), user.id().toString(), subscription, customer)),
        200);
  }

  /** State as left by migration V8 for a PRO row applied before it: no paid_through. */
  private void legacyProviderRow(
      TestUser user, String subscription, String customer, Instant rowEnd) {
    transactions.executeWithoutResult(status -> {
      jdbc.sql(
              "UPDATE subscription SET status = 'CANCELED', canceled_at = now()"
                  + " WHERE user_id = ? AND status = 'ACTIVE'")
          .param(user.id())
          .update();
      jdbc.sql(
              "INSERT INTO subscription (id, user_id, plan_code, status, source, started_at,"
                  + " current_period_end, canceled_at, external_ref)"
                  + " VALUES (?, ?, 'PRO', 'ACTIVE', 'PROVIDER', now(), ?, NULL, ?)")
          .params(UUID.randomUUID(), user.id(), SqlTime.ts(rowEnd), subscription)
          .update();
      jdbc.sql(
              "INSERT INTO billing_subscription (provider, external_ref, user_id, customer_ref,"
                  + " status, current_period_end, paid_through, ended_at, updated_at)"
                  + " VALUES ('STRIPE', ?, ?, ?, 'active', ?, NULL, NULL, now())")
          .params(subscription, user.id(), customer, SqlTime.ts(rowEnd))
          .update();
    });
  }

  private double detected() {
    return meters.counter("vitadelta.billing.surplus.detected").count();
  }

  private void noCancellationOf(String... subscriptions) {
    for (String subscription : subscriptions) {
      assertThat(cancels(subscription)).as("cancellations of %s", subscription).isZero();
    }
  }

  @Test
  void twoCheckoutsPaidBeforeTheFirstWebhookKeepBothAndAreReported() {
    TestUser user = user();
    sessionsAnswer(200);
    // The second checkout starts before the first one's webhook arrived: both are allowed.
    assertStatus(post(CHECKOUT, "", user.token()), 200);
    assertStatus(post(CHECKOUT, "", user.token()), 200);
    String first = stripeId("sub_");
    String second = stripeId("sub_");
    String firstCustomer = stripeId("cus_");
    String secondCustomer = stripeId("cus_");
    subscription(first, firstCustomer, "active", user.id(), PRICE, end(30), "paid");
    subscription(second, secondCustomer, "active", user.id(), PRICE, end(31), "paid");
    cancelAnswers(first, 200);
    cancelAnswers(second, 200);
    double before = detected();

    completed(user, first, firstCustomer);
    completed(user, second, secondCustomer);

    Map<String, Object> active = active(user.id());
    assertThat(active.get("plan_code")).isEqualTo("PRO");
    assertThat(active.get("external_ref")).isEqualTo(second);
    noCancellationOf(first, second);
    assertThat(detected()).isGreaterThan(before);
    assertThat(
            jdbc.sql("SELECT customer_ref FROM billing_customer WHERE user_id = ?")
                .param(user.id())
                .query(String.class)
                .single())
        .isEqualTo(secondCustomer);

    // The operator cancels the surplus subscription; its webhook leaves PRO on the other one.
    subscription(first, firstCustomer, "canceled", user.id(), PRICE, end(30), "paid");
    assertStatus(
        webhook(subscriptionEvent(stripeId("evt_"), "customer.subscription.deleted", first)), 200);
    assertThat(active(user.id()).get("external_ref")).isEqualTo(second);
    noCancellationOf(first, second);
    assertProblem(post(CHECKOUT, "", user.token()), 409, "plan_already_active");
  }

  @Test
  void concurrentWebhooksOfTwoSubscriptionsNeverCancelEither() throws Exception {
    TestUser user = user();
    String first = stripeId("sub_");
    String second = stripeId("sub_");
    String firstCustomer = stripeId("cus_");
    String secondCustomer = stripeId("cus_");
    subscription(first, firstCustomer, "active", user.id(), PRICE, end(30), "paid");
    subscription(second, secondCustomer, "active", user.id(), PRICE, end(30), "paid");
    cancelAnswers(first, 200);
    cancelAnswers(second, 200);
    String firstBody =
        checkoutCompleted(stripeId("evt_"), user.id().toString(), first, firstCustomer);
    String secondBody =
        checkoutCompleted(stripeId("evt_"), user.id().toString(), second, secondCustomer);
    String firstSignature = signature(firstBody);
    String secondSignature = signature(secondBody);

    List<Integer> statuses =
        parallel(
            List.<Callable<Integer>>of(
                () ->
                    webhook(firstBody, Map.of("Stripe-Signature", firstSignature), randomIp())
                        .getStatus()
                        .value(),
                () ->
                    webhook(secondBody, Map.of("Stripe-Signature", secondSignature), randomIp())
                        .getStatus()
                        .value()));

    assertThat(statuses).containsOnly(200);
    noCancellationOf(first, second);
    assertThat(active(user.id()).get("plan_code")).isEqualTo("PRO");
    assertThat(activeCount(user.id())).isEqualTo(1);
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM billing_subscription WHERE user_id = ? AND ended_at IS NULL")
                .param(user.id())
                .query(Long.class)
                .single())
        .isEqualTo(2);
  }

  @Test
  void aLegacyHolderWithoutAPaidHorizonNeverCausesAPaidSubscriptionToBeCanceled() {
    TestUser user = user();
    String legacy = stripeId("sub_");
    String paid = stripeId("sub_");
    String customer = stripeId("cus_");
    Instant rowEnd = end(40);
    legacyProviderRow(user, legacy, customer, rowEnd);
    subscription(paid, customer, "active", user.id(), PRICE, end(30), "paid");
    cancelAnswers(legacy, 200);
    cancelAnswers(paid, 200);

    completed(user, paid, customer);

    // The legacy row still runs longer and keeps PRO; nothing is canceled.
    assertThat(active(user.id()).get("external_ref")).isEqualTo(legacy);
    assertThat(activeEnd(user.id())).isEqualTo(rowEnd);
    noCancellationOf(legacy, paid);
  }

  @Test
  void parallelCheckoutsAreNeverRefusedForEachOther() throws Exception {
    TestUser user = user();
    sessionsAnswer(200);
    List<EntityExchangeResult<String>> results =
        parallel(
            List.<Callable<EntityExchangeResult<String>>>of(
                () -> post(CHECKOUT, "", user.token(), randomIp(), null),
                () -> post(CHECKOUT, "", user.token(), randomIp(), null)));
    assertThat(results).allSatisfy(result -> assertStatus(result, 200));
    assertThat(STRIPE.findAll(postRequestedFor(urlPathEqualTo(SESSIONS)))).hasSize(2);
  }

  @Test
  void aLateCompletionOfAnEarlierCheckoutLeavesPROOnTheLaterOne() {
    TestUser user = user();
    String later = stripeId("sub_");
    String earlier = stripeId("sub_");
    String laterCustomer = stripeId("cus_");
    String earlierCustomer = stripeId("cus_");
    subscription(later, laterCustomer, "active", user.id(), PRICE, end(30), "paid");
    subscription(earlier, earlierCustomer, "active", user.id(), PRICE, end(29), "paid");
    cancelAnswers(earlier, 200);
    cancelAnswers(later, 200);

    completed(user, later, laterCustomer);
    // The completion event of an earlier session arrives last and is paid for a shorter period.
    completed(user, earlier, earlierCustomer);

    assertThat(active(user.id()).get("external_ref")).isEqualTo(later);
    assertThat(activeEnd(user.id())).isEqualTo(end(30));
    noCancellationOf(earlier, later);
    assertThat(
            jdbc.sql("SELECT customer_ref FROM billing_customer WHERE user_id = ?")
                .param(user.id())
                .query(String.class)
                .single())
        .isEqualTo(laterCustomer);
  }

  @Test
  void aFailedSessionCreationNeverBlocksTheNextCheckout() {
    TestUser user = user();
    // Stripe refuses to expire a session that is no longer open.
    STRIPE.stubFor(
        WireMock.post(WireMock.urlPathMatching(SESSIONS + "/[^/]+/expire"))
            .willReturn(aResponse().withStatus(400).withBody("{\"error\":{}}")));
    sessionsAnswer(200);
    assertStatus(post(CHECKOUT, "", user.token()), 200);
    sessionsAnswer(500);
    assertProblem(post(CHECKOUT, "", user.token()), 503, "billing_unavailable");
    sessionsAnswer(200);
    assertStatus(post(CHECKOUT, "", user.token()), 200);
    assertThat(STRIPE.findAll(postRequestedFor(urlPathEqualTo(SESSIONS)))).hasSize(3);
  }

  @Test
  void anActiveSubscriptionWithoutAPaidLatestInvoiceGrantsNothing() {
    TestUser user = user();
    String subscription = stripeId("sub_");
    String customer = stripeId("cus_");
    // Active, a future period, but no latest invoice in the read-back.
    subscription(subscription, customer, "active", user.id(), PRICE, end(30), null);
    completed(user, subscription, customer);
    assertThat(active(user.id()).get("plan_code")).isEqualTo("FREE");
    assertThat(
            jdbc.sql("SELECT paid_through FROM billing_subscription WHERE external_ref = ?")
                .param(subscription)
                .query(java.sql.Timestamp.class)
                .optional()
                .orElse(null))
        .isNull();

    // A trial with its paid zero-amount invoice grants PRO.
    subscription(subscription, customer, "trialing", user.id(), PRICE, end(14), "paid");
    assertStatus(
        webhook(subscriptionEvent(stripeId("evt_"), "customer.subscription.updated", subscription)),
        200);
    assertThat(active(user.id()).get("plan_code")).isEqualTo("PRO");
  }

  @Test
  void aSubscriptionWithoutAProvenPaymentKeepsAnExistingEntitlementUntilItsReadBack() {
    TestUser user = user();
    String subscription = stripeId("sub_");
    String customer = stripeId("cus_");
    Instant rowEnd = end(20);
    legacyProviderRow(user, subscription, customer, rowEnd);

    // A read-back without a paid invoice changes nothing: PRO stays as it was.
    subscription(subscription, customer, "active", user.id(), PRICE, end(50), "open");
    assertStatus(
        webhook(subscriptionEvent(stripeId("evt_"), "customer.subscription.updated", subscription)),
        200);
    assertThat(active(user.id()).get("plan_code")).isEqualTo("PRO");
    assertThat(activeEnd(user.id())).isEqualTo(rowEnd);

    // The paid read-back sets the paid horizon and extends the row.
    subscription(subscription, customer, "active", user.id(), PRICE, end(50), "paid");
    assertStatus(webhook(invoiceEvent(stripeId("evt_"), "invoice.paid", subscription)), 200);
    assertThat(activeEnd(user.id())).isEqualTo(end(50));
    assertThat(
            jdbc.sql("SELECT paid_through FROM billing_subscription WHERE external_ref = ?")
                .param(subscription)
                .query(java.sql.Timestamp.class)
                .single()
                .toInstant())
        .isEqualTo(end(50));
  }
}
