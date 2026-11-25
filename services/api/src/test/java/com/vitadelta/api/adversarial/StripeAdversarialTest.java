package com.vitadelta.api.adversarial;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.vitadelta.api.adversarial.AttackClient.Req;
import com.vitadelta.api.adversarial.AttackClient.Resp;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base of the adversarial tests of the Stripe billing layer (ADR-0014): the API runs with {@code
 * VITADELTA_BILLING_PROVIDER=stripe}, a test-mode key and a webhook signing secret generated for
 * this run, and the Stripe REST API pointed at a local WireMock stub. Nothing reaches the network
 * and no real key exists anywhere in the test.
 *
 * <p>Webhook signatures are computed here independently of the implementation (HMAC-SHA256 keyed
 * with the whole {@code whsec_...} string over {@code <t>.<raw body>}), against the movable
 * adversary clock that the application also uses.
 */
abstract class StripeAdversarialTest extends AdversarialIntegrationTest {

  static final String KEY = "sk_" + "test_" + randomSecret(24);
  static final String WEBHOOK_SECRET = "whsec_" + randomSecret(24);
  static final String PRICE = "price_1AdversaryPro";
  static final String WEBHOOK = API + "/billing/webhooks/stripe";
  static final String CHECKOUT = API + "/billing/checkout-session";
  static final String PORTAL = API + "/billing/portal-session";
  static final String WEB_BASE = "https://vitadelta.test";

  static final WireMockServer STRIPE = new WireMockServer(options().dynamicPort());

  static {
    STRIPE.start();
  }

  @DynamicPropertySource
  static void stripeProperties(DynamicPropertyRegistry registry) {
    registry.add("vitadelta.billing.provider", () -> "stripe");
    registry.add("vitadelta.billing.stripe.secret-key", () -> KEY);
    registry.add("vitadelta.billing.stripe.webhook-secret", () -> WEBHOOK_SECRET);
    registry.add("vitadelta.billing.stripe.price-id", () -> PRICE);
    registry.add("vitadelta.billing.stripe.api-base-url", STRIPE::baseUrl);
  }

  @BeforeEach
  void resetStripe() {
    STRIPE.resetAll();
  }

  // Identifiers and time ------------------------------------------------------------------------

  /** A fresh Stripe-style object id with the given prefix ({@code sub_}, {@code cus_}, ...). */
  static String stripeId(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
  }

  /** A period end on whole seconds, {@code days} after the adversary clock. */
  Instant end(int days) {
    return clock.instant().plus(Duration.ofDays(days)).truncatedTo(ChronoUnit.SECONDS);
  }

  long now() {
    return clock.instant().getEpochSecond();
  }

  // Signatures ----------------------------------------------------------------------------------

