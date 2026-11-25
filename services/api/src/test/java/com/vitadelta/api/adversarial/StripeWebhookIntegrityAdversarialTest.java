package com.vitadelta.api.adversarial;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.vitadelta.api.adversarial.AttackClient.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.vitadelta.api.adversarial.AttackClient.Resp;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Section 15.7, Stripe webhook integrity: even a correctly signed delivery can grant PRO only from
 * the subscription state read back from Stripe, only to the user this API bound the subscription to
 * at checkout, only for a paid period of the configured price, and only forward in time. Payload
 * claims, e-mail addresses, foreign or other-mode objects, stale and reordered events, parallel
 * duplicates, hostile object ids and a hostile or failing Stripe never move an entitlement the
 * wrong way or leak anything.
 */
class StripeWebhookIntegrityAdversarialTest extends StripeAdversarialTest {

  @Test
  void payloadClaimsNeverGrantAnythingOnlyTheStateReadFromStripeDoes() {
    Customer user = freeUser();
    String subscription = stripeId("sub_");
    String customer = stripeId("cus_");
    // Stripe: the subscription is past due and its latest invoice is open.
    stub(subscription(subscription, customer, "past_due", user.id(), end(30), "open"));
    String lying =
        event(
            stripeId("evt_"),
            "invoice.paid",
            false,
            "{\"id\":\"in_1Forged\",\"object\":\"invoice\",\"status\":\"paid\",\"paid\":true,"
                + "\"amount_paid\":19900,\"period_end\":4102444800,\"customer_email\":\"x@example.org\","
                + "\"subscription\":{\"id\":\""
                + subscription
                + "\",\"status\":\"active\",\"metadata\":{\"user_id\":\""
                + user.id()
                + "\"},\"items\":{\"data\":[{\"current_period_end\":4102444800,"
                + "\"price\":{\"id\":\""
                + PRICE
                + "\"}}]},\"latest_invoice\":{\"status\":\"paid\"}}}");
    assertAck(deliver(lying));
    assertThat(plan(user.id())).isEqualTo("FREE");

    // Stripe: active and paid until end(30); the payload's year-2100 period is ignored.
    stub(subscription(subscription, customer, "active", user.id(), end(30), "paid"));
    assertAck(
        deliver(
            lying.replace("in_1Forged", "in_1Forged2").replace("\"id\":\"evt_", "\"id\":\"evt_2")));
    assertThat(plan(user.id())).isEqualTo("PRO");
    assertThat(activeEnd(user.id())).isEqualTo(end(30));
  }

  @Test
  void aForgedInvoicePaidWithoutAPaidLatestInvoiceGrantsNothing() {
    Customer user = freeUser();
    String customer = stripeId("cus_");
    List<StubSubscription> unpaid = new ArrayList<>();
    for (String invoiceStatus : List.of("open", "draft", "uncollectible", "void")) {
      unpaid.add(
          subscription(stripeId("sub_"), customer, "active", user.id(), end(30), invoiceStatus));
    }
    for (String status :
        List.of("past_due", "unpaid", "incomplete", "incomplete_expired", "paused")) {
      unpaid.add(subscription(stripeId("sub_"), customer, status, user.id(), end(30), "paid"));
    }
    // The latest invoice not expanded (an id only) cannot be shown to be paid.
    String id = stripeId("sub_");
    unpaid.add(
        new StubSubscription(
            id,
            customer,
            "active",
            metadata(user.id()),
            PRICE,
            end(30),
            "\"in_1NotExpanded\"",
            false,
            null));
    // Active and paid, but the period already ended.
    unpaid.add(subscription(stripeId("sub_"), customer, "active", user.id(), end(-1), "paid"));
    for (StubSubscription subscription : unpaid) {
      stub(subscription);
      assertAck(deliver(invoiceEvent(stripeId("evt_"), "invoice.paid", subscription.id())));
      assertThat(plan(user.id())).as(subscription.json()).isEqualTo("FREE");
    }
    assertThat(activeRows(user.id())).isEqualTo(1);
  }

