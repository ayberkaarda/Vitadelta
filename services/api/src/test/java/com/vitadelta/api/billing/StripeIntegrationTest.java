package com.vitadelta.api.billing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.vitadelta.api.entitlement.EntitlementIntegrationTest;
import com.vitadelta.api.entitlement.SqlTime;
import com.vitadelta.api.support.TestEnvironment;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Base of the Stripe integration tests: the application runs with {@code
 * VITADELTA_BILLING_PROVIDER=stripe}, a test-mode key and webhook secret generated per run, and the
 * Stripe API pointed at a local WireMock stub (no network access to Stripe). Webhooks are signed
 * here exactly as Stripe signs them, against the application's test clock.
 */
abstract class StripeIntegrationTest extends EntitlementIntegrationTest {

  static final String KEY = "sk_" + "test_" + TestEnvironment.randomHex(24);
  static final String WEBHOOK_SECRET = "whsec_" + TestEnvironment.randomHex(24);
  static final String PRICE = "price_1SyntheticPro";
  static final String WEBHOOK = ProviderBillingController.STRIPE_WEBHOOK;
  static final String CHECKOUT = "/api/v1/billing/checkout-session";
  static final String PORTAL = "/api/v1/billing/portal-session";

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

  /** A fresh Stripe-style id with the given prefix. */
  static String stripeId(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
  }

  /** Stubs {@code GET /v1/subscriptions/{id}} with the documented subscription shape. */
  void subscription(
      String id,
      String customer,
      String status,
      UUID userId,
      String price,
      Instant periodEnd,
      String invoiceStatus) {
    String metadata = userId == null ? "{}" : "{\"user_id\":\"" + userId + "\"}";
    String invoice =
        invoiceStatus == null
            ? "null"
            : "{\"id\":\"in_1Synthetic\",\"object\":\"invoice\",\"status\":\""
                + invoiceStatus
                + "\"}";
    String ended =
        status.equals("canceled") ? Long.toString(clock.instant().getEpochSecond()) : "null";
    String body =
        "{\"id\":\""
            + id
            + "\",\"object\":\"subscription\",\"customer\":\""
            + customer
            + "\",\"status\":\""
            + status
            + "\",\"livemode\":false,\"cancel_at_period_end\":false,\"ended_at\":"
            + ended
            + ",\"metadata\":"
            + metadata
            + ",\"items\":{\"object\":\"list\",\"data\":[{\"id\":\"si_1Synthetic\","
            + "\"object\":\"subscription_item\",\"current_period_end\":"
            + periodEnd.getEpochSecond()
            + ",\"price\":{\"id\":\""
            + price
            + "\",\"object\":\"price\"}}]},\"latest_invoice\":"
            + invoice
            + "}";
    STRIPE.stubFor(
        WireMock.get(urlPathEqualTo("/v1/subscriptions/" + id))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));
  }

  /** A {@code checkout.session.completed} event of a subscription-mode session. */
  static String checkoutCompleted(
      String eventId, String clientReference, String subscriptionId, String customer) {
    return event(
        eventId,
        "checkout.session.completed",
        "{\"id\":\"cs_test_"
            + eventId.substring(4)
            + "\",\"object\":\"checkout.session\",\"mode\":\"subscription\","
            + "\"client_reference_id\":"
            + (clientReference == null ? "null" : "\"" + clientReference + "\"")
            + ",\"customer\":\""
            + customer
            + "\",\"customer_details\":{\"email\":\"someone.else@entitlement.example.org\"},"
            + "\"subscription\":\""
            + subscriptionId
            + "\",\"payment_status\":\"paid\"}");
  }

  /** An {@code invoice.*} event of the subscription (API 2025-03-31 and later shape). */
  static String invoiceEvent(String eventId, String type, String subscriptionId) {
    return event(
        eventId,
        type,
        "{\"id\":\"in_"
            + eventId.substring(4)
            + "\",\"object\":\"invoice\",\"billing_reason\":\"subscription_cycle\","
            + "\"parent\":{\"type\":\"subscription_details\",\"subscription_details\":"
            + "{\"subscription\":\""
            + subscriptionId
            + "\",\"metadata\":{}}}}");
  }

  /** A {@code customer.subscription.*} event. */
  static String subscriptionEvent(String eventId, String type, String subscriptionId) {
    return event(
        eventId,
        type,
        "{\"id\":\"" + subscriptionId + "\",\"object\":\"subscription\",\"status\":\"active\"}");
  }

  static String event(String eventId, String type, String object) {
    return "{\"id\":\""
        + eventId
        + "\",\"object\":\"event\",\"api_version\":\"2026-11-18.endive\",\"created\":1795000000,"
        + "\"livemode\":false,\"pending_webhooks\":1,\"type\":\""
        + type
        + "\",\"data\":{\"object\":"
        + object
        + "}}";
  }

  /** The {@code Stripe-Signature} value for {@code body} signed now (test clock). */
  String signature(String body) {
    return signature(body, clock.instant().getEpochSecond(), WEBHOOK_SECRET);
  }

  static String signature(String body, long timestamp, String secret) {
    return StripeSignatureVerifierTest.header(
        secret, timestamp, body.getBytes(StandardCharsets.UTF_8));
  }

  /** Posts a webhook signed now. */
  EntityExchangeResult<String> webhook(String body) {
    return webhook(body, Map.of("Stripe-Signature", signature(body)), ip);
  }

  EntityExchangeResult<String> webhook(String body, Map<String, String> headers, String clientIp) {
    RestTestClient.RequestBodySpec spec =
        client
            .post()
            .uri(WEBHOOK)
            .contentType(MediaType.parseMediaType("application/json; charset=utf-8"))
            .header("X-Forwarded-For", clientIp);
    for (Map.Entry<String, String> header : headers.entrySet()) {
      spec = spec.header(header.getKey(), header.getValue());
    }
    return spec.body(body.getBytes(StandardCharsets.UTF_8)).exchange().returnResult(String.class);
  }

  /** The ACTIVE subscription row of the user. */
  Map<String, Object> active(UUID userId) {
    return jdbc.sql(
            "SELECT plan_code, status, source, current_period_end, external_ref FROM subscription"
                + " WHERE user_id = ? AND status = 'ACTIVE'")
        .param(userId)
        .query()
        .singleRow();
  }

  Instant activeEnd(UUID userId) {
    Object end = active(userId).get("current_period_end");
    return end == null ? null : ((java.sql.Timestamp) end).toInstant();
  }

  /** Inserts the user's Stripe customer directly. */
  void customer(UUID userId, String customer) {
    jdbc.sql(
            "INSERT INTO billing_customer (user_id, provider, customer_ref, created_at,"
                + " updated_at) VALUES (?, 'STRIPE', ?, ?, ?)")
        .params(userId, customer, SqlTime.ts(clock.instant()), SqlTime.ts(clock.instant()))
        .update();
  }

  long webhookEvents(String eventId) {
    return jdbc.sql("SELECT count(*) FROM billing_webhook_event WHERE event_id = ?")
        .param(eventId)
        .query(Long.class)
        .single();
  }
}
