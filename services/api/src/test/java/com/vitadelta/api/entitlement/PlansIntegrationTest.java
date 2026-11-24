package com.vitadelta.api.entitlement;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;

/** {@code GET /plans} and the cached {@link PlanService} (sections 8.3, 9.1). */
class PlansIntegrationTest extends EntitlementIntegrationTest {

  @Autowired PlanService plans;

  @Test
  void plansArePublicWithLimitsAndTheNativeProPrice() {
    EntityExchangeResult<String> result = get("/api/v1/plans", null);
    assertStatus(result, 200);
    Contract.assertConforms("PlanList", result.getResponseBody());
    JsonNode body = json(result);
    assertThat(body.get("meta").get("nativeCurrency").asString()).isEqualTo("TRY");
    assertThat(body.get("meta").get("currency").asString()).isEqualTo("TRY");
    JsonNode fx = body.get("meta").get("fx");
    assertThat(fx.get("rate").decimalValue()).isEqualByComparingTo("1");
    assertThat(fx.get("status").asString()).isEqualTo("OK");
    assertThat(fx.get("asOf").isNull()).isTrue();
    assertThat(fx.get("source").isNull()).isTrue();

    JsonNode free = body.get("items").get(0);
    assertThat(free.get("code").asString()).isEqualTo("FREE");
    assertThat(free.get("savedProductsLimit").asInt()).isEqualTo(5);
    assertThat(free.get("alertSubscriptionsLimit").asInt()).isEqualTo(3);
    assertThat(free.get("notificationsPerMonthLimit").asInt()).isEqualTo(30);
    assertThat(free.get("price").isNull()).isTrue();

    JsonNode pro = body.get("items").get(1);
    assertThat(pro.get("code").asString()).isEqualTo("PRO");
    assertThat(pro.get("savedProductsLimit").isNull()).isTrue();
    assertThat(pro.get("alertSubscriptionsLimit").isNull()).isTrue();
    assertThat(pro.get("notificationsPerMonthLimit").isNull()).isTrue();
    JsonNode price = pro.get("price");
    assertThat(price.get("amount").decimalValue()).isEqualByComparingTo("199.00");
    assertThat(price.get("amountNative").decimalValue()).isEqualByComparingTo("199.00");
    assertThat(price.get("currency").asString()).isEqualTo("TRY");
    assertThat(price.get("nativeCurrency").asString()).isEqualTo("TRY");
    assertThat(price.get("billingPeriod").asString()).isEqualTo("MONTHLY");
  }

  @Test
  void aSupportedForeignCurrencyStaysNativeUntilRatesExist() {
    country();
    EntityExchangeResult<String> result = get("/api/v1/plans?displayCurrency=" + CURRENCY, null);
    assertStatus(result, 200);
    Contract.assertConforms("PlanList", result.getResponseBody());
    JsonNode meta = json(result).get("meta");
    assertThat(meta.get("currency").asString()).isEqualTo("TRY");
    assertThat(meta.get("fx").get("base").asString()).isEqualTo("TRY");
    assertThat(meta.get("fx").get("quote").asString()).isEqualTo(CURRENCY);
    assertThat(meta.get("fx").get("rate").isNull()).isTrue();
    assertThat(meta.get("fx").get("status").asString()).isEqualTo("UNAVAILABLE");
    assertThat(json(result).get("items").get(1).get("price").get("currency").asString())
        .isEqualTo("TRY");

    TestUser user = user();
    jdbc.sql("UPDATE app_user SET display_currency = ? WHERE id = ?")
        .params(CURRENCY, user.id())
        .update();
    JsonNode preferred = json(get("/api/v1/plans", user.token()));
    assertThat(preferred.get("meta").get("fx").get("quote").asString()).isEqualTo(CURRENCY);
    JsonNode explicit = json(get("/api/v1/plans?displayCurrency=TRY", user.token()));
    assertThat(explicit.get("meta").get("fx").get("status").asString()).isEqualTo("OK");
  }

  @Test
  void unsupportedOrMalformedCurrenciesAreRejected() {
    assertProblem(get("/api/v1/plans?displayCurrency=QQQ", null), 422, "fx_unsupported_currency");
    assertProblem(get("/api/v1/plans?displayCurrency=usd", null), 422, "validation_failed");
  }

  @Test
  void planRowsAreCachedForSixtySeconds() {
    // Other tests move the clock by days; start from an entry written at the current time.
    plans.invalidate();
    assertThat(plans.get(PlanCode.FREE).savedProductsLimit()).isEqualTo(5);
    try {
      jdbc.sql("UPDATE plan SET saved_products_limit = 7 WHERE code = 'FREE'").update();
      clock.advance(Duration.ofSeconds(59));
      assertThat(plans.get(PlanCode.FREE).savedProductsLimit()).isEqualTo(5);
      clock.advance(Duration.ofSeconds(2));
      assertThat(plans.get(PlanCode.FREE).savedProductsLimit()).isEqualTo(7);
      assertThat(
              json(get("/api/v1/plans", null))
                  .get("items")
                  .get(0)
                  .get("savedProductsLimit")
                  .asInt())
          .isEqualTo(7);
    } finally {
      jdbc.sql("UPDATE plan SET saved_products_limit = 5 WHERE code = 'FREE'").update();
      plans.invalidate();
    }
    assertThat(plans.get(PlanCode.FREE).savedProductsLimit()).isEqualTo(5);
    assertThat(plans.all()).extracting(Plan::code).containsExactly(PlanCode.FREE, PlanCode.PRO);
  }
}
