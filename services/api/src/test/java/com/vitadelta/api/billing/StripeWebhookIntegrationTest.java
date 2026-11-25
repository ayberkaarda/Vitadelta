package com.vitadelta.api.billing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.vitadelta.api.entitlement.Contract;
import com.vitadelta.api.entitlement.SubscriptionSource;
import com.vitadelta.api.identity.AccountService;
import com.vitadelta.api.identity.PasswordHasher;
import com.vitadelta.api.mail.EmailMessage;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;

/** {@code POST /billing/webhooks/stripe} against a stubbed Stripe API (ADR-0014). */
class StripeWebhookIntegrationTest extends StripeIntegrationTest {

  @Autowired AccountService accounts;
  @Autowired PasswordHasher hasher;
  @Autowired io.micrometer.core.instrument.MeterRegistry meters;

  /** A period end on whole seconds (Stripe timestamps), {@code days} after the test clock. */
  private Instant end(int days) {
    return clock.instant().plus(Duration.ofDays(days)).truncatedTo(ChronoUnit.SECONDS);
  }

  private record Paid(TestUser user, String subscription, String customer) {}

  /** A user who completed checkout: PRO until {@code end(30)}. */
  private Paid paidUser() {
    TestUser user = user();
    String subscription = stripeId("sub_");
    String customer = stripeId("cus_");
    subscription(subscription, customer, "active", user.id(), PRICE, end(30), "paid");
    assertAck(
        webhook(checkoutCompleted(stripeId("evt_"), user.id().toString(), subscription, customer)));
    return new Paid(user, subscription, customer);
  }

  private static void assertAck(EntityExchangeResult<String> result) {
    assertStatus(result, 200);
    Contract.assertConforms("WebhookAck", result.getResponseBody());
    assertThat(json(result).get("received").asBoolean()).isTrue();
  }

  @Test
  void aCompletedCheckoutActivatesProFromTheSubscriptionState() {
    TestUser user = user();
    String subscription = stripeId("sub_");
    String customer = stripeId("cus_");
    subscription(subscription, customer, "active", user.id(), PRICE, end(30), "paid");
    String eventId = stripeId("evt_");

    assertAck(webhook(checkoutCompleted(eventId, user.id().toString(), subscription, customer)));

    Map<String, Object> active = active(user.id());
    assertThat(active.get("plan_code")).isEqualTo("PRO");
    assertThat(active.get("source")).isEqualTo("PROVIDER");
    assertThat(active.get("external_ref")).isEqualTo(subscription);
    assertThat(activeEnd(user.id())).isEqualTo(end(30));
    assertThat(activeCount(user.id())).isEqualTo(1);
    assertThat(
            jdbc.sql("SELECT customer_ref FROM billing_customer WHERE user_id = ?")
                .param(user.id())
                .query(String.class)
                .single())
        .isEqualTo(customer);
    assertThat(webhookEvents(eventId)).isEqualTo(1);
    List<EmailMessage> mails = mail().await(user.email(), 1);
    assertThat(mails.get(0).subject()).isEqualTo("Your VitaDelta Pro plan is active");
    assertThat(events.activated)
        .anySatisfy(event -> {
          assertThat(event.userId()).isEqualTo(user.id());
          assertThat(event.source()).isEqualTo(SubscriptionSource.PROVIDER);
          assertThat(event.extended()).isFalse();
        });
    assertThat(STRIPE.findAll(getRequestedFor(urlPathEqualTo("/v1/subscriptions/" + subscription))))
        .singleElement()
        .satisfies(request -> {
          assertThat(request.getHeader("Authorization")).isEqualTo("Bearer " + KEY);
          assertThat(request.getHeader("Stripe-Version")).isEqualTo(StripeApi.API_VERSION);
        });
    JsonNode me = json(get("/api/v1/me", user.token()));
    assertThat(me.get("subscription").get("source").asString()).isEqualTo("PROVIDER");
    assertThat(me.toString()).doesNotContain(subscription).doesNotContain(customer);
  }

