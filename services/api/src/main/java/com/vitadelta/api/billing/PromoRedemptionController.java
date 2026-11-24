package com.vitadelta.api.billing;

import com.vitadelta.api.billing.BillingApi.PromoRedeemRequest;
import com.vitadelta.api.billing.BillingApi.SubscriptionActivation;
import com.vitadelta.api.common.ratelimit.RateLimitPolicy;
import com.vitadelta.api.common.ratelimit.RateLimiter;
import com.vitadelta.api.common.web.ClientIpResolver;
import com.vitadelta.api.identity.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/me/subscription/promo} (section 8.8): activates or extends PRO with a promo
 * code. Rate limited per client IP (10 per hour) and per user (5 per hour) before any lookup.
 */
@RestController
public class PromoRedemptionController {

  private final PromoCodeBillingProvider provider;
  private final RateLimiter rateLimiter;
  private final ClientIpResolver clientIp;

  public PromoRedemptionController(
      PromoCodeBillingProvider provider, RateLimiter rateLimiter, ClientIpResolver clientIp) {
    this.provider = provider;
    this.rateLimiter = rateLimiter;
    this.clientIp = clientIp;
  }

  @PostMapping("/api/v1/me/subscription/promo")
  SubscriptionActivation redeem(
      @Valid @RequestBody PromoRedeemRequest body,
      Authentication authentication,
      HttpServletRequest request) {
    UUID userId = CurrentUser.require(authentication);
    rateLimiter.check(RateLimitPolicy.PROMO_PER_IP, clientIp.resolve(request));
    rateLimiter.check(RateLimitPolicy.PROMO_PER_USER, userId.toString());
    ActivationResult result =
        provider.activate(new ActivationRequest.PromoCode(userId, body.code()));
    return new SubscriptionActivation(result.plan().name(), result.subscription().view());
  }
}
