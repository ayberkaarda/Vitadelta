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
      throttle.begin(email).release();
      return 0;
    } catch (ProblemException e) {
      assertThat(e.code()).isEqualTo(ProblemCode.RATE_LIMITED);
      return Long.parseLong(e.headers().getFirst(HttpHeaders.RETRY_AFTER));
    }
  }

  @Test
  void failuresOutsideTheWindowAreForgotten() {
    LoginThrottle throttle = new LoginThrottle(clock);
    String email = "slow@example.org";
    for (int i = 0; i < 9; i++) {
      attemptAndFail(throttle, email);
    }
    clock.advance(LoginThrottle.WINDOW.plusSeconds(1));
    attemptAndFail(throttle, email);
    assertThat(retryAfter(throttle, email)).isZero();
    for (int i = 0; i < 9; i++) {
      attemptAndFail(throttle, email);
    }
    assertThat(retryAfter(throttle, email)).isEqualTo(60);
    clock.advance(Duration.ofSeconds(60));
    assertThat(retryAfter(throttle, email)).isZero();
    assertThat(retryAfter(throttle, "other@example.org")).isZero();
  }

  @Test
  void attemptsInFlightCountAgainstTheBudget() {
    LoginThrottle throttle = new LoginThrottle(clock);
    String email = "parallel@example.org";
    java.util.List<LoginThrottle.Attempt> attempts = new java.util.ArrayList<>();
    for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) {
      attempts.add(throttle.begin(email));
    }
    assertThat(retryAfter(throttle, email)).isEqualTo(1);
    attempts.get(0).release();
    attempts.set(0, throttle.begin(email));
    attempts.forEach(LoginThrottle.Attempt::failed);
    attempts.get(0).failed();
    assertThat(retryAfter(throttle, email)).isEqualTo(60);
    clock.advance(Duration.ofSeconds(60));
    LoginThrottle.Attempt probe = throttle.begin(email);
    assertThat(retryAfter(throttle, email)).as("one probe at a time").isEqualTo(1);
    probe.succeeded();
    probe.failed();
    assertThat(retryAfter(throttle, email)).isZero();
    assertThatNoException().isThrownBy(() -> throttle.begin(email).release());
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
      attemptAndFail(throttle, "x@example.org");
    }
    assertThatThrownBy(() -> throttle.begin("x@example.org"))
        .isInstanceOfSatisfying(
            ProblemException.class,
            e -> assertThat(e.headers().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("60"));
  }

  /** One failed attempt made only when the throttle admits it (the login flow). */
  private static void attemptAndFail(LoginThrottle throttle, String email) {
    throttle.begin(email).failed();
  }

  @Test
  void backOffClimbsTheFullSequenceAcrossTheRollingWindow() {
    LoginThrottle throttle = new LoginThrottle(clock);
    String email = "climber@example.org";
    for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) {
      attemptAndFail(throttle, email);
    }
    long[] expected = {60, 120, 240, 480, 960, 1920, 3600, 3600, 3600};
    for (int i = 0; i < expected.length; i++) {
      long seconds = retryAfter(throttle, email);
      assertThat(seconds).as("lock %d", i + 1).isEqualTo(expected[i]);
      clock.advance(Duration.ofSeconds(seconds));
      if (i < expected.length - 1) {
        attemptAndFail(throttle, email);
      }
    }
    assertThat(retryAfter(throttle, email)).isZero();
    throttle.begin(email).succeeded();
    for (int i = 0; i < LoginThrottle.MAX_FAILURES - 1; i++) {
      attemptAndFail(throttle, email);
    }
    assertThat(retryAfter(throttle, email)).isZero();
  }

  @Test
  void theEscalationLevelResetsOnlyAfterTheQuietPeriod() {
    LoginThrottle throttle = new LoginThrottle(clock);
    String email = "quiet@example.org";
    for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) {
      attemptAndFail(throttle, email);
    }
    clock.advance(Duration.ofMinutes(1));
    attemptAndFail(throttle, email);
    assertThat(retryAfter(throttle, email)).isEqualTo(120);
    clock.advance(LoginThrottle.QUIET_PERIOD.minusMinutes(1));
    attemptAndFail(throttle, email);
    assertThat(retryAfter(throttle, email)).isEqualTo(240);
    clock.advance(LoginThrottle.QUIET_PERIOD.plusSeconds(1));
    attemptAndFail(throttle, email);
    assertThat(retryAfter(throttle, email)).isZero();
  }

  @Test
  void recipientLimitsAreRollingWindowCounts() {
    RecipientMailLimiter limiter = new RecipientMailLimiter(clock);
    String email = "rolling@example.org";
    for (int i = 0; i < 5; i++) {
      assertThat(limiter.tryAcquire(MailKind.VERIFY_EMAIL, email)).as("mail %d", i + 1).isTrue();
      clock.advance(Duration.ofMinutes(10));
    }
    clock.advance(Duration.ofHours(4).plusMinutes(48).minusMinutes(10));
    assertThat(limiter.tryAcquire(MailKind.VERIFY_EMAIL, email)).as("6th mail").isFalse();
    clock.advance(Duration.ofHours(24).minus(Duration.ofHours(4).plusMinutes(48)));
    assertThat(limiter.tryAcquire(MailKind.VERIFY_EMAIL, email)).as("after 24 h").isTrue();
    assertThat(limiter.tryAcquire(MailKind.VERIFY_EMAIL, email)).as("within 10 min").isFalse();
    clock.advance(Duration.ofMinutes(10).minusSeconds(1));
    assertThat(limiter.tryAcquire(MailKind.VERIFY_EMAIL, email)).as("before 10 min").isFalse();
  }
}
