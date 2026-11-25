package com.vitadelta.api.fx;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.identity.ContractSchemas;
import com.vitadelta.api.identity.Role;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;

/**
 * How stored rates appear in responses (section 7.7): every rate as a plain JSON number with its 8
 * stored decimals, never in scientific notation, and a rate so small that a positive amount would
 * be shown as {@code 0.0000} treated as unusable for the whole response (native money, {@code
 * fx.status = UNAVAILABLE}, flag {@code FX_UNAVAILABLE}).
 */
class FxPresentationIntegrationTest extends FxApiTestBase {

  private static final BigDecimal TINY = new BigDecimal("0.00000001");

  private static final BigDecimal SMALL = new BigDecimal("0.00000300");

  @Test
  void aTinyRateIsWrittenWithItsEightDecimalsOnTheRatesEndpoint() {
    storeRate("TRY", "USD", today(), TINY);
    storeRate("TRY", "EUR", today(), SMALL);

    EntityExchangeResult<String> result = get("/api/v1/fx/rates?base=TRY", null);

    assertStatus(result, 200);
    ContractSchemas.assertConforms("FxRateList", result.getResponseBody());
    assertThat(result.getResponseBody())
        .contains("\"quote\":\"USD\",\"rate\":0.00000001,")
        .contains("\"quote\":\"EUR\",\"rate\":0.00000300,")
        .doesNotContainIgnoringCase("E-");
  }

  @Test
  void theFxMetaOfAConvertedResponseWritesATinyRatePlain() {
    storeRate("TRY", "USD", today(), SMALL);

    EntityExchangeResult<String> plans = get("/api/v1/plans?displayCurrency=USD", null);

    assertStatus(plans, 200);
    assertThat(plans.getResponseBody()).doesNotContainIgnoringCase("E-");
    JsonNode fx = exact(plans).get("meta").get("fx");
    assertThat(fx.get("status").asString()).isEqualTo("OK");
    assertThat(fx.get("rate").toString()).isEqualTo("0.00000300");
    JsonNode currencies = exact(get("/api/v1/fx/currencies", null));
    assertThat(currencies.toString()).doesNotContainIgnoringCase("E-");
  }

  @Test
  void aRateThatTurnsAPositivePriceIntoZeroLeavesTheWholeProductResponseNative() {
    storeRate("TRY", "USD", today(), TINY);
    UUID gold = productId("GOLD-A");
    String token = token(Role.USER);

    EntityExchangeResult<String> detail =
        get("/api/v1/products/" + gold + "?country=TR&displayCurrency=USD", token);

    assertStatus(detail, 200);
    assertThat(detail.getResponseBody()).contains("\"rate\":0.00000001,");
    ContractSchemas.assertConforms("ProductDetailResponse", detail.getResponseBody());
    JsonNode body = exact(detail);
    assertUnavailable(body.get("meta"));
    for (JsonNode offer : body.get("product").get("offers")) {
      assertThat(offer.get("currency").asString()).isEqualTo("TRY");
      assertThat(offer.get("price").toString()).isEqualTo(offer.get("priceNative").toString());
    }
    JsonNode cost = body.get("product").get("cost");
    assertThat(cost.get("currency").asString()).isEqualTo("TRY");
    assertThat(cost.get("effectivePrice").decimalValue().signum()).isPositive();
    assertThat(body.get("product").get("flags").toString()).contains("FX_UNAVAILABLE");

    EntityExchangeResult<String> list =
        get("/api/v1/products?country=TR&displayCurrency=USD", token);
    assertStatus(list, 200);
    ContractSchemas.assertConforms("ProductPage", list.getResponseBody());
    JsonNode listBody = exact(list);
    assertUnavailable(listBody.get("meta"));
    for (JsonNode item : listBody.get("items")) {
      if (!item.get("cost").isNull()) {
        assertThat(item.get("cost").get("currency").asString()).isEqualTo("TRY");
        assertThat(item.get("cost").get("effectivePrice").decimalValue().signum()).isPositive();
      }
    }
  }

  @Test
  void aRateThatTurnsAPlanPriceIntoZeroAnswersThePlansNatively() {
    storeRate("TRY", "USD", today(), TINY);

    EntityExchangeResult<String> result = get("/api/v1/plans?displayCurrency=USD", null);

    assertStatus(result, 200);
    ContractSchemas.assertConforms("PlanList", result.getResponseBody());
    JsonNode body = exact(result);
    assertUnavailable(body.get("meta"));
    for (JsonNode plan : body.get("items")) {
      if (!plan.get("price").isNull()) {
        assertThat(plan.get("price").get("currency").asString()).isEqualTo("TRY");
        assertThat(plan.get("price").get("amount").toString())
            .isEqualTo(plan.get("price").get("amountNative").toString());
      }
    }
  }

  @Test
  void aSmallButRepresentableRateStillConverts() {
    BigDecimal rate = new BigDecimal("0.00100000");
    storeRate("TRY", "USD", today(), rate);

    JsonNode body = exact(get("/api/v1/plans?displayCurrency=USD", null));

    assertThat(body.get("meta").get("currency").asString()).isEqualTo("USD");
    assertThat(body.get("meta").get("fx").get("status").asString()).isEqualTo("OK");
    for (JsonNode plan : body.get("items")) {
      if (!plan.get("price").isNull()) {
        assertThat(plan.get("price").get("amount").decimalValue())
            .isEqualTo(converted(plan.get("price").get("amountNative").decimalValue(), rate));
      }
    }
  }

  /** Native money, status UNAVAILABLE, and the unusable stored row still identified. */
  private static void assertUnavailable(JsonNode meta) {
    assertThat(meta.get("currency").asString()).isEqualTo("TRY");
    assertThat(meta.get("nativeCurrency").asString()).isEqualTo("TRY");
    JsonNode fx = meta.get("fx");
    assertThat(fx.get("status").asString()).isEqualTo("UNAVAILABLE");
    assertThat(fx.get("rate").decimalValue()).isEqualTo(TINY);
    assertThat(fx.get("asOf").asString()).isEqualTo(today().toString());
    assertThat(fx.get("stale").asBoolean()).isFalse();
    assertThat(fx.get("base").asString()).isEqualTo("TRY");
    assertThat(fx.get("quote").asString()).isEqualTo("USD");
  }
}
