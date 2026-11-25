package com.vitadelta.api.billing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.vitadelta.api.entitlement.Contract;
import com.vitadelta.api.entitlement.PlanCode;
import com.vitadelta.api.entitlement.SubscriptionSource;
import com.vitadelta.api.identity.Role;
import com.vitadelta.api.support.TestEnvironment;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;

/**
 * {@code POST /billing/checkout-session}, {@code POST /billing/portal-session} and {@code
 * Plan.checkoutAvailable} with the Stripe provider configured (ADR-0014).
 */
class StripeCheckoutIntegrationTest extends StripeIntegrationTest {

  private static final String SESSION_URL = "https://checkout.stripe.com/c/pay/cs_test_Synthetic";
  private static final String PORTAL_URL = "https://billing.stripe.com/p/session/test_Synthetic";

  private void stripeAnswers(String path, int status, String body) {
    STRIPE.stubFor(
        WireMock.post(urlPathEqualTo(path))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));
  }

  private LoggedRequest lastRequest(String path) {
    return STRIPE.findAll(postRequestedFor(urlPathEqualTo(path))).getLast();
  }

  @Test
  void aVerifiedFreeUserGetsAHostedCheckoutBoundToTheAccount() {
    TestUser user = user();
    stripeAnswers(
        "/v1/checkout/sessions", 200, "{\"id\":\"cs_test_1\",\"url\":\"" + SESSION_URL + "\"}");

    EntityExchangeResult<String> result = post(CHECKOUT, "", user.token());

    assertStatus(result, 200);
    Contract.assertConforms("BillingRedirect", result.getResponseBody());
    assertThat(json(result).get("url").asString()).isEqualTo(SESSION_URL);
    Map<String, String> form = StripeApiTest.form(lastRequest("/v1/checkout/sessions"));
    assertThat(form)
        .containsEntry("client_reference_id", user.id().toString())
        .containsEntry("metadata[user_id]", user.id().toString())
        .containsEntry("subscription_data[metadata][user_id]", user.id().toString())
        .containsEntry("line_items[0][price]", PRICE)
        .containsEntry("success_url", TestEnvironment.WEB_BASE_URL + "/upgrade?checkout=success")
        .containsEntry("cancel_url", TestEnvironment.WEB_BASE_URL + "/upgrade?checkout=canceled")
        .containsEntry("locale", "en")
        .doesNotContainKey("customer");
    assertThat(form.values()).noneMatch(value -> value.contains(user.email()));
    // Starting a checkout changes no subscription.
    assertThat(active(user.id()).get("plan_code")).isEqualTo("FREE");
  }

  @Test
  void aReturningCustomerIsReused() {
    TestUser user = user();
    String customer = stripeId("cus_");
    customer(user.id(), customer);
    stripeAnswers(
        "/v1/checkout/sessions", 200, "{\"id\":\"cs_test_1\",\"url\":\"" + SESSION_URL + "\"}");

    assertStatus(post(CHECKOUT, "", user.token()), 200);

    assertThat(StripeApiTest.form(lastRequest("/v1/checkout/sessions")))
        .containsEntry("customer", customer);
  }

  @Test
  void checkoutIsRefusedForUnverifiedProAndAnonymousCallers() {
    assertProblem(post(CHECKOUT, "", null), 401, "unauthorized");
    TestUser unverified = user(false, Role.USER);
    assertProblem(post(CHECKOUT, "", unverified.token()), 403, "email_not_verified");
    TestUser pro = user();
    transactions.executeWithoutResult(status ->
        subscriptionService.activate(pro.id(), PlanCode.PRO, 30, SubscriptionSource.MANUAL));
    EntityExchangeResult<String> conflict = post(CHECKOUT, "", pro.token());
    assertProblem(conflict, 409, "plan_already_active");
    assertThat(STRIPE.findAll(postRequestedFor(urlPathEqualTo("/v1/checkout/sessions")))).isEmpty();
  }

  @Test
  void aStripeFailureIsBillingUnavailableWithoutDetails() {
    TestUser user = user();
    stripeAnswers(
        "/v1/checkout/sessions",
        400,
        "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"No such price\"}}");
    EntityExchangeResult<String> result = post(CHECKOUT, "", user.token());
    assertProblem(result, 503, "billing_unavailable");
    assertThat(result.getResponseBody()).doesNotContain("price").doesNotContain("invalid_request");
  }

  @Test
  void checkoutIsRateLimitedPerUser() {
    TestUser user = user();
    stripeAnswers(
        "/v1/checkout/sessions", 200, "{\"id\":\"cs_test_1\",\"url\":\"" + SESSION_URL + "\"}");
    for (int i = 0; i < 10; i++) {
      assertStatus(post(CHECKOUT, "", user.token(), randomIp(), null), 200);
    }
    assertProblem(post(CHECKOUT, "", user.token(), randomIp(), null), 429, "rate_limited");
  }

  @Test
  void thePortalOpensForTheUsersCustomerOnly() {
    TestUser newcomer = user();
    assertProblem(post(PORTAL, "", newcomer.token()), 409, "no_billing_account");
    assertProblem(post(PORTAL, "", null), 401, "unauthorized");

    TestUser subscriber = user();
    String customer = stripeId("cus_");
    customer(subscriber.id(), customer);
    stripeAnswers("/v1/billing_portal/sessions", 200, "{\"url\":\"" + PORTAL_URL + "\"}");
    EntityExchangeResult<String> result = post(PORTAL, "", subscriber.token());
    assertStatus(result, 200);
    Contract.assertConforms("BillingRedirect", result.getResponseBody());
    assertThat(json(result).get("url").asString()).isEqualTo(PORTAL_URL);
    assertThat(StripeApiTest.form(lastRequest("/v1/billing_portal/sessions")))
        .containsEntry("customer", customer)
        .containsEntry("return_url", TestEnvironment.WEB_BASE_URL + "/account");

    stripeAnswers("/v1/billing_portal/sessions", 200, "{\"url\":\"http://insecure.test/\"}");
    assertProblem(post(PORTAL, "", subscriber.token()), 503, "billing_unavailable");
  }

  @Test
  void thePortalIsRateLimitedPerUser() {
    TestUser subscriber = user();
    customer(subscriber.id(), stripeId("cus_"));
    stripeAnswers("/v1/billing_portal/sessions", 200, "{\"url\":\"" + PORTAL_URL + "\"}");
    for (int i = 0; i < 10; i++) {
      assertStatus(post(PORTAL, "", subscriber.token(), randomIp(), null), 200);
    }
    assertProblem(post(PORTAL, "", subscriber.token(), randomIp(), null), 429, "rate_limited");
  }

  @Test
  void plansReportCheckoutForProOnly() {
    EntityExchangeResult<String> result = get("/api/v1/plans", null);
    assertStatus(result, 200);
    Contract.assertConforms("PlanList", result.getResponseBody());
    for (JsonNode plan : json(result).get("items")) {
      assertThat(plan.get("checkoutAvailable").asBoolean())
          .as(plan.get("code").asString())
          .isEqualTo(plan.get("code").asString().equals("PRO"));
    }
  }

  @Test
  void checkoutIsRefusedWhileALiveProviderSubscriptionExists() {
    TestUser user = user();
    // Downgraded to FREE by the expiry job while the Stripe subscription is past due.
    jdbc.sql(
            "INSERT INTO billing_subscription (provider, external_ref, user_id, customer_ref,"
                + " status, current_period_end, ended_at, updated_at)"
                + " VALUES ('STRIPE', ?, ?, ?, 'past_due', now(), NULL, now())")
        .params(stripeId("sub_"), user.id(), stripeId("cus_"))
        .update();
    stripeAnswers(
        "/v1/checkout/sessions", 200, "{\"id\":\"cs_test_1\",\"url\":\"" + SESSION_URL + "\"}");

    assertProblem(post(CHECKOUT, "", user.token()), 409, "plan_already_active");
    assertThat(STRIPE.findAll(postRequestedFor(urlPathEqualTo("/v1/checkout/sessions")))).isEmpty();
  }
}
