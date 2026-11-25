package com.vitadelta.api.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitadelta.api.support.TestEnvironment;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Payment-provider configuration: off by default, fail fast on an incomplete Stripe configuration,
 * test keys only unless live keys are allowed explicitly, secrets never printed. Key values are
 * generated per run; none is stored in the repository.
 */
class BillingPropertiesTest {

  static final String TEST_KEY = "sk_" + "test_" + TestEnvironment.randomHex(24);
  static final String LIVE_KEY = "sk_" + "live_" + TestEnvironment.randomHex(24);
  static final String WEBHOOK_SECRET = "whsec_" + TestEnvironment.randomHex(24);
  static final String PRICE = "price_1SyntheticPro";

  static BillingProperties.Stripe stripe(
      String key, String webhookSecret, String price, boolean allowLive, String baseUrl) {
    return new BillingProperties.Stripe(
        key,
        webhookSecret,
        price,
        allowLive,
        baseUrl,
        Duration.ofSeconds(5),
        Duration.ofSeconds(20),
        1_048_576);
  }

  static BillingProperties stripeProperties(String key, boolean allowLive) {
    return new BillingProperties(
        "stripe", stripe(key, WEBHOOK_SECRET, PRICE, allowLive, null), Duration.ofMinutes(5));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "none", "NONE"})
  void theProviderIsOffByDefault(String value) {
    BillingProperties properties =
        new BillingProperties(value, stripe(null, null, null, false, null), Duration.ofMinutes(5));
    assertThat(properties.enabled()).isFalse();
    assertThat(properties.selected()).isEqualTo(BillingProperties.Provider.NONE);
    assertThat(properties.stripe().apiBaseUrl())
        .isEqualTo(BillingProperties.DEFAULT_STRIPE_API_BASE_URL);
    assertThat(new BillingProperties(null, properties.stripe(), Duration.ofMinutes(5)).enabled())
        .isFalse();
  }

  @Test
  void anUnknownProviderIsRefused() {
    assertThatThrownBy(() ->
            new BillingProperties(
                "iyzico", stripe(null, null, null, false, null), Duration.ofMinutes(5)))
        .hasMessageContaining("VITADELTA_BILLING_PROVIDER must be none or stripe");
  }

  @Test
  void aCompleteTestConfigurationIsAccepted() {
    BillingProperties properties = stripeProperties(TEST_KEY, false);
    assertThat(properties.enabled()).isTrue();
    assertThat(properties.selected()).isEqualTo(BillingProperties.Provider.STRIPE);
    assertThat(properties.stripe().liveMode()).isFalse();
    String restricted = "rk_" + "test_" + TestEnvironment.randomHex(24);
    assertThat(stripeProperties(restricted, false).stripe().liveMode()).isFalse();
  }

  @Test
  void liveKeysAreRefusedUnlessAllowed() {
    assertThatThrownBy(() -> stripeProperties(LIVE_KEY, false))
        .hasMessageContaining("live-mode key")
        .hasMessageContaining("VITADELTA_STRIPE_ALLOW_LIVE")
        .hasMessageNotContaining(LIVE_KEY);
    String restrictedLive = "rk_" + "live_" + TestEnvironment.randomHex(24);
    assertThatThrownBy(() -> stripeProperties(restrictedLive, false))
        .hasMessageContaining("live-mode key");
    BillingProperties allowed = stripeProperties(LIVE_KEY, true);
    assertThat(allowed.stripe().liveMode()).isTrue();
  }

  @Test
  void everyStripeValueIsRequired() {
    assertThatThrownBy(() ->
            new BillingProperties(
                "stripe", stripe(" ", WEBHOOK_SECRET, PRICE, false, null), Duration.ofMinutes(5)))
        .hasMessageContaining("VITADELTA_STRIPE_SECRET_KEY is not set");
    assertThatThrownBy(() ->
            new BillingProperties(
                "stripe", stripe(TEST_KEY, null, PRICE, false, null), Duration.ofMinutes(5)))
        .hasMessageContaining("VITADELTA_STRIPE_WEBHOOK_SECRET is not set");
    assertThatThrownBy(() ->
            new BillingProperties(
                "stripe",
                stripe(TEST_KEY, WEBHOOK_SECRET, null, false, null),
                Duration.ofMinutes(5)))
        .hasMessageContaining("VITADELTA_STRIPE_PRICE_ID is not set");
  }

  @Test
  void malformedValuesAreRefusedWithoutEchoingSecrets() {
    String publishable = "pk_" + "test_" + TestEnvironment.randomHex(24);
    assertThatThrownBy(() -> stripeProperties(publishable, false))
        .hasMessageContaining("is not a Stripe secret key")
        .hasMessageNotContaining(publishable);
    String notWhsec = TestEnvironment.randomHex(24);
    assertThatThrownBy(() ->
            new BillingProperties(
                "stripe", stripe(TEST_KEY, notWhsec, PRICE, false, null), Duration.ofMinutes(5)))
        .hasMessageContaining("not a webhook signing secret")
        .hasMessageNotContaining(notWhsec);
    assertThatThrownBy(() ->
            new BillingProperties(
                "stripe",
                stripe(TEST_KEY, WEBHOOK_SECRET, "prod_123", false, null),
                Duration.ofMinutes(5)))
        .hasMessageContaining("not a Stripe price id");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://api.stripe.com",
        "http://10.0.0.5:8080",
        "ftp://api.stripe.com",
        "https://user:pw@api.stripe.com",
        "https://api.stripe.com?x=1",
        "not a url"
      })
  void theApiBaseUrlMustBeHttpsOrLoopback(String url) {
    assertThatThrownBy(() ->
            new BillingProperties(
                "stripe",
                stripe(TEST_KEY, WEBHOOK_SECRET, PRICE, false, url),
                Duration.ofMinutes(5)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("api-base-url");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://localhost:8089/",
        "http://127.0.0.1:9",
        "http://[::1]:8080",
        "https://x.y"
      })
  void loopbackHttpAndHttpsAreAccepted(String url) {
    BillingProperties properties =
        new BillingProperties(
            "stripe", stripe(TEST_KEY, WEBHOOK_SECRET, PRICE, false, url), Duration.ofMinutes(5));
    assertThat(properties.stripe().apiBaseUrl()).doesNotEndWith("/");
  }

  @Test
  void limitsAndToleranceMustBePositive() {
    assertThatThrownBy(() ->
            new BillingProperties("none", stripe(null, null, null, false, null), Duration.ZERO))
        .hasMessageContaining("webhook-tolerance");
    assertThatThrownBy(() ->
            new BillingProperties(
                "stripe",
                new BillingProperties.Stripe(
                    TEST_KEY,
                    WEBHOOK_SECRET,
                    PRICE,
                    false,
                    null,
                    Duration.ZERO,
                    Duration.ofSeconds(1),
                    4096),
                Duration.ofMinutes(5)))
        .hasMessageContaining("connect-timeout");
    assertThatThrownBy(() ->
            new BillingProperties(
                "stripe",
                new BillingProperties.Stripe(
                    TEST_KEY,
                    WEBHOOK_SECRET,
                    PRICE,
                    false,
                    null,
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    100),
                Duration.ofMinutes(5)))
        .hasMessageContaining("max-response-bytes");
  }

  @Test
  void toStringHidesTheSecrets() {
    BillingProperties properties = stripeProperties(TEST_KEY, false);
    assertThat(properties.toString())
        .doesNotContain(TEST_KEY)
        .doesNotContain(WEBHOOK_SECRET)
        .contains("<hidden>")
        .contains(PRICE);
    assertThat(stripe(null, null, null, false, null).toString()).contains("<unset>");
  }
}
