package com.vitadelta.api.identity;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.vitadelta.api.common.problem.ProblemCode;
import com.vitadelta.api.common.problem.ProblemException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * Per-account login back-off (section 8.2), keyed by the normalized submitted e-mail whether or not
 * the account exists (enumeration safe). After {@value #MAX_FAILURES} failures within {@link
 * #WINDOW} further attempts are rejected with {@code 429 rate_limited} for 1 minute; every further
 * failure while the failures stay within the window doubles the back-off, up to {@link
 * #MAX_BACKOFF}. A successful login resets the state. In memory, per instance (ADR-0011).
 */
@Component
public class LoginThrottle {

  public static final int MAX_FAILURES = 10;
  public static final Duration WINDOW = Duration.ofMinutes(15);
  public static final Duration FIRST_BACKOFF = Duration.ofMinutes(1);
  public static final Duration MAX_BACKOFF = Duration.ofMinutes(60);

  private final Cache<String, State> states =
      Caffeine.newBuilder()
          .expireAfterAccess(MAX_BACKOFF.plus(WINDOW))
          .maximumSize(1_000_000)
          .build();
  private final Clock clock;

  public LoginThrottle(Clock clock) {
    this.clock = clock;
  }

  private static final class State {
    private final Deque<Instant> failures = new ArrayDeque<>();
    private Instant lockedUntil = Instant.MIN;
    private int lockouts;
  }

  /**
   * Rejects the attempt while the account is backed off.
   *
   * @throws ProblemException {@code 429 rate_limited} with {@code Retry-After}
   */
  public void checkAllowed(String email) {
    State state = states.getIfPresent(email);
    if (state == null) {
      return;
    }
    Instant now = clock.instant();
    Instant until;
    synchronized (state) {
      until = state.lockedUntil;
    }
    if (now.isBefore(until)) {
      long seconds = Math.max(1, (Duration.between(now, until).toMillis() + 999) / 1000);
      throw ProblemException.of(ProblemCode.RATE_LIMITED, "Too many requests; retry later.")
          .withHeader(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
    }
  }

  /** Records a failed attempt (wrong password or unknown e-mail). */
  public void recordFailure(String email) {
    State state = states.get(email, key -> new State());
    Instant now = clock.instant();
    synchronized (state) {
      Instant windowStart = now.minus(WINDOW);
      while (!state.failures.isEmpty() && !state.failures.peekFirst().isAfter(windowStart)) {
        state.failures.pollFirst();
      }
      if (state.failures.isEmpty()) {
        state.lockouts = 0;
      }
      state.failures.addLast(now);
      if (state.failures.size() >= MAX_FAILURES) {
        Duration backoff = FIRST_BACKOFF.multipliedBy(1L << Math.min(state.lockouts, 6));
        if (backoff.compareTo(MAX_BACKOFF) > 0) {
          backoff = MAX_BACKOFF;
        }
        state.lockedUntil = now.plus(backoff);
        state.lockouts++;
      }
    }
  }

  /** Resets the account after a successful login. */
  public void recordSuccess(String email) {
    states.invalidate(email);
  }
}
