package com.vitadelta.api.recommendation;

import com.vitadelta.api.common.web.RequestIds;
import com.vitadelta.api.config.properties.ScoringProperties;
import com.vitadelta.api.recommendation.scoring.ApiClient;
import com.vitadelta.api.recommendation.scoring.api.ComparisonApi;
import com.vitadelta.api.recommendation.scoring.api.ScoringApi;
import com.vitadelta.api.recommendation.scoring.model.CompareRequest;
import com.vitadelta.api.recommendation.scoring.model.CompareResponse;
import com.vitadelta.api.recommendation.scoring.model.Problem;
import com.vitadelta.api.recommendation.scoring.model.ScoreRequest;
import com.vitadelta.api.recommendation.scoring.model.ScoreResponse;
import com.vitadelta.api.recommendation.scoring.model.ValidationError;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Calls the scoring service through the generated client with the resilience rules of section 7.6
 * (ADR-0003): the Resilience4j retry {@value #INSTANCE} (one retry on connection errors only) wraps
 * the circuit breaker {@value #INSTANCE} (50 % failures over 20 calls, 30 s open), so every attempt
 * is recorded by the breaker. The request id of the current request is propagated in {@code
 * X-Request-Id}.
 *
 * <p>Outcomes: {@link ScoringException.Unavailable} for connection errors, timeouts (also while the
 * answer body is read), {@code 5xx}, an open circuit and a saturated bulkhead (more than {@code
 * vitadelta.scoring.max-concurrent-calls} calls in flight: the call fails at once without reaching
 * the breaker); {@link ScoringException.Rejected} for {@code 422}; {@link ScoringException.Failed}
 * for any other {@code 4xx} or an unreadable answer. No text of a scoring answer is attached to
 * these exceptions or logged. The latency of every call is recorded in the timer {@value
 * #LATENCY_METRIC} (tags {@code operation}, {@code outcome}) with a percentile histogram; the
 * breaker state is exported by Resilience4j as {@code resilience4j.circuitbreaker.state}.
 *
 * <p>The callers must not hold a database connection or transaction while calling this class.
 */
@Component
public class ScoringGateway {

  /** Name of the Resilience4j retry and circuit breaker instances. */
  public static final String INSTANCE = "scoring";

  /** Timer of the scoring calls. */
  public static final String LATENCY_METRIC = "vitadelta.scoring.latency";

  private static final Logger LOG = LoggerFactory.getLogger(ScoringGateway.class);

  private final ScoringApi scoring;
  private final ComparisonApi comparison;
  private final ApiClient apiClient;
  private final CircuitBreaker circuitBreaker;
  private final Retry retry;
  private final MeterRegistry meters;
  private final int maxConcurrentCalls;
  private final Semaphore permits;

  public ScoringGateway(
      ScoringApi scoring,
      ComparisonApi comparison,
      ApiClient apiClient,
      CircuitBreakerRegistry circuitBreakers,
      RetryRegistry retries,
      MeterRegistry meters,
      ScoringProperties properties) {
    this.scoring = scoring;
    this.comparison = comparison;
    this.apiClient = apiClient;
    this.circuitBreaker = circuitBreakers.circuitBreaker(INSTANCE);
    this.retry = retries.retry(INSTANCE);
    this.meters = meters;
    this.maxConcurrentCalls = properties.maxConcurrentCalls();
    this.permits = new Semaphore(maxConcurrentCalls);
  }

  /** {@code POST /internal/v1/score}. */
  public ScoreResponse score(ScoreRequest request) {
    String requestId = MDC.get(RequestIds.KEY);
    return call("score", () -> scoring.scoreCandidates(request, requestId));
  }

  /** {@code POST /internal/v1/compare}. */
  public CompareResponse compare(CompareRequest request) {
    String requestId = MDC.get(RequestIds.KEY);
    return call("compare", () -> comparison.compareProducts(request, requestId));
  }

  /** The circuit breaker of the scoring calls (for health reporting and tests). */
  public CircuitBreaker circuitBreaker() {
    return circuitBreaker;
  }

  /** Number of scoring calls that may be in flight at once (for tests). */
  int maxConcurrentCalls() {
    return maxConcurrentCalls;
  }

  private <T> T call(String operation, Supplier<T> exchange) {
    Timer.Sample sample = Timer.start(meters);
    if (!permits.tryAcquire()) {
      LOG.warn(
          "Scoring {} rejected: {} scoring calls are already in flight",
          operation,
          maxConcurrentCalls);
      sample.stop(timer(operation, "saturated"));
      throw new ScoringException.Unavailable("too many scoring calls in flight", null);
    }
    try {
      return guardedCall(operation, exchange, sample);
    } finally {
      permits.release();
    }
  }

  private <T> T guardedCall(String operation, Supplier<T> exchange, Timer.Sample sample) {
    Supplier<T> guarded =
        Retry.decorateSupplier(
            retry, CircuitBreaker.decorateSupplier(circuitBreaker, () -> classified(exchange)));
    String outcome = "error";
    try {
      T result = guarded.get();
      if (result == null) {
        throw new ScoringException.Failed("scoring service returned no body", null);
      }
      outcome = "success";
      return result;
    } catch (CallNotPermittedException e) {
      outcome = "circuit_open";
      throw new ScoringException.Unavailable("scoring circuit is open", e);
    } catch (ResourceAccessException e) {
      outcome = "unavailable";
      LOG.warn("Scoring {} failed: {}", operation, rootCause(e).getClass().getSimpleName());
      throw new ScoringException.Unavailable("scoring service unreachable", e);
    } catch (HttpServerErrorException e) {
      outcome = "unavailable";
      LOG.warn("Scoring {} answered {}", operation, e.getStatusCode().value());
      throw new ScoringException.Unavailable("scoring service failed", e);
    } catch (HttpClientErrorException e) {
      // The answer body (and Spring's exception message, which quotes it) is never attached or
      // logged: only the status and, for a 422, the field paths and codes are used.
      if (e.getStatusCode().value() == HttpStatus.UNPROCESSABLE_CONTENT.value()) {
        outcome = "rejected";
        throw new ScoringException.Rejected(errors(e), null);
      }
      LOG.error("Scoring {} answered {}", operation, e.getStatusCode().value());
      throw new ScoringException.Failed(
          "scoring service answered " + e.getStatusCode().value(), null);
    } catch (RestClientException e) {
      LOG.error(
          "Scoring {} answer could not be read: {}",
          operation,
          rootCause(e).getClass().getSimpleName());
      throw new ScoringException.Failed("scoring service answer could not be read", null);
    } finally {
      sample.stop(timer(operation, outcome));
    }
  }

  private Timer timer(String operation, String outcome) {
    return Timer.builder(LATENCY_METRIC)
        .description("Latency of scoring service calls")
        .tag("operation", operation)
        .tag("outcome", outcome)
        .publishPercentileHistogram()
        .register(meters);
  }

  /**
   * Runs one exchange and reports a failure while the answer body was being read (a read timeout
   * after the headers, a reset or a truncated stream) as a {@link ResourceAccessException}, like a
   * failure before the headers: the service did not answer, so the circuit breaker records it and
   * the caller gets {@code 503}. Such a failure is not a connection error and is never retried.
   */
  static <T> T classified(Supplier<T> exchange) {
    try {
      return exchange.get();
    } catch (RestClientResponseException | ResourceAccessException e) {
      throw e;
    } catch (RestClientException e) {
      IOException io = ioCause(e);
      if (io == null) {
        throw e;
      }
      throw new ResourceAccessException("scoring answer could not be read completely", io);
    }
  }

  private static IOException ioCause(Throwable e) {
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      if (cause instanceof IOException io) {
        return io;
      }
      if (cause.getCause() == cause) {
        return null;
      }
    }
    return null;
  }

  private static Throwable rootCause(Throwable e) {
    Throwable cause = e;
    while (cause.getCause() != null && cause.getCause() != cause) {
      cause = cause.getCause();
    }
    return cause;
  }

  private List<ScoringException.FieldError> errors(HttpClientErrorException e) {
    try {
      Problem problem =
          apiClient.getJsonMapper().readValue(e.getResponseBodyAsByteArray(), Problem.class);
      if (problem == null || problem.getErrors() == null) {
        return List.of();
      }
      return problem.getErrors().stream().map(ScoringErrors::fieldError).toList();
    } catch (RuntimeException parse) {
      LOG.error("Scoring rejection could not be read: {}", parse.getClass().getSimpleName());
      return List.of();
    }
  }

  /** Mapping of the generated error model. */
  private static final class ScoringErrors {

    private ScoringErrors() {}

    static ScoringException.FieldError fieldError(ValidationError error) {
      return new ScoringException.FieldError(
          String.valueOf(error.getField()), String.valueOf(error.getCode()));
    }
  }
}
