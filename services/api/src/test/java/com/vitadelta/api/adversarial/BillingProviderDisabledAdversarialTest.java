package com.vitadelta.api.adversarial;

import static com.vitadelta.api.adversarial.AttackClient.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.adversarial.AttackClient.Resp;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Section 15.7, payment provider switched off (the default, {@code VITADELTA_BILLING_PROVIDER}
 * unset): the public webhook route grants nothing whatever it is sent (an empty secret or a guessed
 * one cannot sign an accepted event), the session routes still demand authentication first, and the
 * plans advertise no checkout.
 */
class BillingProviderDisabledAdversarialTest extends AdversarialIntegrationTest {

  private static final String WEBHOOK = API + "/billing/webhooks/stripe";

  private static String checkoutEvent(UUID userId) {
    return "{\"id\":\"evt_1Disabled"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
        + "\",\"object\":\"event\",\"livemode\":false,\"type\":\"checkout.session.completed\","
        + "\"data\":{\"object\":{\"object\":\"checkout.session\",\"mode\":\"subscription\","
        + "\"client_reference_id\":\""
        + userId
        + "\",\"customer\":\"cus_1Disabled\",\"subscription\":\"sub_1Disabled\"}}}";
  }

  @Test
  void theWebhookGrantsNothingWithoutAConfiguredProvider() {
    UUID user = insertUser(true);
    byte[] body = checkoutEvent(user).getBytes(StandardCharsets.UTF_8);
    long t = clock.instant().getEpochSecond();
    for (String secret : List.of("whsec_", "whsec_" + randomSecret(24), "none")) {
      Resp response =
          req("POST", WEBHOOK)
              .header("Stripe-Signature", StripeAdversarialTest.signatureHeader(secret, t, body))
              .body(body, "application/json")
              .send();
      assertProblem(response, 503, "billing_unavailable");
      assertThat(response.body())
          .doesNotContain("whsec_")
          .doesNotContain("sub_")
          .doesNotContain("cus_");
    }
    assertProblem(
        req("POST", WEBHOOK).body(body, "application/json").send(), 503, "billing_unavailable");
    // The body limit still applies before anything else.
    byte[] huge = ("{\"pad\":\"" + "x".repeat(300_000) + "\"}").getBytes(StandardCharsets.UTF_8);
    assertProblem(
        req("POST", WEBHOOK).body(huge, "application/json").send(), 413, "validation_failed");

    assertThat(
            count(
                "SELECT count(*) FROM subscription WHERE user_id = ? AND status = 'ACTIVE'"
                    + " AND plan_code = 'FREE'",
                user))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM billing_customer WHERE user_id = ?", user)).isZero();
    assertThat(count("SELECT count(*) FROM billing_subscription WHERE user_id = ?", user)).isZero();
  }

  @Test
  void sessionRoutesAuthenticateFirstAndThenReportTheProviderUnavailable() {
    for (String path :
        List.of(API + "/billing/checkout-session", API + "/billing/portal-session")) {
      assertProblem(req("POST", path).json("{}").send(), 401, "unauthorized");
      UUID user = insertUser(true);
      Resp response = req("POST", path).bearer(accessToken(user, "USER", true)).json("{}").send();
      assertProblem(response, 503, "billing_unavailable");
    }
    Resp plans = get(API + "/plans", null);
    assertThat(plans.status()).isEqualTo(200);
    assertThat(plans.json().get("items").size()).isPositive();
    plans
        .json()
        .get("items")
        .forEach(plan ->
            assertThat(plan.get("checkoutAvailable").asBoolean()).as(plan.toString()).isFalse());
  }
}