  @Test
  void aRepeatedDeliveryIsAppliedOnce() {
    TestUser user = user();
    String subscription = stripeId("sub_");
    String customer = stripeId("cus_");
    subscription(subscription, customer, "active", user.id(), PRICE, end(30), "paid");
    String body = checkoutCompleted(stripeId("evt_"), user.id().toString(), subscription, customer);

    assertAck(webhook(body));
    // Stripe signs every delivery anew: a retry carries a new timestamp and signature.
    clock.advance(Duration.ofSeconds(90));
    assertAck(webhook(body));
    assertAck(webhook(body));

    assertThat(subscriptions(user.id())).hasSize(2);
    assertThat(activeCount(user.id())).isEqualTo(1);
    mail().await(user.email(), 1);
    assertThat(mail().to(user.email())).hasSize(1);
    // The fast path answers repeated deliveries without asking Stripe again.
    assertThat(STRIPE.findAll(getRequestedFor(urlPathEqualTo("/v1/subscriptions/" + subscription))))
        .hasSize(1);
  }

  @Test
  void parallelDeliveriesOfOneEventApplyOnce() throws Exception {
    TestUser user = user();
    String subscription = stripeId("sub_");
    String customer = stripeId("cus_");
    subscription(subscription, customer, "active", user.id(), PRICE, end(30), "paid");
    String body = checkoutCompleted(stripeId("evt_"), user.id().toString(), subscription, customer);
    String signature = signature(body);
    List<Callable<Integer>> deliveries = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      deliveries.add(() ->
          webhook(body, Map.of("Stripe-Signature", signature), randomIp()).getStatus().value());
    }
    List<Integer> statuses = parallel(deliveries);
    // A delivery that loses the race to record the event is rolled back and retried by Stripe.
    assertThat(statuses).contains(200).allMatch(status -> status == 200 || status == 500);
    assertThat(subscriptions(user.id())).hasSize(2);
    assertThat(activeCount(user.id())).isEqualTo(1);
    mail().await(user.email(), 1);
    assertThat(mail().to(user.email())).hasSize(1);
  }

  @Test
  void aPaidRenewalExtendsThePeriodWithoutAnotherMail() {
    Paid paid = paidUser();
    mail().await(paid.user().email(), 1);
    subscription(
        paid.subscription(), paid.customer(), "active", paid.user().id(), PRICE, end(61), "paid");

    assertAck(webhook(invoiceEvent(stripeId("evt_"), "invoice.paid", paid.subscription())));

    assertThat(activeEnd(paid.user().id())).isEqualTo(end(61));
    assertThat(subscriptions(paid.user().id())).hasSize(2);
    assertThat(events.activated.stream().filter(e -> e.userId().equals(paid.user().id())))
        .hasSize(1);
    assertThat(mail().to(paid.user().email())).hasSize(1);
  }

  @Test
  void anOpenOrFailedRenewalGrantsNothingUntilItIsPaid() {
    Paid paid = paidUser();
    // Stripe moves the period forward when the renewal invoice is created, before it is paid.
    subscription(
        paid.subscription(), paid.customer(), "active", paid.user().id(), PRICE, end(61), "open");
    assertAck(
        webhook(
            subscriptionEvent(
                stripeId("evt_"), "customer.subscription.updated", paid.subscription())));
    assertThat(activeEnd(paid.user().id())).isEqualTo(end(30));

    subscription(
        paid.subscription(), paid.customer(), "past_due", paid.user().id(), PRICE, end(61), "open");
    assertAck(
        webhook(invoiceEvent(stripeId("evt_"), "invoice.payment_failed", paid.subscription())));
    Map<String, Object> active = active(paid.user().id());
    assertThat(active.get("plan_code")).isEqualTo("PRO");
    assertThat(activeEnd(paid.user().id())).isEqualTo(end(30));

    subscription(
        paid.subscription(), paid.customer(), "active", paid.user().id(), PRICE, end(61), "paid");
    assertAck(webhook(invoiceEvent(stripeId("evt_"), "invoice.paid", paid.subscription())));
    assertThat(activeEnd(paid.user().id())).isEqualTo(end(61));
  }

  @Test
  void aDeletedSubscriptionDowngradesAtOnceAndSendsThePlanExpiredMail() {
    Paid paid = paidUser();
    mail().await(paid.user().email(), 1);
    subscription(
        paid.subscription(), paid.customer(), "canceled", paid.user().id(), PRICE, end(30), "paid");

    assertAck(
        webhook(
            subscriptionEvent(
                stripeId("evt_"), "customer.subscription.deleted", paid.subscription())));

    Map<String, Object> active = active(paid.user().id());
    assertThat(active.get("plan_code")).isEqualTo("FREE");
    assertThat(subscriptions(paid.user().id()))
        .anySatisfy(row -> {
          assertThat(row.get("plan_code")).isEqualTo("PRO");
          assertThat(row.get("status")).isEqualTo("CANCELED");
          assertThat(row.get("canceled_at")).isNotNull();
        });
    assertThat(mail().await(paid.user().email(), 2).get(1).subject()).contains("ended");
    assertThat(events.expired).anySatisfy(e -> assertThat(e.userId()).isEqualTo(paid.user().id()));
  }

  @Test
  void aLateActiveStateNeverRevivesAnEndedSubscription() {
    Paid paid = paidUser();
    subscription(
        paid.subscription(), paid.customer(), "canceled", paid.user().id(), PRICE, end(30), "paid");
    assertAck(
        webhook(
            subscriptionEvent(
                stripeId("evt_"), "customer.subscription.deleted", paid.subscription())));
    assertThat(active(paid.user().id()).get("plan_code")).isEqualTo("FREE");

    // An older invoice.paid delivered afterwards, whose read of Stripe raced the cancellation.
    subscription(
        paid.subscription(), paid.customer(), "active", paid.user().id(), PRICE, end(61), "paid");
    assertAck(webhook(invoiceEvent(stripeId("evt_"), "invoice.paid", paid.subscription())));

    assertThat(active(paid.user().id()).get("plan_code")).isEqualTo("FREE");
    assertThat(
            jdbc.sql("SELECT status FROM billing_subscription WHERE external_ref = ?")
                .param(paid.subscription())
                .query(String.class)
                .single())
        .isEqualTo("canceled");
  }

  @Test
  void anOlderPeriodNeverShortensANewerOne() {
    Paid paid = paidUser();
    subscription(
        paid.subscription(), paid.customer(), "active", paid.user().id(), PRICE, end(61), "paid");
    assertAck(webhook(invoiceEvent(stripeId("evt_"), "invoice.paid", paid.subscription())));
    assertThat(activeEnd(paid.user().id())).isEqualTo(end(61));

    subscription(
        paid.subscription(), paid.customer(), "active", paid.user().id(), PRICE, end(30), "paid");
    assertAck(
        webhook(
            subscriptionEvent(
                stripeId("evt_"), "customer.subscription.updated", paid.subscription())));
    assertThat(activeEnd(paid.user().id())).isEqualTo(end(61));
  }

  @Test
  void aCheckoutWhileOnAPromoPeriodKeepsTheLaterEnd() {
    TestUser user = user();
    transactions.executeWithoutResult(status ->
        subscriptionService.activate(
            user.id(), com.vitadelta.api.entitlement.PlanCode.PRO, 90, SubscriptionSource.PROMO));
    Instant promoEnd = activeEnd(user.id());
    String subscription = stripeId("sub_");
    String customer = stripeId("cus_");
    subscription(subscription, customer, "active", user.id(), PRICE, end(30), "paid");

    assertAck(
        webhook(checkoutCompleted(stripeId("evt_"), user.id().toString(), subscription, customer)));

    Map<String, Object> active = active(user.id());
    assertThat(active.get("source")).isEqualTo("PROVIDER");
    assertThat(active.get("external_ref")).isEqualTo(subscription);
    assertThat(activeEnd(user.id())).isEqualTo(promoEnd);
  }

  @Test
  void theUserComesFromTheMetadataNeverFromAnEmailAddress() {
    TestUser buyer = user();
    TestUser other = user();
    String subscription = stripeId("sub_");
    String customer = stripeId("cus_");
    // The session names one user, the subscription metadata another: nothing is applied.
    subscription(subscription, customer, "active", other.id(), PRICE, end(30), "paid");
    assertAck(
        webhook(
            checkoutCompleted(stripeId("evt_"), buyer.id().toString(), subscription, customer)));
    assertThat(active(buyer.id()).get("plan_code")).isEqualTo("FREE");
    assertThat(active(other.id()).get("plan_code")).isEqualTo("FREE");

    // A subscription without the metadata (not created by this API) is ignored.
    String foreign = stripeId("sub_");
    subscription(foreign, customer, "active", null, PRICE, end(30), "paid");
    assertAck(webhook(invoiceEvent(stripeId("evt_"), "invoice.paid", foreign)));
    // So is a subscription for another price.
    String otherPrice = stripeId("sub_");
    subscription(otherPrice, customer, "active", buyer.id(), "price_1Unrelated", end(30), "paid");
    assertAck(webhook(invoiceEvent(stripeId("evt_"), "invoice.paid", otherPrice)));
    // And a checkout without a client reference.
    assertAck(webhook(checkoutCompleted(stripeId("evt_"), null, subscription, customer)));

    assertThat(active(buyer.id()).get("plan_code")).isEqualTo("FREE");
    assertThat(active(other.id()).get("plan_code")).isEqualTo("FREE");
  }

  @Test
  void aCustomerOfAnotherUserIsNotTakenOver() {
    Paid paid = paidUser();
    TestUser intruder = user();
    String subscription = stripeId("sub_");
    subscription(subscription, paid.customer(), "active", intruder.id(), PRICE, end(30), "paid");
    String eventId = stripeId("evt_");

    assertAck(
        webhook(
            checkoutCompleted(eventId, intruder.id().toString(), subscription, paid.customer())));

    assertThat(active(intruder.id()).get("plan_code")).isEqualTo("FREE");
    assertThat(
            jdbc.sql("SELECT outcome FROM billing_webhook_event WHERE event_id = ?")
                .param(eventId)
                .query(String.class)
                .single())
        .isEqualTo("customer_conflict");
    assertThat(
            jdbc.sql("SELECT count(*) FROM billing_customer WHERE user_id = ?")
                .param(intruder.id())
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void otherModesTypesAndModesOfSessionsAreAcknowledgedAndIgnored() {
    TestUser user = user();
    String subscription = stripeId("sub_");
    String customer = stripeId("cus_");
    subscription(subscription, customer, "active", user.id(), PRICE, end(30), "paid");
    String live =
        checkoutCompleted(stripeId("evt_"), user.id().toString(), subscription, customer)
            .replace("\"livemode\":false", "\"livemode\":true");
    assertAck(webhook(live));
    assertAck(webhook(event(stripeId("evt_"), "customer.created", "{\"id\":\"cus_1\"}")));
    String payment =
        checkoutCompleted(stripeId("evt_"), user.id().toString(), subscription, customer)
            .replace("\"mode\":\"subscription\"", "\"mode\":\"payment\"");
    assertAck(webhook(payment));

    assertThat(active(user.id()).get("plan_code")).isEqualTo("FREE");
    assertThat(STRIPE.findAll(getRequestedFor(urlPathEqualTo("/v1/subscriptions/" + subscription))))
        .isEmpty();
  }

  @Test
  void aStripeOutageIsA503AndTheEventIsAppliedOnRetry() {
    TestUser user = user();
    String subscription = stripeId("sub_");
    String customer = stripeId("cus_");
    STRIPE.stubFor(
        WireMock.get(urlPathEqualTo("/v1/subscriptions/" + subscription))
            .willReturn(aResponse().withStatus(500).withBody("{}")));
    String eventId = stripeId("evt_");
    String body = checkoutCompleted(eventId, user.id().toString(), subscription, customer);

    assertProblem(webhook(body), 503, "billing_unavailable");
    assertThat(webhookEvents(eventId)).isZero();

    subscription(subscription, customer, "active", user.id(), PRICE, end(30), "paid");
    assertAck(webhook(body));
    assertThat(active(user.id()).get("plan_code")).isEqualTo("PRO");
  }

  @Test
  void aSubscriptionStripeDoesNotKnowIsIgnored() {
    TestUser user = user();
    String subscription = stripeId("sub_");
    STRIPE.stubFor(
        WireMock.get(urlPathEqualTo("/v1/subscriptions/" + subscription))
            .willReturn(
                aResponse()
                    .withStatus(404)
                    .withBody("{\"error\":{\"type\":\"invalid_request_error\"}}")));
    assertAck(webhook(invoiceEvent(stripeId("evt_"), "invoice.paid", subscription)));
    assertThat(active(user.id()).get("plan_code")).isEqualTo("FREE");
  }

  @Test
  void everyUnauthenticRequestGetsTheSameGeneric400() {
    TestUser user = user();
    String subscription = stripeId("sub_");
    String customer = stripeId("cus_");
    subscription(subscription, customer, "active", user.id(), PRICE, end(30), "paid");
    String body = checkoutCompleted(stripeId("evt_"), user.id().toString(), subscription, customer);
    long now = clock.instant().getEpochSecond();
    String requestId = "stripe-" + UUID.randomUUID().toString().substring(0, 8);

    Map<String, Map<String, String>> cases = new LinkedHashMap<>();
    cases.put("missing header", Map.of());
    cases.put(
        "wrong secret",
        Map.of("Stripe-Signature", signature(body, now, "whsec_" + "x".repeat(32))));
    cases.put(
        "stale timestamp", Map.of("Stripe-Signature", signature(body, now - 301, WEBHOOK_SECRET)));
    cases.put(
        "future timestamp", Map.of("Stripe-Signature", signature(body, now + 301, WEBHOOK_SECRET)));
    cases.put("malformed header", Map.of("Stripe-Signature", "v1=abc"));
    cases.put("v0 only", Map.of("Stripe-Signature", signature(body).replace("v1=", "v0=")));
    List<EntityExchangeResult<String>> results = new ArrayList<>();
    for (Map<String, String> headers : cases.values()) {
      Map<String, String> withId = new LinkedHashMap<>(headers);
      withId.put("X-Request-Id", requestId);
      results.add(webhook(body, withId, ip));
    }
    String tampered = body.replace(user.id().toString(), user().id().toString());
    results.add(
        webhook(
            tampered, Map.of("Stripe-Signature", signature(body), "X-Request-Id", requestId), ip));
    // A correctly signed body that is not an event.
    String notAnEvent = "{\"object\":\"event\"}";
    results.add(
        webhook(
            notAnEvent,
            Map.of("Stripe-Signature", signature(notAnEvent), "X-Request-Id", requestId),
            ip));
    // A repeated header (sent as two header lines by the client).
    results.add(
        client
            .post()
            .uri(WEBHOOK)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .header("X-Forwarded-For", ip)
            .header("X-Request-Id", requestId)
            .header("Stripe-Signature", signature(body), signature(body))
            .body(body)
            .exchange()
            .returnResult(String.class));

    for (EntityExchangeResult<String> result : results) {
      assertProblem(result, 400, "about:blank");
      assertThat(json(result).get("detail").asString()).isEqualTo("Bad Request.");
    }
    byte[] reference = results.get(0).getResponseBodyContent();
    assertThat(results)
        .allSatisfy(result -> assertThat(result.getResponseBodyContent()).isEqualTo(reference));
    assertThat(active(user.id()).get("plan_code")).isEqualTo("FREE");
    assertThat(STRIPE.findAll(getRequestedFor(urlPathEqualTo("/v1/subscriptions/" + subscription))))
        .isEmpty();
  }

  @Test
  void theWebhookNeedsNoCredentialsButHasItsOwnBodyLimitAndRateCap() {
    String padded =
        event(stripeId("evt_"), "customer.created", "{\"pad\":\"" + "x".repeat(100_000) + "\"}");
    // Over the general 64 kB limit, under the webhook limit of 256 kB.
    assertAck(webhook(padded));
    String huge =
        event(stripeId("evt_"), "customer.created", "{\"pad\":\"" + "x".repeat(300_000) + "\"}");
    assertProblem(webhook(huge), 413, "validation_failed");

    String clientIp = randomIp();
    String junk = event(stripeId("evt_"), "customer.created", "{}");
    // 300 per minute, refilled greedily while the loop runs: the cap is reached after at least
    // 300 accepted requests (unauthentic ones count as well).
    int accepted = 0;
    EntityExchangeResult<String> limited = null;
    while (limited == null && accepted < 1000) {
      EntityExchangeResult<String> result =
          webhook(junk, Map.of("Stripe-Signature", "t=1,v1=00"), clientIp);
      if (result.getStatus().value() == 429) {
        limited = result;
      } else {
        assertThat(result.getStatus().value()).isEqualTo(400);
        accepted++;
      }
    }
    assertThat(accepted).isGreaterThanOrEqualTo(300);
    assertThat(limited).isNotNull();
    assertProblem(limited, 429, "rate_limited");
    assertThat(limited.getResponseHeaders().getFirst("Retry-After")).isNotBlank();
    // Another client address is not affected.
    assertAck(webhook(junk, Map.of("Stripe-Signature", signature(junk)), randomIp()));
  }

  @Test
  void deletingAnAccountCancelsItsStripeSubscriptionAfterCommit() {
    Paid paid = paidUser();
    String password = "correct-horse-battery-staple";
    jdbc.sql("UPDATE app_user SET password_hash = ? WHERE id = ?")
        .params(hasher.hash(password), paid.user().id())
        .update();
    STRIPE.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.delete(
                urlPathEqualTo("/v1/subscriptions/" + paid.subscription()))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id\":\"" + paid.subscription() + "\",\"status\":\"canceled\"}")));

    accounts.delete(paid.user().id(), password);

    assertThat(
            STRIPE.findAll(
                deleteRequestedFor(urlPathEqualTo("/v1/subscriptions/" + paid.subscription()))))
        .hasSize(1);
    assertThat(
            jdbc.sql("SELECT count(*) FROM billing_subscription WHERE user_id = ?")
                .param(paid.user().id())
                .query(Long.class)
                .single())
        .isZero();

    // The deleted event that follows finds no user and changes nothing.
    subscription(
        paid.subscription(), paid.customer(), "canceled", paid.user().id(), PRICE, end(30), "paid");
    String eventId = stripeId("evt_");
    assertAck(
        webhook(subscriptionEvent(eventId, "customer.subscription.deleted", paid.subscription())));
    assertThat(
            jdbc.sql("SELECT outcome FROM billing_webhook_event WHERE event_id = ?")
                .param(eventId)
                .query(String.class)
                .single())
        .isEqualTo("user_missing");
  }

  @Test
  void aFailedCancellationDoesNotBlockTheDeletion() {
    Paid paid = paidUser();
    String password = "correct-horse-battery-staple";
    jdbc.sql("UPDATE app_user SET password_hash = ? WHERE id = ?")
        .params(hasher.hash(password), paid.user().id())
        .update();
    STRIPE.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.delete(
                urlPathEqualTo("/v1/subscriptions/" + paid.subscription()))
            .willReturn(aResponse().withStatus(500)));

    accounts.delete(paid.user().id(), password);

    assertThat(users.findById(paid.user().id())).isEmpty();
  }

  @Test
  void aLiveSubscriptionOfADeletedUserIsCanceledAndAFailedCancellationIsRetried() {
    TestUser user = user();
    jdbc.sql("DELETE FROM app_user WHERE id = ?").param(user.id()).update();
    String subscription = stripeId("sub_");
    String customer = stripeId("cus_");
    subscription(subscription, customer, "active", user.id(), PRICE, end(30), "paid");
    STRIPE.stubFor(
        WireMock.delete(urlPathEqualTo("/v1/subscriptions/" + subscription))
            .willReturn(aResponse().withStatus(500).withBody("{}")));
    String eventId = stripeId("evt_");
    String body = invoiceEvent(eventId, "invoice.paid", subscription);
    double failedBefore = meters.counter("vitadelta.billing.cancel_on_delete.failed").count();

    assertProblem(webhook(body), 503, "billing_unavailable");
    assertThat(webhookEvents(eventId)).isZero();
    assertThat(meters.counter("vitadelta.billing.cancel_on_delete.failed").count())
        .isEqualTo(failedBefore + 1);

    STRIPE.stubFor(
        WireMock.delete(urlPathEqualTo("/v1/subscriptions/" + subscription))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id\":\"" + subscription + "\",\"status\":\"canceled\"}")));
    assertAck(webhook(body));
    assertThat(
            jdbc.sql("SELECT outcome FROM billing_webhook_event WHERE event_id = ?")
                .param(eventId)
                .query(String.class)
                .single())
        .isEqualTo("user_missing");
    assertThat(
            STRIPE.findAll(deleteRequestedFor(urlPathEqualTo("/v1/subscriptions/" + subscription))))
        .hasSize(2);
    assertThat(
            jdbc.sql("SELECT count(*) FROM billing_subscription WHERE external_ref = ?")
                .param(subscription)
                .query(Long.class)
                .single())
        .isZero();
  }

  private String customerOf(UUID userId) {
    return jdbc.sql("SELECT customer_ref FROM billing_customer WHERE user_id = ?")
        .param(userId)
        .query(String.class)
        .single();
  }

  /** A second subscription of the same user, completed through its own checkout. */
  private void secondCheckout(Paid first, String subscription, String customer, int days) {
    subscription(subscription, customer, "active", first.user().id(), PRICE, end(days), "paid");
    assertAck(
        webhook(
            checkoutCompleted(
                stripeId("evt_"), first.user().id().toString(), subscription, customer)));
  }

  private void deleted(Paid paid) {
    subscription(
        paid.subscription(), paid.customer(), "canceled", paid.user().id(), PRICE, end(30), "paid");
    assertAck(
        webhook(
            subscriptionEvent(
                stripeId("evt_"), "customer.subscription.deleted", paid.subscription())));
  }

  @Test
  void overlappingSubscriptionsKeepProWhileOneOfThemIsPaid() {
    Paid first = paidUser();
    String second = stripeId("sub_");
    String secondCustomer = stripeId("cus_");
    secondCheckout(first, second, secondCustomer, 45);

    // The entitlement follows the subscription that is paid furthest, under its own reference.
    assertThat(active(first.user().id()).get("external_ref")).isEqualTo(second);
    assertThat(activeEnd(first.user().id())).isEqualTo(end(45));

    // Ending the first subscription does not drop PRO that the second one still pays for.
    deleted(first);
    Map<String, Object> active = active(first.user().id());
    assertThat(active.get("plan_code")).isEqualTo("PRO");
    assertThat(active.get("external_ref")).isEqualTo(second);
    assertThat(activeEnd(first.user().id())).isEqualTo(end(45));

    // A renewal of the second subscription extends its own row.
    subscription(second, secondCustomer, "active", first.user().id(), PRICE, end(75), "paid");
    assertAck(webhook(invoiceEvent(stripeId("evt_"), "invoice.paid", second)));
    assertThat(activeEnd(first.user().id())).isEqualTo(end(75));
    assertThat(active(first.user().id()).get("external_ref")).isEqualTo(second);
  }

  @Test
  void aSecondSubscriptionPaidForAShorterPeriodIsCanceledAsSurplus() {
    Paid first = paidUser();
    String second = stripeId("sub_");
    STRIPE.stubFor(
        WireMock.delete(urlPathEqualTo("/v1/subscriptions/" + second))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id\":\"" + second + "\",\"status\":\"canceled\"}")));
    // The second subscription is paid for a shorter period: the first keeps the entitlement and
    // the second is canceled at Stripe.
    secondCheckout(first, second, stripeId("cus_"), 20);
    assertThat(active(first.user().id()).get("external_ref")).isEqualTo(first.subscription());
    assertThat(activeEnd(first.user().id())).isEqualTo(end(30));
    assertThat(STRIPE.findAll(deleteRequestedFor(urlPathEqualTo("/v1/subscriptions/" + second))))
        .hasSize(1);
    assertThat(
            jdbc.sql("SELECT ended_at FROM billing_subscription WHERE external_ref = ?")
                .param(second)
                .query(java.sql.Timestamp.class)
                .single())
        .isNotNull();

    // When the entitled subscription ends, nothing else pays for PRO.
    deleted(first);
    assertThat(active(first.user().id()).get("plan_code")).isEqualTo("FREE");
  }

  @Test
  void aLateEventOfAnotherSubscriptionNeverMovesThePortalToItsCustomer() {
    Paid first = paidUser();
    String second = stripeId("sub_");
    String secondCustomer = stripeId("cus_");
    secondCheckout(first, second, secondCustomer, 45);
    assertThat(customerOf(first.user().id())).isEqualTo(secondCustomer);

    deleted(first);
    assertThat(customerOf(first.user().id())).isEqualTo(secondCustomer);

    // A live but unpaid update of the first subscription does not take the portal either.
    subscription(
        first.subscription(),
        first.customer(),
        "past_due",
        first.user().id(),
        PRICE,
        end(60),
        "open");
    assertAck(
        webhook(
            subscriptionEvent(
                stripeId("evt_"), "customer.subscription.updated", first.subscription())));
    assertThat(customerOf(first.user().id())).isEqualTo(secondCustomer);
  }

  @Test
  void aTerminalEventSetsNoCustomerForAUserWithoutOne() {
    TestUser user = user();
    String subscription = stripeId("sub_");
    subscription(subscription, stripeId("cus_"), "canceled", user.id(), PRICE, end(30), "paid");
    assertAck(
        webhook(
            subscriptionEvent(stripeId("evt_"), "customer.subscription.deleted", subscription)));
    assertThat(
            jdbc.sql("SELECT count(*) FROM billing_customer WHERE user_id = ?")
                .param(user.id())
                .query(Long.class)
                .single())
        .isZero();
  }
}