  /** {@code HMAC-SHA256(secret, "<t>." + body)} as lower-case hex. */
  static String hmac(String secret, long timestamp, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
      return HexFormat.of().formatHex(mac.doFinal(body));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A {@code Stripe-Signature} value as Stripe sends it. */
  static String signatureHeader(String secret, long timestamp, byte[] body) {
    return "t=" + timestamp + ",v1=" + hmac(secret, timestamp, body);
  }

  /** The header for {@code body}, signed now with the endpoint secret. */
  String sign(byte[] body) {
    return signatureHeader(WEBHOOK_SECRET, now(), body);
  }

  String sign(String body) {
    return sign(body.getBytes(StandardCharsets.UTF_8));
  }

  // Webhook requests ----------------------------------------------------------------------------

  /** A webhook request from this test's client address, without a signature header. */
  Req webhookRequest(byte[] body, String contentType) {
    return req("POST", WEBHOOK).body(body, contentType);
  }

  /** Posts {@code body} signed now, as Stripe does. */
  Resp deliver(String body) {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    return webhookRequest(bytes, "application/json; charset=utf-8")
        .header("Stripe-Signature", sign(bytes))
        .send();
  }

  /** Asserts the acknowledgement of an accepted delivery. */
  static void assertAck(Resp response) {
    assertThat(response.status()).as(response.toString()).isEqualTo(200);
    assertThat(response.json().get("received").asBoolean()).isTrue();
  }

  /**
   * Asserts the single generic rejection of an unauthentic or unreadable delivery: {@code 400}, the
   * framework's generic problem, no hint about which check failed, nothing leaked.
   */
  static void assertGenericRejection(Resp response) {
    assertThat(response.status()).as(response.toString()).isEqualTo(400);
    assertThat(response.header("content-type")).startsWith("application/problem+json");
    assertThat(response.json().get("type").asString()).isEqualTo("about:blank");
    assertThat(response.json().get("detail").asString()).isEqualTo("Bad Request.");
    assertNoBillingLeak(response);
  }

  /** No stack, framework names, Stripe object ids, keys, secrets or rejection reasons. */
  static void assertNoBillingLeak(Resp response) {
    AttackClient.assertNoLeak(response);
    String body = response.body() == null ? "" : response.body().toLowerCase(java.util.Locale.ROOT);
    for (String marker :
        List.of(
            "sub_",
            "cus_",
            "evt_",
            "price_",
            "sk_test",
            "whsec_",
            "stripe.com",
            "signature",
            "timestamp",
            "mismatch",
            "hmac",
            "malformed",
            "duplicate",
            "user_id",
            KEY.toLowerCase(java.util.Locale.ROOT),
            WEBHOOK_SECRET.toLowerCase(java.util.Locale.ROOT))) {
      assertThat(body).as("response must not contain %s: %s", marker, body).doesNotContain(marker);
    }
  }

  // Event bodies (shapes of the pinned API version) ---------------------------------------------

  static String event(String eventId, String type, boolean livemode, String object) {
    return "{\"id\":\""
        + eventId
        + "\",\"object\":\"event\",\"api_version\":\"2026-11-18.endive\",\"created\":1795000000,"
        + "\"livemode\":"
        + livemode
        + ",\"pending_webhooks\":1,\"type\":\""
        + type
        + "\",\"data\":{\"object\":"
        + object
        + "}}";
  }

  static String checkoutCompleted(
      String eventId, UUID clientReference, String subscriptionId, String customer) {
    return event(
        eventId,
        "checkout.session.completed",
        false,
        "{\"id\":\"cs_test_"
            + eventId.substring(4)
            + "\",\"object\":\"checkout.session\",\"mode\":\"subscription\","
            + "\"client_reference_id\":"
            + (clientReference == null ? "null" : "\"" + clientReference + "\"")
            + ",\"customer\":\""
            + customer
            + "\",\"subscription\":\""
            + subscriptionId
            + "\",\"payment_status\":\"paid\",\"status\":\"complete\"}");
  }

  static String invoiceEvent(String eventId, String type, String subscriptionId) {
    return event(
        eventId,
        type,
        false,
        "{\"id\":\"in_"
            + eventId.substring(4)
            + "\",\"object\":\"invoice\",\"status\":\"paid\",\"billing_reason\":"
            + "\"subscription_cycle\",\"parent\":{\"type\":\"subscription_details\","
            + "\"subscription_details\":{\"subscription\":\""
            + subscriptionId
            + "\",\"metadata\":{}}}}");
  }

  static String subscriptionEvent(String eventId, String type, String subscriptionId) {
    return event(
        eventId,
        type,
        false,
        "{\"id\":\"" + subscriptionId + "\",\"object\":\"subscription\",\"status\":\"active\"}");
  }

  // The Stripe stub -----------------------------------------------------------------------------

  /** What {@code GET /v1/subscriptions/{id}} answers. */
  record StubSubscription(
      String id,
      String customer,
      String status,
      String metadataJson,
      String price,
      Instant periodEnd,
      String latestInvoiceJson,
      boolean livemode,
      Instant endedAt) {

    String json() {
      return "{\"id\":\""
          + id
          + "\",\"object\":\"subscription\",\"customer\":\""
          + customer
          + "\",\"status\":\""
          + status
          + "\",\"livemode\":"
          + livemode
          + ",\"cancel_at_period_end\":false,\"ended_at\":"
          + (endedAt == null ? "null" : Long.toString(endedAt.getEpochSecond()))
          + ",\"metadata\":"
          + metadataJson
          + ",\"items\":{\"object\":\"list\",\"data\":[{\"id\":\"si_1Adversary\","
          + "\"object\":\"subscription_item\",\"current_period_end\":"
          + periodEnd.getEpochSecond()
          + ",\"price\":{\"id\":\""
          + price
          + "\",\"object\":\"price\"}}]},\"latest_invoice\":"
          + latestInvoiceJson
          + "}";
    }
  }

  static String metadata(UUID userId) {
    return userId == null ? "{}" : "{\"user_id\":\"" + userId + "\"}";
  }

  static String invoice(String status) {
    return status == null
        ? "null"
        : "{\"id\":\"in_1Adversary\",\"object\":\"invoice\",\"status\":\"" + status + "\"}";
  }

  /** A subscription of this API's checkout: configured price, test mode, given state. */
  StubSubscription subscription(
      String id,
      String customer,
      String status,
      UUID userId,
      Instant periodEnd,
      String invoiceStatus) {
    return new StubSubscription(
        id,
        customer,
        status,
        metadata(userId),
        PRICE,
        periodEnd,
        invoice(invoiceStatus),
        false,
        status.equals("canceled") ? clock.instant() : null);
  }

  void stub(StubSubscription subscription) {
    stubRaw(subscription.id(), 200, subscription.json());
  }

  void stubRaw(String subscriptionId, int status, String body) {
    STRIPE.stubFor(
        WireMock.get(urlPathEqualTo("/v1/subscriptions/" + subscriptionId))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));
  }

