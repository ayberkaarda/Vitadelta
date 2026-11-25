package com.vitadelta.api.alerts;

import static com.vitadelta.api.alerts.AlertEvaluator.Decision.FIRE;
import static com.vitadelta.api.alerts.AlertEvaluator.Decision.NONE;
import static com.vitadelta.api.alerts.AlertEvaluator.Decision.STORE_BASELINE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitadelta.api.common.problem.FieldViolation;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Pure rules: trigger decisions, threshold parsing and the module settings. */
class AlertRulesTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static OfferState priced(String price, boolean inStock) {
    return new OfferState(new BigDecimal(price), inStock, "R", "https://r.example/p");
  }

  private static AlertEvaluator.Decision drop(String baseline, String threshold, OfferState after) {
    return AlertEvaluator.decide(
        AlertTrigger.PRICE_DROP,
        baseline == null ? null : new BigDecimal(baseline),
        threshold == null ? null : new BigDecimal(threshold),
        priced("100.00", true),
        after);
  }

  @Test
  void priceDropComparesWithTheBaselineAndTheThreshold() {
    assertThat(drop("100.00", "5.00", priced("95.00", true))).isEqualTo(FIRE);
    assertThat(drop("100.00", "5.00", priced("95.01", true))).isEqualTo(NONE);
    assertThat(drop("100.00", "0.01", priced("99.99", false))).isEqualTo(FIRE);
    assertThat(drop("100.00", "5.00", OfferState.NONE)).isEqualTo(NONE);
    assertThat(drop("100.00", null, priced("10.00", true))).isEqualTo(NONE);
    assertThat(drop(null, "5.00", priced("10.00", true))).isEqualTo(STORE_BASELINE);
    assertThat(drop(null, "5.00", OfferState.NONE)).isEqualTo(NONE);
  }

  @Test
  void backInStockAndPriceChangeNeedBothStates() {
    assertThat(
            AlertEvaluator.decide(
                AlertTrigger.BACK_IN_STOCK,
                null,
                null,
                priced("1.00", false),
                priced("1.00", true)))
        .isEqualTo(FIRE);
    assertThat(
            AlertEvaluator.decide(
                AlertTrigger.BACK_IN_STOCK, null, null, OfferState.NONE, priced("1.00", true)))
        .isEqualTo(NONE);
    assertThat(
            AlertEvaluator.decide(
                AlertTrigger.PRICE_CHANGE, null, null, priced("1.00", true), priced("1.0", true)))
        .isEqualTo(NONE);
    assertThat(
            AlertEvaluator.decide(
                AlertTrigger.PRICE_CHANGE, null, null, priced("1.00", true), priced("1.01", true)))
        .isEqualTo(FIRE);
  }

  @Test
  void thresholdsAreRangeCheckedAndNeverRounded() {
    List<FieldViolation> errors = new ArrayList<>();
    assertThat(JsonFields.threshold(JSON.readTree("7.5"), errors)).isEqualByComparingTo("7.50");
    assertThat(JsonFields.threshold(JSON.readTree("99.99"), errors)).isEqualByComparingTo("99.99");
    assertThat(JsonFields.threshold(JSON.readTree("5.000"), errors)).isEqualByComparingTo("5");
    assertThat(errors).isEmpty();
    for (String bad : List.of("0.001", "0", "100", "1e400", "\"5\"", "true")) {
      List<FieldViolation> found = new ArrayList<>();
      assertThat(JsonFields.threshold(JSON.readTree(bad), found)).as(bad).isNull();
      assertThat(found).as(bad).hasSize(1);
    }
    assertThat(JsonFields.parseUuid("not-a-uuid")).isEmpty();
    assertThat(JsonFields.parseUuid(null)).isEmpty();
    assertThat(AlertTrigger.parse("PRICE_DROP")).contains(AlertTrigger.PRICE_DROP);
    assertThat(AlertTrigger.parse("price_drop")).isEmpty();
  }

  @Test
  void settingsAreValidated() {
    AlertsProperties defaults =
        new AlertsProperties(
            Duration.ofSeconds(10),
            100,
            Duration.ofMinutes(2),
            5,
            Duration.ofDays(30),
            Duration.ofDays(90),
            1000);
    assertThat(defaults.claimBatchSize()).isEqualTo(100);
    assertThatThrownBy(() ->
            new AlertsProperties(
                Duration.ZERO,
                100,
                Duration.ofMinutes(2),
                5,
                Duration.ofDays(30),
                Duration.ofDays(90),
                1000))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            new AlertsProperties(
                Duration.ofSeconds(10),
                0,
                Duration.ofMinutes(2),
                5,
                Duration.ofDays(30),
                Duration.ofDays(90),
                1000))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            new AlertsProperties(
                Duration.ofSeconds(10),
                100,
                Duration.ofMinutes(2),
                0,
                Duration.ofDays(30),
                Duration.ofDays(90),
                1000))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            new AlertsProperties(
                Duration.ofSeconds(10),
                100,
                Duration.ofMinutes(2),
                5,
                Duration.ofDays(30),
                Duration.ofDays(90),
                0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void failuresAreDescribedByClassNamesOnly() {
    RuntimeException error =
        new IllegalStateException("to a@b.example", new java.io.IOException("secret a@b"));
    assertThat(AlertEmailDispatcher.describe(error))
        .isEqualTo("IllegalStateException (cause IOException)")
        .doesNotContain("@");
    assertThat(AlertEmailDispatcher.describe(new IllegalStateException("x")))
        .isEqualTo("IllegalStateException");
  }

  @Test
  void thePreferenceLinkBecomesTheOnlyListUnsubscribeHeader() {
    com.vitadelta.api.mail.EmailMessage message =
        new com.vitadelta.api.mail.EmailMessage(
            "a@example.org", "Price drop", "t", "h", java.util.Locale.ENGLISH);
    assertThat(
            AlertEmailDispatcher.withPreferenceLink(message, "https://vitadelta.test/account")
                .headers())
        .containsExactly(
            java.util.Map.entry("List-Unsubscribe", "<https://vitadelta.test/account>"));
    for (String hostile :
        java.util.Arrays.asList(
            null,
            "",
            "https://vitadelta.test/a>, <mailto:x@example.org",
            "https://vitadelta.test/a b",
            "https://vitadelta.test/a\r\nBcc: x@example.org")) {
      assertThatThrownBy(() -> AlertEmailDispatcher.withPreferenceLink(message, hostile))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
