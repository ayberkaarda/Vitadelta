package com.vitadelta.api.billing;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Payment-provider settings (Phase 8, ADR-0014). The provider is off unless {@code
 * VITADELTA_BILLING_PROVIDER=stripe}; then the Stripe secret key, webhook signing secret and price
 * id are required and checked at startup (the application refuses to start otherwise). Only test
 * keys are accepted unless {@code VITADELTA_STRIPE_ALLOW_LIVE=true}. {@link #toString()} never
 * prints a secret.
 *
 * @param provider {@code none} (default; also when blank) or {@code stripe}
 * @param stripe Stripe settings, only checked when the provider is {@code stripe}
 * @param webhookTolerance largest accepted distance between a webhook's signed timestamp and the
 *     server clock (default 5 minutes, the tolerance of Stripe's own libraries)
 */
@ConfigurationProperties(prefix = "vitadelta.billing")
public record BillingProperties(
    String provider,
    @DefaultValue BillingProperties.Stripe stripe,
    @DefaultValue("5m") Duration webhookTolerance) {

  /** The configured payment provider. */
  public enum Provider {
    NONE,
    STRIPE
  }

  /** Stripe's API host; the only host the adapter calls in production. */
  public static final String DEFAULT_STRIPE_API_BASE_URL = "https://api.stripe.com";

  private static final Pattern PRICE_ID = Pattern.compile("^price_[A-Za-z0-9]{1,200}$");
  private static final Pattern WEBHOOK_SECRET = Pattern.compile("^whsec_[A-Za-z0-9+/=_-]{16,200}$");
  private static final Pattern SECRET_KEY =
      Pattern.compile("^(sk|rk)_(test|live)_[A-Za-z0-9_]{16,250}$");

  /**
   * Stripe settings.
   *
   * @param secretKey API secret key ({@code VITADELTA_STRIPE_SECRET_KEY}): {@code sk_test_...} or a
   *     restricted {@code rk_test_...} key; live keys only with {@code allowLive}
   * @param webhookSecret signing secret of the webhook endpoint ({@code
   *     VITADELTA_STRIPE_WEBHOOK_SECRET}, {@code whsec_...})
   * @param priceId the recurring monthly price of PRO ({@code VITADELTA_STRIPE_PRICE_ID}, {@code
   *     price_...})
   * @param allowLive whether live keys are accepted ({@code VITADELTA_STRIPE_ALLOW_LIVE}, default
   *     false)
   * @param apiBaseUrl Stripe API base URL (default {@value #DEFAULT_STRIPE_API_BASE_URL}); https,
   *     or http to a loopback address (local stub servers only)
   * @param connectTimeout TCP connect timeout of an API call (default 5 s)
   * @param readTimeout time allowed for an API response (default 20 s)
   * @param maxResponseBytes largest accepted API response body (default 1 MiB)
   */
  public record Stripe(
      String secretKey,
      String webhookSecret,
      String priceId,
      @DefaultValue("false") boolean allowLive,
      @DefaultValue(DEFAULT_STRIPE_API_BASE_URL) String apiBaseUrl,
      @DefaultValue("5s") Duration connectTimeout,
      @DefaultValue("20s") Duration readTimeout,
      @DefaultValue("1048576") int maxResponseBytes) {

    public Stripe {
      secretKey = blankToNull(secretKey);
      webhookSecret = blankToNull(webhookSecret);
      priceId = blankToNull(priceId);
      apiBaseUrl =
          apiBaseUrl == null || apiBaseUrl.isBlank()
              ? DEFAULT_STRIPE_API_BASE_URL
              : stripTrailingSlash(apiBaseUrl.strip());
    }

    /** Whether the secret key is a live-mode key. */
    public boolean liveMode() {
      return secretKey != null && secretKey.substring(3).startsWith("live_");
    }

    @Override
    public String toString() {
      return "Stripe[secretKey="
          + (secretKey == null ? "<unset>" : "<hidden>")
          + ", webhookSecret="
          + (webhookSecret == null ? "<unset>" : "<hidden>")
          + ", priceId="
          + priceId
          + ", allowLive="
          + allowLive
          + ", apiBaseUrl="
          + apiBaseUrl
          + "]";
    }
  }

  public BillingProperties {
    Provider selected = parse(provider);
    provider = selected.name().toLowerCase(Locale.ROOT);
    if (webhookTolerance == null || webhookTolerance.isNegative() || webhookTolerance.isZero()) {
      throw new IllegalArgumentException("vitadelta.billing.webhook-tolerance must be positive");
    }
    if (selected == Provider.STRIPE) {
      validateStripe(stripe);
    }
  }

  /** The configured provider. */
  public Provider selected() {
    return Provider.valueOf(provider.toUpperCase(Locale.ROOT));
  }

  /** Whether a payment provider is configured. */
  public boolean enabled() {
    return selected() != Provider.NONE;
  }

  private static Provider parse(String value) {
    if (value == null || value.isBlank()) {
      return Provider.NONE;
    }
    return switch (value.strip().toLowerCase(Locale.ROOT)) {
      case "none" -> Provider.NONE;
      case "stripe" -> Provider.STRIPE;
      default ->
          throw new IllegalArgumentException(
              "VITADELTA_BILLING_PROVIDER must be none or stripe: " + value.strip());
    };
  }

  private static void validateStripe(Stripe stripe) {
    if (stripe.secretKey() == null) {
      throw new IllegalArgumentException(
          "VITADELTA_STRIPE_SECRET_KEY is not set; it is required when"
              + " VITADELTA_BILLING_PROVIDER=stripe");
    }
    if (!SECRET_KEY.matcher(stripe.secretKey()).matches()) {
      throw new IllegalArgumentException(
          "VITADELTA_STRIPE_SECRET_KEY is not a Stripe secret key (sk_test_... or rk_test_...)");
    }
    if (stripe.liveMode() && !stripe.allowLive()) {
      throw new IllegalArgumentException(
          "VITADELTA_STRIPE_SECRET_KEY is a live-mode key; only test keys are accepted unless"
              + " VITADELTA_STRIPE_ALLOW_LIVE=true");
    }
    if (stripe.webhookSecret() == null) {
      throw new IllegalArgumentException(
          "VITADELTA_STRIPE_WEBHOOK_SECRET is not set; it is required when"
              + " VITADELTA_BILLING_PROVIDER=stripe");
    }
    if (!WEBHOOK_SECRET.matcher(stripe.webhookSecret()).matches()) {
      throw new IllegalArgumentException(
          "VITADELTA_STRIPE_WEBHOOK_SECRET is not a webhook signing secret (whsec_...)");
    }
    if (stripe.priceId() == null) {
      throw new IllegalArgumentException(
          "VITADELTA_STRIPE_PRICE_ID is not set; it is required when"
              + " VITADELTA_BILLING_PROVIDER=stripe");
    }
    if (!PRICE_ID.matcher(stripe.priceId()).matches()) {
      throw new IllegalArgumentException(
          "VITADELTA_STRIPE_PRICE_ID is not a Stripe price id (price_...): " + stripe.priceId());
    }
    validateApiBaseUrl(stripe.apiBaseUrl());
    requirePositive(stripe.connectTimeout(), "vitadelta.billing.stripe.connect-timeout");
    requirePositive(stripe.readTimeout(), "vitadelta.billing.stripe.read-timeout");
    if (stripe.maxResponseBytes() < 4096) {
      throw new IllegalArgumentException(
          "vitadelta.billing.stripe.max-response-bytes must be >= 4096");
    }
  }

  private static void validateApiBaseUrl(String url) {
    URI uri;
    try {
      uri = new URI(url);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("vitadelta.billing.stripe.api-base-url is not a URL", e);
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    boolean shape =
        uri.isAbsolute()
            && uri.getHost() != null
            && uri.getRawQuery() == null
            && uri.getRawFragment() == null
            && uri.getRawUserInfo() == null;
    boolean https = scheme.equals("https");
    boolean loopbackHttp = scheme.equals("http") && isLoopback(uri.getHost());
    if (!shape || !(https || loopbackHttp)) {
      throw new IllegalArgumentException(
          "vitadelta.billing.stripe.api-base-url must be an https URL (http only to a loopback"
              + " address) without credentials, query or fragment: "
              + url);
    }
  }

  private static boolean isLoopback(String host) {
    if (host.equalsIgnoreCase("localhost")) {
      return true;
    }
    String literal =
        host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    if (!literal.matches("^[0-9.]+$") && !literal.contains(":")) {
      return false;
    }
    try {
      return InetAddress.getByName(literal).isLoopbackAddress();
    } catch (UnknownHostException e) {
      return false;
    }
  }

  private static void requirePositive(Duration value, String name) {
    if (value == null || value.isNegative() || value.isZero()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static String stripTrailingSlash(String value) {
    String result = value;
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }
}