  @Test
  void anotherUsersIdInTheMetadataOrSessionNeverMovesAnEntitlement() {
    Customer attacker = freeUser();
    Customer victim = freeUser();
    String customer = stripeId("cus_");

    // The session names the attacker, the subscription the victim.
    String a = stripeId("sub_");
    stub(subscription(a, customer, "active", victim.id(), end(30), "paid"));
    assertAck(deliver(checkoutCompleted(stripeId("evt_"), attacker.id(), a, customer)));
    // Metadata that is not one plain UUID string, or names a user that does not exist.
    for (String metadata :
        List.of(
            "{\"user_id\":\" " + attacker.id() + "\"}",
            "{\"user_id\":\"" + attacker.id() + "' OR '1'='1\"}",
            "{\"user_id\":[\"" + attacker.id() + "\"]}",
            "{\"user_id\":{\"id\":\"" + attacker.id() + "\"}}",
            "{\"userId\":\"" + attacker.id() + "\"}",
            "{\"user_id\":\"" + UUID.randomUUID() + "\"}",
            "{\"user_id\":\"" + attacker.id() + "\",\"user_id\":\"" + victim.id() + "\"}")) {
      String sub = stripeId("sub_");
      stub(
          new StubSubscription(
              sub, customer, "active", metadata, PRICE, end(30), invoice("paid"), false, null));
      Resp response = deliver(invoiceEvent(stripeId("evt_"), "invoice.paid", sub));
      assertThat(response.status()).as(metadata + " " + response).isIn(200, 503);
      assertNoBillingLeak(response);
    }
    assertThat(plan(attacker.id())).isEqualTo("FREE");
    assertThat(plan(victim.id())).isEqualTo("FREE");
    assertThat(billingRows(attacker.id()) + billingRows(victim.id())).isZero();
  }

  @Test
  void aSubscriptionAlreadyBoundToOneUserIsNeverReassignedToAnother() {
    Paid owner = paidUser();
    Customer intruder = freeUser();
    // The metadata of the owner's subscription was changed to name the intruder (for example by
    // another integration on the same Stripe account); the customer is new.
    String newCustomer = stripeId("cus_");
    stub(subscription(owner.subscription(), newCustomer, "active", intruder.id(), end(60), "paid"));
    String eventId = stripeId("evt_");
    assertAck(deliver(invoiceEvent(eventId, "invoice.paid", owner.subscription())));
    assertThat(outcome(eventId)).isEqualTo("customer_conflict");
    assertThat(plan(intruder.id())).isEqualTo("FREE");
    assertThat(billingRows(intruder.id())).isZero();
    assertThat(activeEnd(owner.user().id())).isEqualTo(end(30));

    // The owner's customer under a fresh subscription naming the intruder: refused as well, and
    // the intruder can open no billing portal for that customer.
    String other = stripeId("sub_");
    stub(subscription(other, owner.customer(), "active", intruder.id(), end(30), "paid"));
    String second = stripeId("evt_");
    assertAck(deliver(checkoutCompleted(second, intruder.id(), other, owner.customer())));
    assertThat(outcome(second)).isEqualTo("customer_conflict");
    assertThat(plan(intruder.id())).isEqualTo("FREE");
    Resp portal =
        req("POST", PORTAL)
            .bearer(intruder.token())
            .json("{\"customer\":\"" + owner.customer() + "\"}")
            .send();
    assertProblem(portal, 409, "no_billing_account");
    assertThat(stripeRequests())
        .noneMatch(request -> request.getUrl().startsWith("/v1/billing_portal"));
  }

  @Test
  void staleOrReorderedEventsCannotDowngradeOrResurrect() {
    Paid paid = paidUser();
    // A "deleted" event while Stripe still reports the subscription active and paid.
    assertAck(
        deliver(
            subscriptionEvent(
                stripeId("evt_"), "customer.subscription.deleted", paid.subscription())));
    assertAck(
        deliver(invoiceEvent(stripeId("evt_"), "invoice.payment_failed", paid.subscription())));
    assertThat(plan(paid.user().id())).isEqualTo("PRO");
    assertThat(activeEnd(paid.user().id())).isEqualTo(end(30));

    // The cancellation of an older, unrelated subscription of the same user does not end the
    // current one.
    String old = stripeId("sub_");
    stub(subscription(old, paid.customer(), "canceled", paid.user().id(), end(5), "paid"));
    assertAck(deliver(subscriptionEvent(stripeId("evt_"), "customer.subscription.deleted", old)));
    assertThat(plan(paid.user().id())).isEqualTo("PRO");
    assertThat(active(paid.user().id()).get("external_ref")).isEqualTo(paid.subscription());

    // A deletion delivered before the checkout it follows: the user never becomes PRO.
    Customer late = freeUser();
    String sub = stripeId("sub_");
    String customer = stripeId("cus_");
    stub(subscription(sub, customer, "canceled", late.id(), end(30), "paid"));
    assertAck(deliver(subscriptionEvent(stripeId("evt_"), "customer.subscription.deleted", sub)));
    assertAck(deliver(checkoutCompleted(stripeId("evt_"), late.id(), sub, customer)));
    stub(subscription(sub, customer, "active", late.id(), end(30), "paid"));
    assertAck(deliver(subscriptionEvent(stripeId("evt_"), "customer.subscription.updated", sub)));
    assertThat(plan(late.id())).isEqualTo("FREE");
  }

