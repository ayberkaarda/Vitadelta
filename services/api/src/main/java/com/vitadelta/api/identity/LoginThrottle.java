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
 * the account exists (enumeration safe). In memory, per instance (ADR-0011).
 *
 * <ul>
 *   <li>Every attempt first {@linkplain #begin reserves} capacity atomically, before the password
 *       is verified: at most {@value #MAX_FAILURES} attempts (failures within {@link #WINDOW} plus
 *       attempts in flight) are admitted, so parallel requests cannot exceed the budget.
 *   <li>The {@value #MAX_FAILURES}th failure within the window locks the e-mail for 1 minute. From
 *       then on escalation is active: after a lock has ended, one attempt at a time is admitted and
 *       every further failure locks again for twice as long (2, 4, 8 ... minutes, capped at {@link
 *       #MAX_BACKOFF}). The escalation level does not depend on the rolling window.
 *   <li>A successful login resets everything; so does a quiet period of {@link #QUIET_PERIOD}
 *       without failures.
 * </ul>
 *
 * Rejections are {@code 429 rate_limited} with {@code Retry-After}.
 */
@Component
public class LoginThrottle {

  public static final int MAX_FAILURES = 10;
  public static final Duration WINDOW = Duration.ofMinutes(15);
  public static final Duration FIRST_BACKOFF = Duration.ofMinutes(1);
  public static final Duration MAX_BACKOFF = Duration.ofMinutes(60);
  public static final Duration QUIET_PERIOD = Duration.ofHours(24);

  private final Cache<String, State> states =
      Caffeine.newBuilder()
          .expireAfterAccess(QUIET_PERIOD.plus(MAX_BACKOFF))
          .maximumSize(1_000_000)
          .build();
  private final Clock clock;

  public LoginThrottle(Clock clock) {
    this.clock = clock;
  }

  private static final class State {
    private final Deque<Instant> failures = new ArrayDeque<>();
    private Instant lockedUntil = Instant.MIN;
    private Instant lastFailure = Instant.MIN;
    private int level;
    private int inFlight;
  }

  /** One admitted attempt; settle it with exactly one of the methods (later calls are ignored). */
  public final class Attempt {

    private final String email;
    private final State state;
    private boolean settled;

    private Attempt(String email, State state) {
      this.email = email;
      this.state = state;
    }

    /** The password was wrong or the e-mail unknown. */
    public void failed() {
      synchronized (state) {
        if (settled) {
          return;
        }
        settled = true;
        state.inFlight--;
        Instant now = clock.instant();
        prune(state, now);
        state.failures.addLast(now);
        state.lastFailure = now;
        if (state.level > 0 || state.failures.size() >= MAX_FAILURES) {
          Duration backoff = FIRST_BACKOFF.multipliedBy(1L << Math.min(state.level, 6));
          state.lockedUntil = now.plus(backoff.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff);
          state.level++;
        }
      }
    }

    /** The password was correct: the account state is reset. */
    public void succeeded() {
      synchronized (state) {
        if (settled) {
          return;
        }
        settled = true;
        state.inFlight--;
      }
      states.asMap().remove(email, state);
    }

    /** The attempt ended without a verdict (for example an error); its capacity is returned. */
    public void release() {
      synchronized (state) {
        if (settled) {
          return;
        }
        settled = true;
        state.inFlight--;
      }
    }
  }

  private static void prune(State state, Instant now) {
    Instant windowStart = now.minus(WINDOW);
    while (!state.failures.isEmpty() && !state.failures.peekFirst().isAfter(windowStart)) {
      state.failures.pollFirst();
    }
  }

  /**
   * Reserves an attempt for {@code email}.
   *
   * @throws ProblemException {@code 429 rate_limited} with {@code Retry-After} while the e-mail is
   *     locked or its attempt budget is taken by attempts in flight
   */
  public Attempt begin(String email) {
    State state = states.get(email, key -> new State());
    Instant now = clock.instant();
    synchronized (state) {
      if (!state.lastFailure.equals(Instant.MIN)
          && !now.isBefore(state.lastFailure.plus(QUIET_PERIOD))
          && state.inFlight == 0) {
        state.failures.clear();
        state.level = 0;
        state.lockedUntil = Instant.MIN;
        state.lastFailure = Instant.MIN;
      }
      if (now.isBefore(state.lockedUntil)) {
        throw rateLimited(Duration.between(now, state.lockedUntil));
      }
      prune(state, now);
      boolean admitted =
          state.level > 0
              ? state.inFlight == 0
              : state.failures.size() + state.inFlight < MAX_FAILURES;
      if (!admitted) {
        throw rateLimited(Duration.ofSeconds(1));
      }
      state.inFlight++;
      return new Attempt(email, state);
    }
  }

  private static ProblemException rateLimited(Duration wait) {
    long seconds = Math.max(1, (wait.toMillis() + 999) / 1000);
    return ProblemException.of(ProblemCode.RATE_LIMITED, "Too many requests; retry later.")
        .withHeader(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
  }
}
