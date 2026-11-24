package com.vitadelta.api.billing;

import com.vitadelta.api.entitlement.EntitlementIntegrationTest;
import com.vitadelta.api.entitlement.PlanCode;
import com.vitadelta.api.entitlement.SqlTime;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

/** Base of the billing integration tests: promo codes inserted directly and redemption calls. */
abstract class BillingIntegrationTest extends EntitlementIntegrationTest {

  static final String REDEEM = "/api/v1/me/subscription/promo";

  @Autowired PromoCodeGenerator generator;

  /** Inserts a PRO promo code (prefix {@link #PROMO_PREFIX}) and returns it. */
  String promo(int durationDays, int maxRedemptions, Instant expiresAt) {
    String code = generator.generate(PROMO_PREFIX);
    jdbc.sql(
            "INSERT INTO promo_code (code, plan_code, duration_days, max_redemptions,"
                + " redeemed_count, expires_at, created_by, created_at)"
                + " VALUES (?, ?, ?, ?, 0, ?, NULL, ?)")
        .params(
            code,
            PlanCode.PRO.name(),
            durationDays,
            maxRedemptions,
            SqlTime.ts(expiresAt),
            SqlTime.ts(clock.instant()))
        .update();
    return code;
  }

  int redeemedCount(String code) {
    return jdbc.sql("SELECT redeemed_count FROM promo_code WHERE code = CAST(? AS citext)")
        .param(code)
        .query(Integer.class)
        .single();
  }

  EntityExchangeResult<String> redeem(String code, String token) {
    return post(REDEEM, body(Map.of("code", code)), token);
  }

  EntityExchangeResult<String> redeem(
      String code, String token, String clientIp, String requestId) {
    return post(REDEEM, body(Map.of("code", code)), token, clientIp, requestId);
  }
}