  /** Every request the API sent to the Stripe stub. */
  static List<LoggedRequest> stripeRequests() {
    return STRIPE.findAll(WireMock.anyRequestedFor(WireMock.anyUrl()));
  }

  // Users and state -----------------------------------------------------------------------------

  /** A verified FREE user (inserted directly) and a valid access token for it. */
  record Customer(UUID id, String token) {}

  Customer freeUser() {
    UUID id = insertUser(true);
    return new Customer(id, accessToken(id, "USER", true));
  }

  /** The ACTIVE subscription row of the user. */
  Map<String, Object> active(UUID userId) {
    return jdbc.sql(
            "SELECT plan_code, source, current_period_end, external_ref FROM subscription"
                + " WHERE user_id = ? AND status = 'ACTIVE'")
        .param(userId)
        .query()
        .singleRow();
  }

  String plan(UUID userId) {
    return (String) active(userId).get("plan_code");
  }

  Instant activeEnd(UUID userId) {
    Object end = active(userId).get("current_period_end");
    return end == null ? null : ((Timestamp) end).toInstant();
  }

  long activeRows(UUID userId) {
    return count(
        "SELECT count(*) FROM subscription WHERE user_id = ? AND status = 'ACTIVE'", userId);
  }

  String outcome(String eventId) {
    return jdbc.sql("SELECT outcome FROM billing_webhook_event WHERE event_id = ?")
        .param(eventId)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  long billingRows(UUID userId) {
    return count("SELECT count(*) FROM billing_customer WHERE user_id = ?", userId)
        + count("SELECT count(*) FROM billing_subscription WHERE user_id = ?", userId);
  }

  /** A user who completed checkout: PRO until {@code end(30)} through {@code subscription}. */
  record Paid(Customer user, String subscription, String customer) {}

  Paid paidUser() {
    Customer user = freeUser();
    String subscription = stripeId("sub_");
    String customer = stripeId("cus_");
    stub(subscription(subscription, customer, "active", user.id(), end(30), "paid"));
    assertAck(deliver(checkoutCompleted(stripeId("evt_"), user.id(), subscription, customer)));
    assertThat(plan(user.id())).isEqualTo("PRO");
    return new Paid(user, subscription, customer);
  }
}
