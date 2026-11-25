package com.vitadelta.api.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vitadelta.api.catalog.web.EngineParameters;
import com.vitadelta.api.config.properties.HealthProperties;
import com.vitadelta.api.recommendation.scoring.api.HealthApi;
import com.vitadelta.api.recommendation.scoring.model.EngineConfig;
import com.vitadelta.api.recommendation.scoring.model.HealthResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.client.ResourceAccessException;

/** Probe and engine-parameter cache of {@link ScoringHealth} with a stubbed client and ticker. */
class ScoringHealthTest {

  private final HealthApi api = mock(HealthApi.class);
  private final CircuitBreaker breaker = CircuitBreaker.ofDefaults("scoring-health-test");
  private final AtomicLong now = new AtomicLong(1_000L);
  private final ScoringHealth health =
      new ScoringHealth(
          api,
          breaker,
          new HealthProperties(
              Duration.ofSeconds(10), Duration.ofMinutes(5), Duration.ofSeconds(30)),
          now::get);

  private static HealthResponse answer(String beta, String ceiling, String worth, String marginal) {
    return new HealthResponse()
        .status(HealthResponse.StatusEnum.UP)
        .config(
            new EngineConfig()
                .diminishingBeta(beta == null ? null : new BigDecimal(beta))
                .utilityCeiling(new BigDecimal(ceiling))
                .comparisonEpsilon(new BigDecimal("1.0e-9"))
                .verdictWorthItMaxRatio(new BigDecimal(worth))
                .verdictMarginalMaxRatio(new BigDecimal(marginal)));
  }

  @Test
  void aSuccessfulProbeKeepsTheParametersUnrounded() {
    when(api.getHealth(any())).thenReturn(answer("0.35", "1.5", "1.0", "1.5"));
    assertThat(health.probe()).isTrue();
    assertThat(health.current())
        .contains(
            new EngineParameters(
                new BigDecimal("0.35"),
                new BigDecimal("1.5"),
                new BigDecimal("1.0"),
                new BigDecimal("1.5")));
    assertThat(health.current().orElseThrow().verdictWorthItMaxRatio().toPlainString())
        .isEqualTo("1.0");
  }

  @Test
  void parametersSurviveALaterOutageAndAreReplacedByTheNextAnswer() {
    when(api.getHealth(any()))
        .thenReturn(answer("0.35", "1.5", "1.0", "1.5"))
        .thenThrow(new ResourceAccessException("down"))
        .thenReturn(answer("0.4", "2", "1.1", "1.6"));
    assertThat(health.probe()).isTrue();
    assertThat(health.probe()).isFalse();
    assertThat(health.current().orElseThrow().diminishingBeta()).isEqualByComparingTo("0.35");
    assertThat(health.probe()).isTrue();
    assertThat(health.current().orElseThrow().diminishingBeta()).isEqualByComparingTo("0.4");
  }

  @Test
  void withoutParametersARequestRetriesAtMostOncePerInterval() {
    when(api.getHealth(any())).thenThrow(new ResourceAccessException("down"));
    assertThat(health.current()).isEmpty();
    assertThat(health.current()).isEmpty();
    verify(api, times(1)).getHealth(any());
    now.addAndGet(Duration.ofSeconds(29).toNanos());
    assertThat(health.current()).isEmpty();
    verify(api, times(1)).getHealth(any());
    now.addAndGet(Duration.ofSeconds(2).toNanos());
    assertThat(health.current()).isEmpty();
    verify(api, times(2)).getHealth(any());
  }

  @Test
  void theFirstAnswerAfterAnOutageIsUsedAtOnce() {
    when(api.getHealth(any()))
        .thenThrow(new ResourceAccessException("down"))
        .thenReturn(answer("0.35", "1.5", "1.0", "1.5"));
    health.tryFetch();
    assertThat(health.current()).isEmpty();
    now.addAndGet(Duration.ofSeconds(31).toNanos());
    assertThat(health.current()).isPresent();
    now.addAndGet(Duration.ofMinutes(5).toNanos());
    assertThat(health.current()).isPresent();
    verify(api, times(2)).getHealth(any());
  }

  @Test
  void anIncompleteConfigIsNotKeptButTheServiceIsUp() {
    when(api.getHealth(any())).thenReturn(answer(null, "1.5", "1.0", "1.5"));
    assertThat(health.probe()).isTrue();
    assertThat(health.current()).isEmpty();
    when(api.getHealth(any()))
        .thenReturn(new HealthResponse().status(HealthResponse.StatusEnum.UP));
    assertThat(health.probe()).isTrue();
    when(api.getHealth(any())).thenReturn(null);
    assertThat(health.probe()).isTrue();
  }

  @ParameterizedTest
  @CsvSource({
    "-1, 1.5, 1.0, 1.5",
    "0, 1.5, 1.0, 1.5",
    "0.35, 0.5, 1.0, 1.5",
    "0.35, 0.999999, 1.0, 1.5",
    "0.35, 1.5, 0, 1.5",
    "0.35, 1.5, -1, 1.5",
    "0.35, 1.5, 1.0, 0",
    "0.35, 1.5, 1.0, -3",
    "0.35, 1.5, 1.6, 1.5"
  })
  void parametersOutsideTheDocumentedRangesAreNeverPublished(
      String beta, String ceiling, String worth, String marginal) {
    when(api.getHealth(any()))
        .thenReturn(answer("0.35", "1.5", "1.0", "1.5"))
        .thenReturn(answer(beta, ceiling, worth, marginal));
    assertThat(health.probe()).isTrue();
    assertThat(health.current()).isPresent();
    assertThat(health.probe()).as("the service still answers").isTrue();
    assertThat(health.current()).as("neither the new nor the older values").isEmpty();
  }

  @Test
  void theDocumentedBoundariesThemselvesArePublished() {
    when(api.getHealth(any())).thenReturn(answer("0.000001", "1", "0.5", "0.5"));
    assertThat(health.probe()).isTrue();
    assertThat(health.current())
        .contains(
            new EngineParameters(
                new BigDecimal("0.000001"),
                BigDecimal.ONE,
                new BigDecimal("0.5"),
                new BigDecimal("0.5")));
  }

  @Test
  void anIncompleteAnswerAfterAValidOneClearsThePublishedParameters() {
    when(api.getHealth(any()))
        .thenReturn(answer("0.35", "1.5", "1.0", "1.5"))
        .thenReturn(answer(null, "1.5", "1.0", "1.5"));
    assertThat(health.probe()).isTrue();
    assertThat(health.current()).isPresent();
    assertThat(health.probe()).isTrue();
    assertThat(health.current()).isEmpty();
  }

  @Test
  void anOpenCircuitIsDownWithoutACall() {
    breaker.transitionToOpenState();
    assertThat(health.probe()).isFalse();
    breaker.transitionToForcedOpenState();
    assertThat(health.probe()).isFalse();
    verify(api, never()).getHealth(any());
    breaker.transitionToClosedState();
    when(api.getHealth(any())).thenReturn(answer("0.35", "1.5", "1.0", "1.5"));
    assertThat(health.probe()).isTrue();
  }
}
