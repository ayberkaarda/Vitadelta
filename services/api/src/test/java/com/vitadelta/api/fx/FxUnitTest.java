package com.vitadelta.api.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vitadelta.api.entitlement.PriceConversion;
import com.vitadelta.api.fx.FxRateRepository.StoredRate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/** Unit tests of the FX building blocks that need no database. */
class FxUnitTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-25T08:00:00Z"), ZoneOffset.UTC);
  private static final LocalDate TODAY = LocalDate.of(2026, 11, 25);

  private static FxProperties properties(String baseUrl, List<String> extras) {
    return new FxProperties(
        baseUrl,
        extras,
        Duration.ofSeconds(2),
        Duration.ofSeconds(5),
        65536,
        Duration.ofHours(6),
        1,
        5,
        Duration.ofSeconds(60));
  }

  private static StoredRate rate(String base, String quote, LocalDate asOf, String value) {
    return new StoredRate(base, quote, asOf, new BigDecimal(value), CLOCK.instant(), "FRANKFURTER");
  }

  // ---------- Properties ----------

  @Test
  void propertiesNormaliseTheBaseUrlAndTheExtraCurrencies() {
    FxProperties p =
        properties("  https://api.frankfurter.dev/v2/  ", List.of(" chf", "", "JPY", "CHF"));

    assertThat(p.baseUrl()).isEqualTo("https://api.frankfurter.dev/v2");
    assertThat(p.extraCurrencies()).containsExactly("CHF", "JPY");
    assertThat(properties("http://127.0.0.1:9/v2", null).extraCurrencies()).isEmpty();
    assertThat(FxProperties.DEFAULT_BASE_URL).isEqualTo("https://api.frankfurter.dev/v2");
    assertThat(properties(null, List.of()).baseUrl()).isEqualTo(FxProperties.DEFAULT_BASE_URL);
    assertThat(properties(" ", List.of()).baseUrl()).isEqualTo(FxProperties.DEFAULT_BASE_URL);
  }

  @Test
  void propertiesRejectInvalidValues() {
    for (String url :
        new String[] {
          "ftp://example.org/v2",
          "/v2",
          "https://example.org/v2?x=1",
          "https://example.org/v2#f",
          "https://user:pw@example.org/v2",
          "https://exa mple.org"
        }) {
      assertThatThrownBy(() -> properties(url, List.of()))
          .as(String.valueOf(url))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> properties(FxProperties.DEFAULT_BASE_URL, List.of("XQZ")))
        .hasMessageContaining("ISO 4217");
    assertThatThrownBy(() -> properties(FxProperties.DEFAULT_BASE_URL, List.of("EURO")))
        .hasMessageContaining("ISO 4217");
    String url = FxProperties.DEFAULT_BASE_URL;
    assertThatThrownBy(() ->
            new FxProperties(
                url,
                List.of(),
                Duration.ZERO,
                Duration.ofSeconds(5),
                65536,
                Duration.ofHours(6),
                1,
                5,
                Duration.ofSeconds(60)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            new FxProperties(
                url,
                List.of(),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                100,
                Duration.ofHours(6),
                1,
                5,
                Duration.ofSeconds(60)))
        .hasMessageContaining("max-response-bytes");
    assertThatThrownBy(() ->
            new FxProperties(
                url,
                List.of(),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                65536,
                Duration.ofHours(6),
                -1,
                5,
                Duration.ofSeconds(60)))
        .hasMessageContaining("startup-max-age");
    assertThatThrownBy(() ->
            new FxProperties(
                url,
                List.of(),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                65536,
                Duration.ofHours(6),
                1,
                -1,
                Duration.ofSeconds(60)))
        .hasMessageContaining("stale-after-days");
  }

  // ---------- Response summary ----------

  @Test
  void summariesAreBoundedAndPrintable() {
    assertThat(ResponseSummary.of(null)).isEqualTo("0 bytes");
    assertThat(ResponseSummary.of(new byte[0])).isEqualTo("0 bytes");
    assertThat(ResponseSummary.of("{\"a\":1}\r\n\t".getBytes(StandardCharsets.UTF_8)))
        .isEqualTo("10 bytes: {\"a\":1}???");
    String summary = ResponseSummary.of("é".repeat(500).getBytes(StandardCharsets.UTF_8));
    assertThat(summary).startsWith("1000 bytes: ").endsWith("[truncated]");
    assertThat(summary.length()).isLessThanOrEqualTo(12 + ResponseSummary.MAX_CHARS + 11);
    assertThat(summary.chars().allMatch(c -> c >= 0x20 && c < 0x7f)).isTrue();
  }

  // ---------- Lookup and converter ----------

  @Test
  void stalenessStartsAfterFiveDays() {
    FxRateLookup lookup =
        new FxRateLookup(
            mock(FxRateRepository.class),
            properties(FxProperties.DEFAULT_BASE_URL, List.of()),
            CLOCK);

    assertThat(lookup.today()).isEqualTo(TODAY);
    assertThat(lookup.isStale(TODAY)).isFalse();
    assertThat(lookup.isStale(TODAY.minusDays(5))).isFalse();
    assertThat(lookup.isStale(TODAY.minusDays(6))).isTrue();
  }

  @Test
  void lookupsAreCachedUntilInvalidated() {
    FxRateRepository repository = mock(FxRateRepository.class);
    when(repository.newest("TRY", "USD"))
        .thenReturn(Optional.of(rate("TRY", "USD", TODAY, "0.02413700")));
    FxRateLookup lookup =
        new FxRateLookup(repository, properties(FxProperties.DEFAULT_BASE_URL, List.of()), CLOCK);

    lookup.newest("TRY", "USD");
    lookup.newest("TRY", "USD");
    verify(repository, times(1)).newest("TRY", "USD");

    lookup.invalidate();
    lookup.newest("TRY", "USD");
    verify(repository, times(2)).newest("TRY", "USD");
  }

  @Test
  void theConverterAnswersIdentityStoredStaleAndUnavailablePairs() {
    FxRateRepository repository = mock(FxRateRepository.class);
    when(repository.newest(anyString(), anyString())).thenReturn(Optional.empty());
    when(repository.newest("TRY", "USD"))
        .thenReturn(Optional.of(rate("TRY", "USD", TODAY.minusDays(1), "0.02413700")));
    when(repository.newest("TRY", "EUR"))
        .thenReturn(Optional.of(rate("TRY", "EUR", TODAY.minusDays(6), "0.020875")));
    FxDisplayPriceConverter converter =
        new FxDisplayPriceConverter(
            new FxRateLookup(
                repository, properties(FxProperties.DEFAULT_BASE_URL, List.of()), CLOCK));

    assertThat(converter.conversion("TRY", "TRY")).isEqualTo(PriceConversion.identity("TRY"));
    verify(repository, never()).newest("TRY", "TRY");

    PriceConversion usd = converter.conversion("TRY", "USD");
    assertThat(usd.currency()).isEqualTo("USD");
    assertThat(usd.nativeCurrency()).isEqualTo("TRY");
    assertThat(usd.fx())
        .isEqualTo(
            new PriceConversion.Fx(
                "TRY",
                "USD",
                new BigDecimal("0.02413700"),
                TODAY.minusDays(1),
                "FRANKFURTER",
                false,
                PriceConversion.OK));
    assertThat(usd.fx().rate().toString()).isEqualTo("0.02413700");

    PriceConversion eur = converter.conversion("TRY", "EUR");
    assertThat(eur.fx().stale()).isTrue();
    assertThat(eur.fx().rate().toString()).isEqualTo("0.02087500");

    assertThat(converter.conversion("TRY", "GBP"))
        .isEqualTo(PriceConversion.unavailable("TRY", "GBP"));
  }

  /**
   * Property: for random unrounded prices and stored rates, conversion is the exact product rounded
   * once, and uniform scaling never reverses the order of two prices (ranking invariance at the
   * converter level; the endpoint test covers whole rankings).
   */
  @Test
  void conversionRoundsOnceAndPreservesOrder() {
    FxRateRepository repository = mock(FxRateRepository.class);
    Random random = new Random(20261125L);
    for (int round = 0; round < 200; round++) {
      BigDecimal stored =
          BigDecimal.valueOf(1 + random.nextInt(9_999_999), 8)
              .add(BigDecimal.valueOf(random.nextInt(60)));
      when(repository.newest("TRY", "USD"))
          .thenReturn(Optional.of(rate("TRY", "USD", TODAY, stored.toPlainString())));
      FxRateLookup lookup =
          new FxRateLookup(repository, properties(FxProperties.DEFAULT_BASE_URL, List.of()), CLOCK);
      PriceConversion conversion = new FxDisplayPriceConverter(lookup).conversion("TRY", "USD");
      double low = random.nextDouble() * 5000;
      double high = low + random.nextDouble() * 100;
      BigDecimal convertedLow = conversion.convert(new BigDecimal(low));
      BigDecimal convertedHigh = conversion.convert(new BigDecimal(high));
      assertThat(convertedLow)
          .isEqualTo(new BigDecimal(low).multiply(stored).setScale(4, RoundingMode.HALF_EVEN));
      assertThat(convertedLow).isLessThanOrEqualTo(convertedHigh);
    }
  }

  // ---------- Schedule ----------

  @Test
  void theFirstScheduledExecutionAppliesTheStartupRuleAndLaterOnesRefresh() {
    FxRefreshJob job = mock(FxRefreshJob.class);
    FxRefreshSchedule schedule =
        new FxRefreshSchedule(job, properties(FxProperties.DEFAULT_BASE_URL, List.of()));
    ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

    schedule.configureTasks(registrar);
    assertThat(registrar.getFixedRateTaskList()).hasSize(1);
    assertThat(registrar.getFixedRateTaskList().get(0).getIntervalDuration())
        .isEqualTo(Duration.ofHours(6));
    assertThat(registrar.getFixedRateTaskList().get(0).getInitialDelayDuration())
        .isEqualTo(Duration.ZERO);

    schedule.tick();
    verify(job, times(1)).runIfDue();
    verify(job, never()).run();
    schedule.tick();
    schedule.tick();
    verify(job, times(1)).runIfDue();
    verify(job, times(2)).run();
  }

  @Test
  void resultsReportSuccessOnlyForCompleteRuns() {
    assertThat(new FxRefreshJob.Result(true, 12, List.of(), false).succeeded()).isTrue();
    assertThat(new FxRefreshJob.Result(true, 9, List.of("GBP"), false).succeeded()).isFalse();
    assertThat(new FxRefreshJob.Result(true, 0, List.of(), true).succeeded()).isFalse();
    assertThat(FxRefreshJob.Result.skipped().succeeded()).isFalse();
  }
}
