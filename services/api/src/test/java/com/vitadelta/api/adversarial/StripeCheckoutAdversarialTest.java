package com.vitadelta.api.adversarial;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.vitadelta.api.adversarial.AttackClient.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.vitadelta.api.adversarial.AttackClient.Req;
import com.vitadelta.api.adversarial.AttackClient.Resp;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Section 15.7, hosted checkout and billing portal: the session routes need a bearer token of an
 * existing, verified user; everything sent to Stripe comes from the server (configured price, the
 * caller's own id and customer, return URLs from {@code VITADELTA_WEB_BASE_URL}), never from the
 * request body, query, {@code Host}-like or forwarded headers; only safe https redirect URLs come
 * back; the signature-only exemption of the webhook covers the webhook and nothing else.
 */
class StripeCheckoutAdversarialTest extends StripeAdversarialTest {

  static final String CHECKOUT_URL = "https://checkout.stripe.com/c/pay/cs_test_adversary";
  static final String PORTAL_URL = "https://billing.stripe.com/p/session/test_adversary";

  private void stripeAnswers(String path, int status, String body) {
    STRIPE.stubFor(
        WireMock.post(urlPathEqualTo(path))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));
  }

  private void stripeServesSessions() {
    stripeAnswers(
        "/v1/checkout/sessions",
        200,
        "{\"id\":\"cs_test_adversary\",\"object\":\"checkout.session\",\"url\":\""
            + CHECKOUT_URL
            + "\"}");
    stripeAnswers(
        "/v1/billing_portal/sessions",
        200,
        "{\"id\":\"bps_adversary\",\"object\":\"billing_portal.session\",\"url\":\""
            + PORTAL_URL
            + "\"}");
  }

  /** The form fields of a request sent to Stripe. */
  private static Map<String, List<String>> form(LoggedRequest request) {
    Map<String, List<String>> fields = new LinkedHashMap<>();
    for (String pair : request.getBodyAsString().split("&")) {
      int separator = pair.indexOf('=');
      String key = URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8);
      String value = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
      fields.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
    }
    return fields;
  }

  private static List<LoggedRequest> sent(String path) {
    return STRIPE.findAll(postRequestedFor(urlPathEqualTo(path)));
  }

  /** Every way a client might try to steer the hosted pages. */
  private static Req hostile(Req request) {
    return request
        .header("X-Forwarded-Host", "evil.example")
        .header("X-Forwarded-Proto", "http")
        .header("X-Forwarded-Prefix", "/phish")
        .header("X-Forwarded-Port", "8443")
        .header("Forwarded", "host=evil.example;proto=http")
        .header("X-Original-URL", "/api/v1/billing/webhooks/stripe")
        .header("X-Rewrite-URL", "/api/v1/billing/webhooks/stripe")
        .header("Referer", "https://evil.example/upgrade")
        .header("Stripe-Account", "acct_1Evil")
        .header("Stripe-Version", "2020-08-27")
        .header("Idempotency-Key", "attacker-chosen");
  }

  private static final String HOSTILE_BODY =
      "{\"price\":\"price_1Free\",\"priceId\":\"price_1Free\",\"quantity\":0,"
          + "\"mode\":\"payment\",\"successUrl\":\"https://evil.example/ok\","
          + "\"success_url\":\"https://evil.example/ok\",\"cancel_url\":\"javascript:alert(1)\","
          + "\"returnUrl\":\"https://evil.example/back\",\"return_url\":\"https://evil.example/back\","
          + "\"customer\":\"cus_1Victim\",\"customer_email\":\"victim@example.org\","
          + "\"userId\":\"00000000-0000-0000-0000-000000000001\",\"client_reference_id\":"
          + "\"00000000-0000-0000-0000-000000000001\",\"metadata\":{\"user_id\":\"x\"},"
          + "\"trial_period_days\":365,\"discounts\":[{\"coupon\":\"FREE100\"}],\"locale\":\"de\"}";

  private static final String HOSTILE_QUERY =
      "?price=price_1Free&success_url=https%3A%2F%2Fevil.example&customer=cus_1Victim"
          + "&client_reference_id=00000000-0000-0000-0000-000000000001";

  @Test
  void checkoutIgnoresEveryClientSuppliedValue() {
    stripeServesSessions();
    Customer user = freeUser();
    Resp response =
        hostile(req("POST", CHECKOUT + HOSTILE_QUERY).bearer(user.token()))
            .json(HOSTILE_BODY)
            .send();
    assertThat(response.status()).as(response.toString()).isEqualTo(200);
    assertThat(response.json().get("url").asString()).isEqualTo(CHECKOUT_URL);
    assertThat(response.json().size()).isEqualTo(1);

    List<LoggedRequest> sessions = sent("/v1/checkout/sessions");
    assertThat(sessions).hasSize(1);
    LoggedRequest request = sessions.get(0);
    assertThat(request.getUrl()).isEqualTo("/v1/checkout/sessions");
    assertThat(request.getHeader("Authorization")).isEqualTo("Bearer " + KEY);
    assertThat(request.containsHeader("Stripe-Account")).isFalse();
    assertThat(request.containsHeader("Idempotency-Key")).isFalse();
    assertThat(request.getHeader("Stripe-Version")).isNotEqualTo("2020-08-27");
    String id = user.id().toString();
    Map<String, List<String>> expected = new LinkedHashMap<>();
    expected.put("mode", List.of("subscription"));
    expected.put("line_items[0][price]", List.of(PRICE));
    expected.put("line_items[0][quantity]", List.of("1"));
    expected.put("success_url", List.of(WEB_BASE + "/upgrade?checkout=success"));
    expected.put("cancel_url", List.of(WEB_BASE + "/upgrade?checkout=canceled"));
    expected.put("client_reference_id", List.of(id));
    expected.put("metadata[user_id]", List.of(id));
    expected.put("subscription_data[metadata][user_id]", List.of(id));
    expected.put("locale", List.of("en"));
    Map<String, List<String>> actual = form(request);
    assertThat(actual.keySet()).containsExactlyInAnyOrderElementsOf(expected.keySet());
    expected.forEach((key, value) -> assertThat(actual.get(key)).as(key).isEqualTo(value));
    assertThat(request.getBodyAsString()).doesNotContain("evil").doesNotContain("Victim");

    // A browser origin that is not the web app is refused or ignored, never reflected.
    Resp foreignOrigin =
        req("POST", CHECKOUT)
            .bearer(user.token())
            .header("Origin", "https://evil.example")
            .json("{}")
            .send();
    assertThat(foreignOrigin.status()).isIn(200, 403);
    assertThat(foreignOrigin.header("access-control-allow-origin")).isNull();
    assertThat(sent("/v1/checkout/sessions"))
        .allSatisfy(r ->
            assertThat(form(r).get("success_url"))
                .containsExactly(WEB_BASE + "/upgrade?checkout=success"));
    // Nothing changes until a verified webhook arrives.
    assertThat(plan(user.id())).isEqualTo("FREE");
    assertThat(billingRows(user.id())).isZero();
  }

  @Test
  void checkoutReusesOnlyTheCallersOwnCustomer() {
    stripeServesSessions();
    Paid victim = paidUser();
    Customer user = freeUser();
    String own = stripeId("cus_");
    jdbc.sql(
            "INSERT INTO billing_customer (user_id, provider, customer_ref, created_at, updated_at)"
                + " VALUES (?, 'STRIPE', ?, now(), now())")
        .params(user.id(), own)
        .update();
    Resp response =
        req("POST", CHECKOUT)
            .bearer(user.token())
            .json("{\"customer\":\"" + victim.customer() + "\"}")
            .send();
    assertThat(response.status()).as(response.toString()).isEqualTo(200);
    assertThat(form(sent("/v1/checkout/sessions").get(0)).get("customer")).containsExactly(own);
    assertThat(sent("/v1/checkout/sessions").get(0).getBodyAsString())
        .doesNotContain(victim.customer());
  }

  @Test
  void checkoutAndPortalRefuseEveryCallerThatIsNotAVerifiedExistingUser() {
    stripeServesSessions();
    List<Resp> unauthorized = new ArrayList<>();
    for (String path : List.of(CHECKOUT, PORTAL)) {
      unauthorized.add(req("POST", path).json("{}").send());
      // A webhook signature is no credential on the session routes.
      unauthorized.add(req("POST", path).header("Stripe-Signature", sign("{}")).json("{}").send());
      unauthorized.add(req("POST", path).cookie("vd_refresh", randomSecret(32)).json("{}").send());
      unauthorized.add(req("POST", path).bearer("not-a-token").json("{}").send());
      // A correctly formed token of an account that does not exist (or was deleted).
      unauthorized.add(
          req("POST", path).bearer(accessToken(UUID.randomUUID(), "USER", true)).json("{}").send());
      // A token signed with another secret.
      unauthorized.add(
          req("POST", path)
              .bearer(
                  sign(
                      randomSecret(48).getBytes(StandardCharsets.UTF_8),
                      currentKeyId(),
                      com.nimbusds.jose.JWSAlgorithm.HS256,
                      claims(
                          freeUser().id().toString(),
                          "USER",
                          true,
                          clock.instant(),
                          java.time.Duration.ofMinutes(5))))
              .json("{}")
              .send());
    }
    for (Resp response : unauthorized) {
      assertProblem(response, 401, "unauthorized");
    }

    // A token that claims a verified address does not override the account's state.
    UUID unverified = insertUser(false);
    assertProblem(
        req("POST", CHECKOUT).bearer(accessToken(unverified, "USER", true)).json("{}").send(),
        403,
        "email_not_verified");

    // An active PRO plan, from the provider or from a promo code.
    Paid paid = paidUser();
    assertProblem(
        req("POST", CHECKOUT).bearer(paid.user().token()).json("{}").send(),
        409,
        "plan_already_active");
    Customer promo = freeUser();
    jdbc.sql(
            "UPDATE subscription SET plan_code = 'PRO', source = 'PROMO',"
                + " current_period_end = ? WHERE user_id = ? AND status = 'ACTIVE'")
        .params(java.sql.Timestamp.from(end(30)), promo.id())
        .update();
    assertProblem(
        req("POST", CHECKOUT).bearer(promo.token()).json("{}").send(), 409, "plan_already_active");

    assertThat(sent("/v1/checkout/sessions")).isEmpty();
    assertThat(sent("/v1/billing_portal/sessions")).isEmpty();
  }

  @Test
  void aSessionCookieAloneNeverAuthorizesABillingRoute() {
    stripeServesSessions();
    String email = uniqueEmail();
    registerVerified(email);
    Session session = login(email, PASSWORD);
    for (String path : List.of(CHECKOUT, PORTAL)) {
      Resp response =
          req("POST", path)
              .cookie("vd_refresh", session.refreshToken())
              .header("X-Requested-With", "vitadelta")
              .header("Origin", WEB_BASE)
              .json("{}")
              .send();
      assertProblem(response, 401, "unauthorized");
    }
    assertThat(stripeRequests()).isEmpty();
  }

  @Test
  void thePortalOpensOnlyForTheCallersCustomerWithAFixedReturnUrl() {
    stripeServesSessions();
    Paid owner = paidUser();
    Customer stranger = freeUser();
    assertProblem(
        hostile(req("POST", PORTAL + "?customer=" + owner.customer()).bearer(stranger.token()))
            .json("{\"customer\":\"" + owner.customer() + "\"}")
            .send(),
        409,
        "no_billing_account");
    assertThat(sent("/v1/billing_portal/sessions")).isEmpty();

    Resp response =
        hostile(
                req("POST", PORTAL + "?return_url=https%3A%2F%2Fevil.example")
                    .bearer(owner.user().token()))
            .json("{\"return_url\":\"https://evil.example\",\"customer\":\"cus_1Other\"}")
            .send();
    assertThat(response.status()).as(response.toString()).isEqualTo(200);
    assertThat(response.json().get("url").asString()).isEqualTo(PORTAL_URL);
    List<LoggedRequest> portals = sent("/v1/billing_portal/sessions");
    assertThat(portals).hasSize(1);
    Map<String, List<String>> fields = form(portals.get(0));
    assertThat(fields.keySet()).containsExactlyInAnyOrder("customer", "return_url");
    assertThat(fields.get("customer")).containsExactly(owner.customer());
    assertThat(fields.get("return_url")).containsExactly(WEB_BASE + "/account");
  }

  @Test
  void onlySafeHttpsRedirectsFromStripeReachTheBrowser() {
    List<String> unsafe =
        List.of(
            "javascript:alert(document.cookie)",
            "data:text/html,<script>alert(1)</script>",
            "http://checkout.stripe.com/c/pay/cs_test_1",
            "//evil.example/c/pay",
            "/relative/path",
            "https:evil.example",
            "https://user:password@evil.example/",
            "https://checkout.stripe.com/c/pay\r\nSet-Cookie: vd_refresh=stolen",
            "https://checkout.stripe.com/c/pay cs_test",
            "");
    for (String url : unsafe) {
      String json = "\"" + url.replace("\r", "\\r").replace("\n", "\\n") + "\"";
      STRIPE.resetAll();
      stripeAnswers("/v1/checkout/sessions", 200, "{\"id\":\"cs_test_1\",\"url\":" + json + "}");
      // A fresh caller per attempt: the per-user session limit is not what is tested here.
      Resp response = req("POST", CHECKOUT).bearer(freeUser().token()).json("{}").send();
      assertProblem(response, 503, "billing_unavailable");
      assertNoBillingLeak(response);
      assertThat(response.body()).doesNotContain("evil").doesNotContain("javascript");
      assertThat(response.setCookies()).isEmpty();
    }
    // Missing or non-string url fields.
    for (String body :
        List.of("{\"id\":\"cs_test_1\"}", "{\"url\":[\"https://a.example\"]}", "[]")) {
      STRIPE.resetAll();
      stripeAnswers("/v1/checkout/sessions", 200, body);
      assertProblem(
          req("POST", CHECKOUT).bearer(freeUser().token()).json("{}").send(),
          503,
          "billing_unavailable");
    }
  }

  @Test
  void stripeErrorsNeverReachTheCaller() {
    Customer user = freeUser();
    String customer = stripeId("cus_");
    jdbc.sql(
            "INSERT INTO billing_customer (user_id, provider, customer_ref, created_at, updated_at)"
                + " VALUES (?, 'STRIPE', ?, now(), now())")
        .params(user.id(), customer)
        .update();
    String leaky =
        "{\"error\":{\"type\":\"invalid_request_error\",\"code\":\"resource_missing\","
            + "\"message\":\"No such customer: '"
            + customer
            + "'; key "
            + KEY
            + "\",\"param\":\"customer\",\"request_log_url\":"
            + "\"https://dashboard.stripe.com/test/logs/req_1\"}}";
    for (int status : List.of(400, 401, 402, 403, 404, 429, 500)) {
      for (String path : List.of("/v1/checkout/sessions", "/v1/billing_portal/sessions")) {
        STRIPE.resetAll();
        stripeAnswers(path, status, leaky);
        String route = path.contains("portal") ? PORTAL : CHECKOUT;
        Resp response = req("POST", route).bearer(user.token()).json("{}").send();
        assertProblem(response, 503, "billing_unavailable");
        assertNoBillingLeak(response);
        assertThat(response.body()).doesNotContain("dashboard").doesNotContain("No such");
      }
    }
  }

  @Test
  void theSignatureOnlyExemptionCoversTheWebhookAndNothingElse() {
    stripeServesSessions();
    Customer user = freeUser();
    List<String> bypasses =
        List.of(
            API + "/billing/webhooks/../checkout-session",
            API + "/billing/webhooks/stripe/../../checkout-session",
            API + "/billing/webhooks/stripe/../../portal-session",
            API + "/billing/webhooks/%2e%2e/checkout-session",
            API + "/billing/webhooks/stripe%2F..%2F..%2Fcheckout-session",
            API + "/billing/webhooks/stripe;/../../checkout-session",
            API + "/billing/webhooks//../checkout-session",
            API + "/billing/webhooks/stripe/%2e%2e/%2e%2e/portal-session",
            API + "/billing/webhooks/..;/checkout-session",
            API + "/billing/webhooks/stripe/..%5c..%5ccheckout-session",
            API + "/billing/webhooks/../../me",
            API + "/billing/webhooks/../../admin/promo-codes",
            API + "/billing/webhooks/stripe/../../../me/subscription/promo");
    for (String path : bypasses) {
      Resp response = req("POST", path).header("Stripe-Signature", sign("{}")).json("{}").send();
      assertThat(response.status()).as(path + " " + response).isIn(400, 401, 403, 404, 405);
      assertNoBillingLeak(response);
    }
    // Neighbouring routes keep their own rules.
    assertProblem(
        req("POST", API + "/me/subscription/promo").json("{\"code\":\"X\"}").send(),
        401,
        "unauthorized");
    assertProblem(req("GET", API + "/me").send(), 401, "unauthorized");
    assertProblem(
        req("POST", API + "/admin/users/" + user.id() + "/subscription")
            .bearer(user.token())
            .json("{\"planCode\":\"PRO\",\"durationDays\":30}")
            .send(),
        403,
        "forbidden");
    // Lookups of someone else's session ids do not exist on any route.
    for (String method : List.of("GET", "POST", "PUT", "DELETE")) {
      Resp anonymous = req(method, CHECKOUT + "/cs_test_victim").send();
      assertProblem(anonymous, 401, "unauthorized");
      Resp signedIn = req(method, CHECKOUT + "/cs_test_victim").bearer(user.token()).send();
      assertThat(signedIn.status()).as(method + " " + signedIn).isIn(404, 405);
      assertNoBillingLeak(signedIn);
      Resp query = req(method, CHECKOUT + "?session_id=cs_test_victim").bearer(user.token()).send();
      assertThat(query.status()).as(method + " " + query).isIn(200, 405, 415);
      if (query.status() == 200) {
        // The query is ignored: a new session of the caller, never the named one.
        assertThat(query.json().get("url").asString()).isEqualTo(CHECKOUT_URL);
      } else {
        assertNoBillingLeak(query);
      }
    }
    assertThat(sent("/v1/checkout/sessions"))
        .allSatisfy(request -> {
          assertThat(request.getBodyAsString()).doesNotContain("cs_test_victim");
          assertThat(form(request).get("client_reference_id"))
              .containsExactly(user.id().toString());
        });
    // Other methods and look-alike paths of the webhook are not handlers.
    for (String method : List.of("GET", "PUT", "PATCH", "DELETE")) {
      Resp response = req(method, WEBHOOK).send();
      assertThat(response.status()).as(method + " " + response).isIn(401, 403, 404, 405);
      assertNoBillingLeak(response);
    }
    for (String path :
        List.of(
            WEBHOOK + "/",
            WEBHOOK + "x",
            API + "/billing/webhooks/paypal",
            API + "/billing/webhooks")) {
      Resp response = req("POST", path).header("Stripe-Signature", sign("{}")).json("{}").send();
      assertThat(response.status()).as(path + " " + response).isIn(400, 404, 405);
      assertNoBillingLeak(response);
    }
    assertThat(stripeRequests())
        .as("no bypass reached Stripe")
        .allMatch(request -> request.getUrl().equals("/v1/checkout/sessions"));
    assertThat(plan(user.id())).isEqualTo("FREE");
  }

  @Test
  void theSessionRateLimitsFollowTheUserNotTheAddress() {
    stripeServesSessions();
    Customer user = freeUser();
    Resp limited = null;
    for (int i = 0; i < 11 && limited == null; i++) {
      Resp response = req("POST", CHECKOUT).ip(randomIp()).bearer(user.token()).json("{}").send();
      if (response.status() == 429) {
        limited = response;
      } else {
        assertThat(response.status()).isEqualTo(200);
      }
    }
    assertThat(limited).as("the eleventh checkout within the hour is refused").isNotNull();
    assertProblem(limited, 429, "rate_limited");
    assertThat(sent("/v1/checkout/sessions")).hasSize(10);
  }
}
