package com.vitadelta.api.billing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.vitadelta.api.billing.StripeApiException.Reason;
import com.vitadelta.api.support.TestEnvironment;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Stripe REST calls against a WireMock stub answering with the documented shapes of API version
 * {@value StripeApi#API_VERSION} (no network access to Stripe, keys generated per run).
 */
class StripeApiTest {

  static final String KEY = "sk_" + "test_" + TestEnvironment.randomHex(24);

  private static WireMockServer server;
  private StripeApi api;

  @BeforeAll
  static void start() {
    server = new WireMockServer(options().dynamicPort());
    server.start();
  }

  @AfterAll
  static void stop() {
    server.stop();
  }

  @BeforeEach
  void reset() {
    server.resetAll();
    api = new StripeApi(properties(server.baseUrl(), Duration.ofSeconds(2), 65_536));
  }

  static BillingProperties properties(String baseUrl, Duration readTimeout, int maxBytes) {
    return new BillingProperties(
        "stripe",
        new BillingProperties.Stripe(
            KEY,
            "whsec_" + TestEnvironment.randomHex(24),
            "price_1SyntheticPro",
            false,
            baseUrl,
            Duration.ofSeconds(2),
            readTimeout,
            maxBytes),
        Duration.ofMinutes(5));
  }

  static Map<String, String> form(LoggedRequest request) {
    Map<String, String> fields = new LinkedHashMap<>();
    for (String pair : request.getBodyAsString().split("&")) {
      int eq = pair.indexOf('=');
      fields.put(
          URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
          URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
    }
    return fields;
  }

  private void answer(String method, String path, int status, String body) {
    var response =
        aResponse()
            .withStatus(status)
            .withHeader("Content-Type", "application/json")
            .withBody(body);
    server.stubFor(
        switch (method) {
          case "POST" -> post(urlPathEqualTo(path)).willReturn(response);
          case "DELETE" -> delete(urlPathEqualTo(path)).willReturn(response);
          default -> get(urlPathEqualTo(path)).willReturn(response);
        });
  }

  @Test
  void aCheckoutSessionIsCreatedInSubscriptionModeForTheUser() {
    answer(
        "POST",
        "/v1/checkout/sessions",
        200,
        "{\"id\":\"cs_test_1\",\"object\":\"checkout.session\","
            + "\"url\":\"https://checkout.stripe.com/c/pay/cs_test_1\"}");
    UUID user = UUID.randomUUID();

    String url =
        api.createCheckoutSession(
            new StripeApi.CheckoutRequest(
                user,
                "price_1SyntheticPro",
                "cus_Synthetic1",
                "https://vitadelta.test/upgrade?checkout=success",
                "https://vitadelta.test/upgrade?checkout=canceled",
                "tr"));

    assertThat(url).isEqualTo("https://checkout.stripe.com/c/pay/cs_test_1");
    LoggedRequest request =
        server.findAll(postRequestedFor(urlPathEqualTo("/v1/checkout/sessions"))).get(0);
    assertThat(request.getHeader("Authorization")).isEqualTo("Bearer " + KEY);
    assertThat(request.getHeader("Stripe-Version")).isEqualTo("2026-11-18.endive");
    assertThat(request.getHeader("Content-Type")).startsWith("application/x-www-form-urlencoded");
    assertThat(form(request))
        .containsEntry("mode", "subscription")
        .containsEntry("line_items[0][price]", "price_1SyntheticPro")
        .containsEntry("line_items[0][quantity]", "1")
        .containsEntry("client_reference_id", user.toString())
        .containsEntry("metadata[user_id]", user.toString())
        .containsEntry("subscription_data[metadata][user_id]", user.toString())
        .containsEntry("success_url", "https://vitadelta.test/upgrade?checkout=success")
        .containsEntry("cancel_url", "https://vitadelta.test/upgrade?checkout=canceled")
        .containsEntry("customer", "cus_Synthetic1")
        .containsEntry("locale", "tr")
        .doesNotContainKey("customer_email");
  }

  @Test
  void aNewCustomerIsLeftToCheckout() {
    answer(
        "POST",
        "/v1/checkout/sessions",
        200,
        "{\"id\":\"cs_test_2\",\"url\":\"https://checkout.stripe.com/x\"}");
    api.createCheckoutSession(
        new StripeApi.CheckoutRequest(
            UUID.randomUUID(),
            "price_1SyntheticPro",
            null,
            "https://a.test/s",
            "https://a.test/c",
            "en"));
    LoggedRequest request =
        server.findAll(postRequestedFor(urlPathEqualTo("/v1/checkout/sessions"))).get(0);
    assertThat(form(request)).doesNotContainKey("customer");
  }

  @Test
  void aSessionWithoutAnHttpsUrlIsRejected() {
    for (String body :
        List.of(
            "{\"url\":\"http://checkout.stripe.com/x\"}",
            "{\"url\":null}",
            "{\"id\":\"cs_1\"}",
            "{\"url\":\"javascript:alert(1)\"}",
            "{\"url\":\"https://user@evil.test/\"}",
            "{\"url\":\"https://bad host/\"}")) {
      server.resetAll();
      answer("POST", "/v1/billing_portal/sessions", 200, body);
      StripeApiException error =
          catchThrowableOfType(
              StripeApiException.class,
              () -> api.createPortalSession("cus_Synthetic1", "https://vitadelta.test/account"));
      assertThat(error).as(body).isNotNull();
      assertThat(error.reason()).isEqualTo(Reason.MALFORMED);
    }
  }

  @Test
  void aPortalSessionReturnsToTheGivenUrl() {
    answer(
        "POST",
        "/v1/billing_portal/sessions",
        200,
        "{\"url\":\"https://billing.stripe.com/p/session/test_1\"}");
    assertThat(api.createPortalSession("cus_Synthetic1", "https://vitadelta.test/account"))
        .isEqualTo("https://billing.stripe.com/p/session/test_1");
    LoggedRequest request =
        server.findAll(postRequestedFor(urlPathEqualTo("/v1/billing_portal/sessions"))).get(0);
    assertThat(form(request))
        .containsEntry("customer", "cus_Synthetic1")
        .containsEntry("return_url", "https://vitadelta.test/account");
  }

  @Test
  void stripeErrorsNameTheTypeButNotTheBody() {
    answer(
        "POST",
        "/v1/checkout/sessions",
        400,
        "{\"error\":{\"type\":\"invalid_request_error\",\"code\":\"resource_missing\","
            + "\"message\":\"No such price: 'price_x' secret detail\",\"param\":\"line_items\"}}");
    StripeApiException error =
        catchThrowableOfType(
            StripeApiException.class,
            () ->
                api.createCheckoutSession(
                    new StripeApi.CheckoutRequest(
                        UUID.randomUUID(),
                        "price_x1",
                        null,
                        "https://a.test/s",
                        "https://a.test/c",
                        "en")));
    assertThat(error.reason()).isEqualTo(Reason.HTTP_STATUS);
    assertThat(error.status()).isEqualTo(400);
    assertThat(error.getMessage())
        .contains("type=invalid_request_error", "code=resource_missing")
        .doesNotContain("secret detail")
        .doesNotContain(KEY);
    assertThat(StripeApi.errorSummary("not json".getBytes(StandardCharsets.UTF_8))).isNull();
    assertThat(StripeApi.errorSummary("{\"error\":\"x\"}".getBytes(StandardCharsets.UTF_8)))
        .isNull();
    assertThat(
            StripeApi.errorSummary(
                "{\"error\":{\"type\":\"Bad Type!\"}}".getBytes(StandardCharsets.UTF_8)))
        .isNull();
  }

  static String subscriptionJson(
      String id, String status, String userId, long periodEnd, String invoice) {
    return "{\"id\":\""
        + id
        + "\",\"object\":\"subscription\",\"customer\":\"cus_Synthetic1\",\"status\":\""
        + status
        + "\",\"livemode\":false,\"ended_at\":null,\"metadata\":"
        + (userId == null ? "{}" : "{\"user_id\":\"" + userId + "\"}")
        + ",\"items\":{\"object\":\"list\",\"data\":[{\"id\":\"si_1\",\"current_period_end\":"
        + (periodEnd - 100)
        + ",\"price\":{\"id\":\"price_other\"}},{\"id\":\"si_2\",\"current_period_end\":"
        + periodEnd
        + ",\"price\":{\"id\":\"price_1SyntheticPro\"}}]},\"latest_invoice\":"
        + invoice
        + "}";
  }

  @Test
  void aSubscriptionIsReadWithItsExpandedLatestInvoice() {
    String user = UUID.randomUUID().toString();
    answer(
        "GET",
        "/v1/subscriptions/sub_Synthetic1",
        200,
        subscriptionJson(
            "sub_Synthetic1",
            "active",
            user,
            1_800_000_000L,
            "{\"id\":\"in_1\",\"status\":\"paid\"}"));

    StripeSubscription subscription = api.retrieveSubscription("sub_Synthetic1");

    assertThat(subscription.id()).isEqualTo("sub_Synthetic1");
    assertThat(subscription.customer()).isEqualTo("cus_Synthetic1");
    assertThat(subscription.userId()).isEqualTo(user);
    assertThat(subscription.priceIds())
        .containsExactlyInAnyOrder("price_other", "price_1SyntheticPro");
    assertThat(subscription.currentPeriodEnd()).isEqualTo(Instant.ofEpochSecond(1_800_000_000L));
    assertThat(subscription.latestInvoiceStatus()).isEqualTo("paid");
    assertThat(subscription.currentPeriodPaid()).isTrue();
    assertThat(subscription.terminal()).isFalse();
    LoggedRequest request =
        server.findAll(getRequestedFor(urlPathEqualTo("/v1/subscriptions/sub_Synthetic1"))).get(0);
    assertThat(request.getUrl())
        .isEqualTo("/v1/subscriptions/sub_Synthetic1?expand%5B%5D=latest_invoice");
    assertThat(request.getHeader("Stripe-Version")).isEqualTo(StripeApi.API_VERSION);
  }

  @Test
  void theInvoiceAndStatusDecideWhetherThePeriodIsPaid() {
    long end = 1_800_000_000L;
    answer(
        "GET",
        "/v1/subscriptions/sub_A",
        200,
        subscriptionJson("sub_A", "active", null, end, "\"in_unexpanded\""));
    StripeSubscription unexpanded = api.retrieveSubscription("sub_A");
    assertThat(unexpanded.latestInvoiceStatus()).isEqualTo("unknown");
    assertThat(unexpanded.currentPeriodPaid()).isFalse();
    assertThat(unexpanded.userId()).isNull();

    answer(
        "GET",
        "/v1/subscriptions/sub_B",
        200,
        subscriptionJson("sub_B", "trialing", null, end, "null"));
    // Without a latest invoice nothing proves a payment, also for a trial.
    assertThat(api.retrieveSubscription("sub_B").currentPeriodPaid()).isFalse();

    answer(
        "GET",
        "/v1/subscriptions/sub_C",
        200,
        subscriptionJson("sub_C", "active", null, end, "{\"status\":\"open\"}"));
    assertThat(api.retrieveSubscription("sub_C").currentPeriodPaid()).isFalse();

    answer(
        "GET",
        "/v1/subscriptions/sub_D",
        200,
        subscriptionJson("sub_D", "past_due", null, end, "{\"status\":\"paid\"}"));
    assertThat(api.retrieveSubscription("sub_D").currentPeriodPaid()).isFalse();

    answer(
        "GET",
        "/v1/subscriptions/sub_E",
        200,
        subscriptionJson("sub_E", "canceled", null, end, "{\"status\":\"paid\"}"));
    StripeSubscription canceled = api.retrieveSubscription("sub_E");
    assertThat(canceled.terminal()).isTrue();
    assertThat(canceled.currentPeriodPaid()).isFalse();

    answer(
        "GET",
        "/v1/subscriptions/sub_F",
        200,
        subscriptionJson("sub_F", "incomplete_expired", null, end, "{}"));
    StripeSubscription expired = api.retrieveSubscription("sub_F");
    assertThat(expired.terminal()).isTrue();
    assertThat(expired.latestInvoiceStatus()).isEqualTo("unknown");
  }

  @Test
  void anExpandedCustomerAndAnEndTimeAreRead() {
    answer(
        "GET",
        "/v1/subscriptions/sub_G",
        200,
        "{\"id\":\"sub_G\",\"customer\":{\"id\":\"cus_Synthetic2\"},\"status\":\"ACTIVE\","
            + "\"livemode\":true,\"ended_at\":1700000000,\"metadata\":null,\"items\":{}}");
    StripeSubscription subscription = api.retrieveSubscription("sub_G");
    assertThat(subscription.customer()).isEqualTo("cus_Synthetic2");
    assertThat(subscription.status()).isEqualTo("active");
    assertThat(subscription.livemode()).isTrue();
    assertThat(subscription.endedAt()).isEqualTo(Instant.ofEpochSecond(1_700_000_000L));
    assertThat(subscription.terminal()).isTrue();
    assertThat(subscription.currentPeriodEnd()).isNull();
    assertThat(subscription.priceIds()).isEmpty();
  }

  @Test
  void malformedSubscriptionsAreRejected() {
    for (String body :
        List.of(
            "[]",
            "{\"id\":\"sub_H\"}",
            "{\"id\":\"sub_H\",\"customer\":\"cus_1\",\"status\":\"active\"}",
            "{\"id\":\"x\",\"customer\":\"cus_1\",\"status\":\"active\",\"livemode\":false}",
            "{\"id\":\"sub_H\",\"customer\":\"evil\",\"status\":\"active\",\"livemode\":false}",
            "{\"id\":\"sub_H\",\"id\":\"sub_I\"}",
            "not json")) {
      server.resetAll();
      answer("GET", "/v1/subscriptions/sub_H", 200, body);
      StripeApiException error =
          catchThrowableOfType(StripeApiException.class, () -> api.retrieveSubscription("sub_H"));
      assertThat(error).as(body).isNotNull();
      assertThat(error.reason()).isEqualTo(Reason.MALFORMED);
    }
  }

  @Test
  void idsFromPayloadsAreCheckedBeforeTheyBecomePartOfAUrl() {
    for (String id : List.of("sub_../../v1/customers", "cus_1", "", "sub_a/b", "sub_a?x=1")) {
      assertThatThrownBy(() -> api.retrieveSubscription(id))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> api.cancelSubscription(id))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> api.createPortalSession("sub_1", "https://a.test/"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> api.retrieveSubscription(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(server.getAllServeEvents()).isEmpty();
  }

  @Test
  void aSubscriptionIsCanceledWithDelete() {
    answer(
        "DELETE",
        "/v1/subscriptions/sub_Synthetic1",
        200,
        "{\"id\":\"sub_Synthetic1\",\"status\":\"canceled\"}");
    api.cancelSubscription("sub_Synthetic1");
    assertThat(
            server.findAll(deleteRequestedFor(urlPathEqualTo("/v1/subscriptions/sub_Synthetic1"))))
        .hasSize(1);
  }

  @Test
  void transportFailuresAreClassified() {
    answer(
        "GET",
        "/v1/subscriptions/sub_Missing",
        404,
        "{\"error\":{\"type\":\"invalid_request_error\",\"code\":\"resource_missing\"}}");
    StripeApiException missing =
        catchThrowableOfType(
            StripeApiException.class, () -> api.retrieveSubscription("sub_Missing"));
    assertThat(missing.notFound()).isTrue();

    server.stubFor(
        get(urlPathEqualTo("/v1/subscriptions/sub_Reset"))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
    StripeApiException reset =
        catchThrowableOfType(StripeApiException.class, () -> api.retrieveSubscription("sub_Reset"));
    assertThat(reset.reason()).isEqualTo(Reason.CONNECTION);
    assertThat(reset.notFound()).isFalse();

    StripeApi slow = new StripeApi(properties(server.baseUrl(), Duration.ofMillis(200), 65_536));
    server.stubFor(
        get(urlPathEqualTo("/v1/subscriptions/sub_Slow"))
            .willReturn(aResponse().withFixedDelay(1500).withStatus(200).withBody("{}")));
    StripeApiException timeout =
        catchThrowableOfType(StripeApiException.class, () -> slow.retrieveSubscription("sub_Slow"));
    assertThat(timeout.reason()).isEqualTo(Reason.TIMEOUT);

    StripeApi small = new StripeApi(properties(server.baseUrl(), Duration.ofSeconds(2), 4096));
    answer("GET", "/v1/subscriptions/sub_Large", 200, "{\"pad\":\"" + "x".repeat(5000) + "\"}");
    StripeApiException large =
        catchThrowableOfType(
            StripeApiException.class, () -> small.retrieveSubscription("sub_Large"));
    assertThat(large.reason()).isEqualTo(Reason.TOO_LARGE);

    StripeApi closed =
        new StripeApi(properties("http://127.0.0.1:9", Duration.ofSeconds(2), 65_536));
    StripeApiException refused =
        catchThrowableOfType(StripeApiException.class, () -> closed.retrieveSubscription("sub_X"));
    assertThat(refused.reason()).isIn(Reason.CONNECTION, Reason.TIMEOUT);
  }

  @Test
  void anUnconfiguredAdapterCallsNothing() {
    StripeApi off =
        new StripeApi(
            new BillingProperties(
                "none",
                new BillingProperties.Stripe(
                    null,
                    null,
                    null,
                    false,
                    null,
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    4096),
                Duration.ofMinutes(5)));
    assertThatThrownBy(() -> off.cancelSubscription("sub_1"))
        .isInstanceOf(IllegalStateException.class);
  }
}
