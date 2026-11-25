package com.vitadelta.api.billing;

import com.vitadelta.api.billing.BillingApi.BillingRedirect;
import com.vitadelta.api.billing.BillingApi.WebhookAck;
import com.vitadelta.api.common.ratelimit.RateLimitPolicy;
import com.vitadelta.api.common.ratelimit.RateLimiter;
import com.vitadelta.api.common.web.ClientIpResolver;
import com.vitadelta.api.identity.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Payment-provider routes (ADR-0014): {@code POST /api/v1/billing/checkout-session} and {@code POST
 * /api/v1/billing/portal-session} for the signed-in user, and the public {@code POST
 * /api/v1/billing/webhooks/stripe}, which is authenticated by its signature only, takes the raw
 * body as bytes (so the signature is checked over exactly what Stripe sent) and is capped per
 * client address. The cap is checked once the body has been read (bounded by the 256 kB webhook
 * limit of {@code RequestBodyLimitFilter}) and before the signature check, the parsing and any
 * database or Stripe access.
 */
@RestController
public class ProviderBillingController {

  /** The Stripe webhook route. */
  public static final String STRIPE_WEBHOOK = "/api/v1/billing/webhooks/stripe";

  /** The signature header of Stripe webhooks. */
  static final String SIGNATURE_HEADER = "Stripe-Signature";

  private final ProviderCheckoutService checkout;
  private final StripeWebhookService webhooks;
  private final RateLimiter rateLimiter;
  private final ClientIpResolver clientIp;

  public ProviderBillingController(
      ProviderCheckoutService checkout,
      StripeWebhookService webhooks,
      RateLimiter rateLimiter,
      ClientIpResolver clientIp) {
    this.checkout = checkout;
    this.webhooks = webhooks;
    this.rateLimiter = rateLimiter;
    this.clientIp = clientIp;
  }

  @PostMapping("/api/v1/billing/checkout-session")
  BillingRedirect createCheckoutSession(Authentication authentication) {
    return checkout.checkout(CurrentUser.require(authentication));
  }

  @PostMapping("/api/v1/billing/portal-session")
  BillingRedirect createPortalSession(Authentication authentication) {
    return checkout.portal(CurrentUser.require(authentication));
  }

  @PostMapping(STRIPE_WEBHOOK)
  WebhookAck receiveStripeWebhook(
      @RequestBody(required = false) byte[] payload, HttpServletRequest request) {
    rateLimiter.check(RateLimitPolicy.STRIPE_WEBHOOK_PER_IP, clientIp.resolve(request));
    List<String> signatures = Collections.list(request.getHeaders(SIGNATURE_HEADER));
    webhooks.handle(payload == null ? new byte[0] : payload, signatures);
    return WebhookAck.RECEIVED;
  }
}
