package com.vitadelta.api.recommendation;

import com.vitadelta.api.common.problem.ProblemCode;
import com.vitadelta.api.common.problem.ProblemException;
import com.vitadelta.api.common.ratelimit.RateLimitPolicy;
import com.vitadelta.api.common.ratelimit.RateLimiter;
import com.vitadelta.api.config.properties.RecommendationProperties;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * Admission control of the expensive recommendation work of a signed-in user: a request budget per
 * minute and a cap on requests in flight. It is applied before the country catalog is loaded, so a
 * single account cannot occupy the catalog reads and the shared scoring capacity. Anonymous callers
 * are limited per client IP instead.
 *
 * <pre>{@code
 * try (UserRequestLimiter.Permit permit = limiter.admit(userId)) {
 *   // load the catalog and call the scoring service
 * }
 * }</pre>
 */
@Component
public class UserRequestLimiter {

  private final RateLimiter rateLimiter;
  private final RateLimitPolicy policy;
  private final int maxInFlight;
  private final ConcurrentHashMap<UUID, Integer> inFlight = new ConcurrentHashMap<>();

  public UserRequestLimiter(RateLimiter rateLimiter, RecommendationProperties properties) {
    this.rateLimiter = rateLimiter;
    this.policy =
        RateLimitPolicy.of(
            "recommendation-user", properties.userRequestsPerMinute(), Duration.ofMinutes(1));
    this.maxInFlight = properties.userConcurrency();
  }

  /** A claimed in-flight slot; closing it more than once is harmless. */
  public final class Permit implements AutoCloseable {

    private final UUID userId;
    private final AtomicBoolean closed = new AtomicBoolean();

    private Permit(UUID userId) {
      this.userId = userId;
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        inFlight.computeIfPresent(userId, (id, count) -> count <= 1 ? null : count - 1);
      }
    }
  }

  /**
   * Admits one request of {@code userId}.
   *
   * @throws ProblemException {@code 429 rate_limited} with {@code Retry-After} when the user has
   *     {@code userConcurrency} requests in flight or used up the per-minute budget
   */
  public Permit admit(UUID userId) {
    boolean[] taken = new boolean[1];
    inFlight.compute(
        userId,
        (id, count) -> {
          int current = count == null ? 0 : count;
          if (current >= maxInFlight) {
            return count;
          }
          taken[0] = true;
          return current + 1;
        });
    if (!taken[0]) {
      throw ProblemException.of(ProblemCode.RATE_LIMITED, "Too many requests; retry later.")
          .withHeader(HttpHeaders.RETRY_AFTER, "1");
    }
    Permit permit = new Permit(userId);
    try {
      rateLimiter.check(policy, userId.toString());
    } catch (RuntimeException e) {
      permit.close();
      throw e;
    }
    return permit;
  }
}
