package com.vitadelta.api.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.entitlement.Contract;
import com.vitadelta.api.entitlement.EntitlementIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;

/**
 * Without {@code VITADELTA_BILLING_PROVIDER} (the default) no plan offers checkout and every
 * billing route answers {@code 503 billing_unavailable}; nothing is sent anywhere.
 */
class BillingProviderDisabledIntegrationTest extends EntitlementIntegrationTest {

  @Test
  void plansOfferNoCheckout() {
    EntityExchangeResult<String> result = get("/api/v1/plans", null);
    Contract.assertConforms("PlanList", result.getResponseBody());
    for (JsonNode plan : json(result).get("items")) {
      assertThat(plan.get("checkoutAvailable").asBoolean()).isFalse();
    }
  }

  @Test
  void billingRoutesAreUnavailable() {
    TestUser user = user();
    assertProblem(
        post("/api/v1/billing/checkout-session", "", user.token()), 503, "billing_unavailable");
    assertProblem(
        post("/api/v1/billing/portal-session", "", user.token()), 503, "billing_unavailable");
    EntityExchangeResult<String> webhook =
        client
            .post()
            .uri(ProviderBillingController.STRIPE_WEBHOOK)
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Forwarded-For", ip)
            .header("Stripe-Signature", "t=1,v1=00")
            .body("{\"id\":\"evt_1\"}")
            .exchange()
            .returnResult(String.class);
    assertProblem(webhook, 503, "billing_unavailable");
  }

  @Test
  void checkoutStillRequiresAuthentication() {
    assertProblem(post("/api/v1/billing/checkout-session", "", null), 401, "unauthorized");
    assertProblem(post("/api/v1/billing/portal-session", "", null), 401, "unauthorized");
  }
}