  @Test
  void parallelDuplicatesOfOneEventApplyOnceAndNeverLeak() throws Exception {
    Customer user = freeUser();
    String subscription = stripeId("sub_");
    String customer = stripeId("cus_");
    stub(subscription(subscription, customer, "active", user.id(), end(30), "paid"));
    String body = checkoutCompleted(stripeId("evt_"), user.id(), subscription, customer);
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    String header = sign(bytes);
    int deliveries = 12;
    ExecutorService pool = Executors.newFixedThreadPool(deliveries);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Resp>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < deliveries; i++) {
        String clientIp = randomIp();
        futures.add(
            pool.submit(() -> {
              start.await();
              return http.req("POST", WEBHOOK)
                  .ip(clientIp)
                  .header("Stripe-Signature", header)
                  .body(bytes, "application/json")
                  .send();
            }));
      }
      start.countDown();
      List<Resp> responses = new ArrayList<>();
      for (Future<Resp> future : futures) {
        responses.add(future.get(60, TimeUnit.SECONDS));
      }
      assertThat(responses).anySatisfy(StripeAdversarialTest::assertAck);
      for (Resp response : responses) {
        // A delivery that loses the race is rolled back; Stripe retries it.
        assertThat(response.status()).as(response.toString()).isIn(200, 409, 500, 503);
        assertNoBillingLeak(response);
      }
    } finally {
      pool.shutdownNow();
    }
    assertThat(plan(user.id())).isEqualTo("PRO");
    assertThat(activeRows(user.id())).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM subscription WHERE user_id = ?", user.id()))
        .isEqualTo(2);
    assertThat(count("SELECT count(*) FROM billing_customer WHERE user_id = ?", user.id()))
        .isEqualTo(1);
  }

  @Test
  void liveModeAndForeignObjectsAreIgnoredWithoutReadingAnythingElse() {
    Customer user = freeUser();
    String subscription = stripeId("sub_");
    String customer = stripeId("cus_");
    stub(subscription(subscription, customer, "active", user.id(), end(30), "paid"));
    // A live-mode event on the test-mode endpoint.
    assertAck(
        deliver(
            checkoutCompleted(stripeId("evt_"), user.id(), subscription, customer)
                .replace("\"livemode\":false", "\"livemode\":true")));
    assertThat(stripeRequests()).isEmpty();
    // A test-mode event whose subscription Stripe reports in live mode.
    String live = stripeId("sub_");
    stub(
        new StubSubscription(
            live,
            customer,
            "active",
            metadata(user.id()),
            PRICE,
            end(30),
            invoice("paid"),
            true,
            null));
    assertAck(deliver(invoiceEvent(stripeId("evt_"), "invoice.paid", live)));
    // Unknown and unhandled types, including look-alikes of handled ones.
    for (String type :
        List.of(
            "customer.subscription.created",
            "invoice.payment_succeeded",
            "charge.succeeded",
            "checkout.session.async_payment_succeeded",
            "invoice.paid.v2",
            "customer.subscription.updated.",
            "account.application.authorized")) {
      assertAck(deliver(subscriptionEvent(stripeId("evt_"), type, subscription)));
    }
    // A subscription of the same account for another price.
    String otherPrice = stripeId("sub_");
    stub(
        new StubSubscription(
            otherPrice,
            customer,
            "active",
            metadata(user.id()),
            "price_1Other",
            end(30),
            invoice("paid"),
            false,
            null));
    assertAck(deliver(invoiceEvent(stripeId("evt_"), "invoice.paid", otherPrice)));
    assertThat(plan(user.id())).isEqualTo("FREE");
    assertThat(billingRows(user.id())).isZero();
    assertThat(stripeRequests())
        .allMatch(request -> request.getMethod().getName().equals("GET"))
        .noneMatch(request -> request.getUrl().contains(subscription));
  }

  @Test
  void hostileObjectIdsNeverBecomePartOfAStripeRequest() {
    Customer user = freeUser();
    List<String> ids =
        List.of(
            "sub_1/../../v1/customers",
            "sub_1%2F..%2Fcharges",
            "sub_1?expand[]=customer",
            "sub_1#fragment",
            "sub_1\\u0000",
            "sub_1 HTTP/1.1\\r\\nHost: evil.example",
            "sub_" + "A".repeat(300),
            "cus_123",
            "../sub_1",
            "http://169.254.169.254/latest/meta-data");
    for (String id : ids) {
      String escaped = id.replace("\"", "\\\"");
      Resp invoice = deliver(invoiceEvent(stripeId("evt_"), "invoice.paid", escaped));
      Resp updated =
          deliver(subscriptionEvent(stripeId("evt_"), "customer.subscription.updated", escaped));
      Resp checkout =
          deliver(checkoutCompleted(stripeId("evt_"), user.id(), escaped, stripeId("cus_")));
      for (Resp response : List.of(invoice, updated, checkout)) {
        assertThat(response.status()).as(id + " " + response).isIn(200, 400);
        assertNoBillingLeak(response);
      }
    }
    // An expanded object is reduced to its id; nothing else in it is trusted.
    String subscription = stripeId("sub_");
    String customer = stripeId("cus_");
    stub(subscription(subscription, customer, "past_due", user.id(), end(30), "open"));
    String expanded =
        checkoutCompleted(stripeId("evt_"), user.id(), subscription, customer)
            .replace(
                "\"subscription\":\"" + subscription + "\"",
                "\"subscription\":{\"id\":\""
                    + subscription
                    + "\",\"status\":\"active\",\"latest_invoice\":{\"status\":\"paid\"}}");
    assertAck(deliver(expanded));
    assertThat(stripeRequests())
        .allSatisfy(request ->
            assertThat(request.getUrl())
                .matches("^/v1/subscriptions/sub_[A-Za-z0-9]+\\?expand%5B%5D=latest_invoice$"));
    assertThat(plan(user.id())).isEqualTo("FREE");
  }

  @Test
  void aHostileOrFailingStripeNeverGrantsAndNeverLeaks() {
    Customer user = freeUser();
    String customer = stripeId("cus_");
    List<String> leakyBodies =
        List.of(
            "{\"error\":{\"type\":\"api_error\",\"message\":\"Invalid API Key provided: "
                + KEY
                + " for "
                + customer
                + "\",\"code\":\"secret_leak\"}}",
            "<html>java.lang.IllegalStateException at com.stripe.Internal</html>",
            "{\"id\":\"sub_1\",\"object\":\"subscription\"",
            "");
    for (int status : List.of(500, 502, 401, 429)) {
      for (String body : leakyBodies) {
        String subscription = stripeId("sub_");
        stubRaw(subscription, status, body);
        Resp response =
            deliver(checkoutCompleted(stripeId("evt_"), user.id(), subscription, customer));
        assertProblem(response, 503, "billing_unavailable");
        assertNoBillingLeak(response);
      }
    }
    // A redirect towards an internal address is not followed.
    String redirected = stripeId("sub_");
    STRIPE.stubFor(
        WireMock.get(urlPathEqualTo("/v1/subscriptions/" + redirected))
            .willReturn(
                aResponse()
                    .withStatus(302)
                    .withHeader("Location", STRIPE.baseUrl() + "/internal/metadata")));
    assertProblem(
        deliver(checkoutCompleted(stripeId("evt_"), user.id(), redirected, customer)),
        503,
        "billing_unavailable");
    assertThat(stripeRequests()).noneMatch(request -> request.getUrl().startsWith("/internal"));
    // A successful answer that is ambiguous (duplicate keys) or of another shape.
    String ambiguous = stripeId("sub_");
    stubRaw(
        ambiguous,
        200,
        subscription(ambiguous, customer, "past_due", user.id(), end(30), "open")
            .json()
            .replace("\"status\":\"past_due\"", "\"status\":\"past_due\",\"status\":\"active\"")
            .replace("\"status\":\"open\"", "\"status\":\"open\",\"status\":\"paid\""));
    Resp duplicateKeys =
        deliver(checkoutCompleted(stripeId("evt_"), user.id(), ambiguous, customer));
    assertProblem(duplicateKeys, 503, "billing_unavailable");
    String wrongId = stripeId("sub_");
    stubRaw(
        wrongId,
        200,
        subscription("sub_../" + wrongId, customer, "active", user.id(), end(30), "paid").json());
    Resp mismatched = deliver(checkoutCompleted(stripeId("evt_"), user.id(), wrongId, customer));
    assertThat(mismatched.status()).isIn(200, 503);
    assertNoBillingLeak(mismatched);

    assertThat(plan(user.id())).isEqualTo("FREE");
    assertThat(billingRows(user.id())).isZero();
  }
}
