package com.vitadelta.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitadelta.api.common.problem.ProblemCode;
import com.vitadelta.api.common.problem.ProblemException;
import com.vitadelta.api.identity.RecipientMailLimiter.MailKind;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/** Per-account login back-off and per-recipient e-mail limits (section 8.2). */
class ThrottlingTest {

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-24T10:00:00Z"));

  private static long retryAfter(LoginThrottle throttle, String email) {
    try {
      throttle.checkAllowed(email);
      return 0;
    } catch (ProblemException e) {
      assertThat(e.code()).isEqualTo(ProblemCode.RATE_LIMITED);
      return Long.parseLong(e.headers().getFirst(HttpHeaders.RETRY_AFTER));
    }
  }

  @Test
  void backOffDoublesUpToOneHourAndSuccessResets() {
    LoginThrottle throttle = new LoginThrottle(clock);
    String email = "someone@example.org";
    for (int i = 0; i < 9; i++) {
      throttle.recordFailure(email);
      assertThat(retryAfter(throttle, email)).isZero();
    }
    throttle.recordFailure(email);
    assertThat(retryAfter(throttle, email)).isEqualTo(60);
    long[] expected = {120, 240, 480, 960, 1920, 3600, 3600};
    for (long seconds : expected) {
      clock.advance(Duration.ofSeconds(1));
      throttle.recordFailure(email);
      assertThat(retryAfter(throttle, email)).isEqualTo(seconds);
    }
    throttle.recordSuccess(email);
    assertThat(retryAfter(throttle, email)).isZero();
    assertThat(retryAfter(throttle, "other@example.org")).isZero();
  }

  @Test
  void failuresOutsideTheWindowAreForgotten() {
    LoginThrottle throttle = new LoginThrottle(clock);
    String email = "slow@example.org";
    for (int i = 0; i < 9; i++) {
      throttle.recordFailure(email);
    }
    clock.advance(LoginThrottle.WINDOW.plusSeconds(1));
    throttle.recordFailure(email);
    assertThatNoException().isThrownBy(() -> throttle.checkAllowed(email));
    for (int i = 0; i < 9; i++) {
      throttle.recordFailure(email);
    }
    assertThat(retryAfter(throttle, email)).isEqualTo(60);
    clock.advance(Duration.ofSeconds(60));
    assertThat(retryAfter(throttle, email)).isZero();
  }

  @Test
  void recipientsGetOneMailPerKindPerTenMinutesAndFivePerDay() {
    RecipientMailLimiter limiter = new RecipientMailLimiter(clock);
    String email = "inbox@example.org";
    assertThat(limiter.tryAcquire(MailKind.RESET_PASSWORD, email)).isTrue();
    assertThat(limiter.tryAcquire(MailKind.RESET_PASSWORD, email)).isFalse();
    assertThat(limiter.tryAcquire(MailKind.VERIFY_EMAIL, email)).isTrue();
    assertThat(limiter.tryAcquire(MailKind.ALREADY_REGISTERED, email)).isTrue();
    assertThat(limiter.tryAcquire(MailKind.RESET_PASSWORD, "other@example.org")).isTrue();
    int sent = 1;
    for (int i = 0; i < 6; i++) {
      clock.advance(Duration.ofMinutes(10));
      if (limiter.tryAcquire(MailKind.RESET_PASSWORD, email)) {
        sent++;
      }
    }
    assertThat(sent).isEqualTo(5);
    assertThat(MailKind.RESET_PASSWORD.template()).isEqualTo("reset-password");
  }

  @Test
  void rateLimitedProblemsCarryRetryAfter() {
    LoginThrottle throttle = new LoginThrottle(clock);
    for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) {
      throttle.recordFailure("x@example.org");
    }
    assertThatThrownBy(() -> throttle.checkAllowed("x@example.org"))
        .isInstanceOfSatisfying(
            ProblemException.class,
            e -> assertThat(e.headers().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("60"));
  }
}
