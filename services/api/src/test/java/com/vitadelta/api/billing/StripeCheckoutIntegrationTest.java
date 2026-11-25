package com.vitadelta.api.billing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.vitadelta.api.entitlement.Contract;
import com.vitadelta.api.entitlement.PlanCode;
import com.vitadelta.api.entitlement.SubscriptionSource;
import com.vitadelta.api.identity.Role;
import com.vitadelta.api.support.TestEnvironment;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
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

  private void stripeExpires(int status) {
    STRIPE.stubFor(
        WireMock.post(urlPathMatching("/v1/checkout/sessions/cs_test_[A-Za-z0-9]+/expire"))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        status == 200
                            ? "{\"object\":\"checkout.session\",\"status\":\"expired\"}"
                            : "{\"error\":{\"type\":\"invalid_request_error\"}}")));
  }

  private int expired() {
    return STRIPE
        .findAll(
            postRequestedFor(urlPathMatching("/v1/checkout/sessions/cs_test_[A-Za-z0-9]+/expire")))
        .size();
  }

  @Test
  void aNewCheckoutExpiresThePreviousOpenSession() {
    TestUser user = user();
    stripeAnswers(
        "/v1/checkout/sessions", 200, "{\"id\":\"cs_test_Old1\",\"url\":\"" + SESSION_URL + "\"}");
    stripeExpires(200);
    assertStatus(post(CHECKOUT, "", user.token()), 200);
    assertThat(expired()).isZero();

    assertStatus(post(CHECKOUT, "", user.token()), 200);

    assertThat(
            STRIPE.findAll(
                postRequestedFor(urlPathEqualTo("/v1/checkout/sessions/cs_test_Old1/expire"))))
        .hasSize(1);
    assertThat(STRIPE.findAll(postRequestedFor(urlPathEqualTo("/v1/checkout/sessions"))))
        .hasSize(2);
  }

  @Test
  void aJustPaidPreviousSessionIsNotFollowedByASecondOne() {
    TestUser user = user();
    stripeAnswers(
        "/v1/checkout/sessions", 200, "{\"id\":\"cs_test_Paid1\",\"url\":\"" + SESSION_URL + "\"}");
    assertStatus(post(CHECKOUT, "", user.token()), 200);
    // Stripe refuses to expire a completed session; its webhook has not arrived yet.
    stripeExpires(400);
    assertProblem(post(CHECKOUT, "", user.token()), 409, "plan_already_active");
    assertThat(STRIPE.findAll(postRequestedFor(urlPathEqualTo("/v1/checkout/sessions"))))
        .hasSize(1);

    // A Stripe outage while expiring is 503 and creates nothing either.
    stripeExpires(500);
    assertProblem(post(CHECKOUT, "", user.token()), 503, "billing_unavailable");
    assertThat(STRIPE.findAll(postRequestedFor(urlPathEqualTo("/v1/checkout/sessions"))))
        .hasSize(1);

    // Once the completed checkout is applied, the session is forgotten and the plan is PRO.
    String subscription = stripeId("sub_");
    String customer = stripeId("cus_");
    subscription(
        subscription,
        customer,
        "active",
        user.id(),
        PRICE,
        clock.instant().plus(java.time.Duration.ofDays(30)),
        "paid");
    webhookOk(checkoutCompleted(stripeId("evt_"), user.id().toString(), subscription, customer));
    assertThat(
            jdbc.sql("SELECT session_ref FROM billing_checkout_session WHERE user_id = ?")
                .param(user.id())
                .query(String.class)
                .optional())
        .isEmpty();
    assertProblem(post(CHECKOUT, "", user.token()), 409, "plan_already_active");
  }

  private void webhookOk(String body) {
    assertStatus(webhook(body), 200);
  }

  private static ResponseDefinitionBuilder session(String id) {
    return aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withFixedDelay(300)
        .withBody(
            "{\"id\":\"" + id + "\",\"url\":\"https://checkout.stripe.com/c/pay/" + id + "\"}");
  }

  @Test
  void concurrentCheckoutsLeaveOnePayableSession() throws Exception {
    TestUser user = user();
    String scenario = "two-sessions-" + user.id();
    STRIPE.stubFor(
        WireMock.post(urlPathEqualTo("/v1/checkout/sessions"))
            .inScenario(scenario)
            .whenScenarioStateIs(Scenario.STARTED)
            .willSetStateTo("second")
            .willReturn(session("cs_test_First")));
    STRIPE.stubFor(
        WireMock.post(urlPathEqualTo("/v1/checkout/sessions"))
            .inScenario(scenario)
            .whenScenarioStateIs("second")
            .willReturn(session("cs_test_Second")));
    stripeExpires(200);
    List<EntityExchangeResult<String>> results =
        parallel(
            List.<Callable<EntityExchangeResult<String>>>of(
                () -> post(CHECKOUT, "", user.token(), randomIp(), null),
                () -> post(CHECKOUT, "", user.token(), randomIp(), null)));

    // A caller that arrives while the other one creates its session is asked to retry; one that
    // arrives later replaces the first session. Either way exactly one session stays payable.
    for (EntityExchangeResult<String> result : results) {
      if (result.getStatus().value() == 409) {
        assertProblem(result, 409, "conflict_retry");
      } else {
        assertStatus(result, 200);
      }
    }
    assertThat(results).anySatisfy(result -> assertStatus(result, 200));
    int created = STRIPE.findAll(postRequestedFor(urlPathEqualTo("/v1/checkout/sessions"))).size();
    assertThat(created - expired()).isEqualTo(1);
  }
}
