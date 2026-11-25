package com.vitadelta.api.recommendation;

import com.vitadelta.api.catalog.web.EngineParameters;
import com.vitadelta.api.catalog.web.EngineParametersSource;
import com.vitadelta.api.common.web.RequestIds;
import com.vitadelta.api.config.properties.HealthProperties;
import com.vitadelta.api.recommendation.scoring.api.HealthApi;
import com.vitadelta.api.recommendation.scoring.model.EngineConfig;
import com.vitadelta.api.recommendation.scoring.model.HealthResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Health of the scoring service and its engine parameters, both from {@code GET
 * /internal/v1/health} through the generated client (same HTTP client and timeouts as the scoring
 * calls: connect 500 ms, read 2 s).
 *
 * <ul>
 *   <li>{@link #probe()} ({@code GET /admin/health}): {@code true} when the service answers 200
 *       within the timeouts. While the scoring circuit breaker is open the probe answers {@code
 *       false} without a call. A probe is neither retried nor recorded by the breaker, so health
 *       polling never changes how recommendation calls are treated.
 *   <li>{@link #current()} ({@code GET /weights/defaults}): the {@code config} of the last 200
 *       answer, kept for the lifetime of the process and replaced by every later successful probe.
 *       It is first read in the background when the application is ready; while none was obtained,
 *       a request tries once more at most every {@code vitadelta.health.engine-retry-interval} (one
 *       attempt at a time; concurrent requests do not wait for it).
 * </ul>
 */
@Component
public class ScoringHealth implements EngineParametersSource {

  private static final Logger LOG = LoggerFactory.getLogger(ScoringHealth.class);

  private final HealthApi health;
  private final CircuitBreaker circuitBreaker;
  private final long retryIntervalNanos;
  private final LongSupplier ticker;
  private final AtomicReference<EngineParameters> engine = new AtomicReference<>();
  private final AtomicBoolean fetching = new AtomicBoolean();
  private final AtomicLong nextAttempt = new AtomicLong();
  private final AtomicBoolean attempted = new AtomicBoolean();

  @Autowired
  public ScoringHealth(HealthApi health, ScoringGateway gateway, HealthProperties properties) {
    this(health, gateway.circuitBreaker(), properties, System::nanoTime);
  }

  ScoringHealth(
      HealthApi health,
      CircuitBreaker circuitBreaker,
      HealthProperties properties,
      LongSupplier ticker) {
    this.health = health;
    this.circuitBreaker = circuitBreaker;
    this.retryIntervalNanos = properties.engineRetryInterval().toNanos();
    this.ticker = ticker;
  }

  /** Reads the engine parameters once in the background when the application is ready. */
  @EventListener(ApplicationReadyEvent.class)
  void fetchInBackground() {
    Thread.ofVirtual().name("scoring-engine-parameters").start(this::tryFetch);
  }

  /**
   * Whether the scoring service answers {@code GET /internal/v1/health} with 200 within the client
   * timeouts; {@code false} at once while the circuit breaker is open.
   */
  public boolean probe() {
    CircuitBreaker.State state = circuitBreaker.getState();
    if (state == CircuitBreaker.State.OPEN || state == CircuitBreaker.State.FORCED_OPEN) {
      return false;
    }
    HealthResponse answer;
    try {
      answer = health.getHealth(MDC.get(RequestIds.KEY));
    } catch (RuntimeException e) {
      LOG.debug("Scoring health check failed: {}", e.getClass().getSimpleName());
      return false;
    }
    remember(answer == null ? null : answer.getConfig());
    return true;
  }

  @Override
  public Optional<EngineParameters> current() {
    EngineParameters known = engine.get();
    if (known == null) {
      tryFetch();
      known = engine.get();
    }
    return Optional.ofNullable(known);
  }

  /** One attempt to read the parameters, unless one runs or the last failed one is too recent. */
  void tryFetch() {
    if (engine.get() != null) {
      return;
    }
    long now = ticker.getAsLong();
    if (attempted.get() && now - nextAttempt.get() < 0) {
      return;
    }
    if (!fetching.compareAndSet(false, true)) {
      return;
    }
    try {
      attempted.set(true);
      nextAttempt.set(now + retryIntervalNanos);
      if (!probe()) {
        LOG.info("Scoring engine parameters not available yet");
      }
    } finally {
      fetching.set(false);
    }
  }

  private void remember(EngineConfig config) {
    if (config == null
        || config.getDiminishingBeta() == null
        || config.getUtilityCeiling() == null
        || config.getVerdictWorthItMaxRatio() == null
        || config.getVerdictMarginalMaxRatio() == null) {
      return;
    }
    engine.set(
        new EngineParameters(
            config.getDiminishingBeta(),
            config.getUtilityCeiling(),
            config.getVerdictWorthItMaxRatio(),
            config.getVerdictMarginalMaxRatio()));
  }
}
