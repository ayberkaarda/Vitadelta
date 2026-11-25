package com.vitadelta.api.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitadelta.api.common.problem.ProblemCode;
import com.vitadelta.api.common.problem.ProblemException;
import io.github.bucket4j.TimeMeter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

  /** A manually advanced clock. */
  static final class ManualTime implements TimeMeter {
    final AtomicLong nanos = new AtomicLong();

    @Override
    public long currentTimeNanos() {
      return nanos.get();
    }

    @Override
    public boolean isWallClockBased() {
      return false;
    }

    void advance(Duration duration) {
      nanos.addAndGet(duration.toNanos());
    }
  }

  @Test
  void limitsPerKeyAndRefillsOverThePeriod() {
    ManualTime time = new ManualTime();
    RateLimiter limiter = new RateLimiter(time);
    RateLimitPolicy policy = RateLimitPolicy.of("test-login", 2, Duration.ofMinutes(1));
    limiter.check(policy, "198.51.100.1");
    limiter.check(policy, "198.51.100.1");
    assertThatThrownBy(() -> limiter.check(policy, "198.51.100.1"))
        .isInstanceOfSatisfying(
            ProblemException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ProblemCode.RATE_LIMITED);
              assertThat(e.status().value()).isEqualTo(429);
              long retryAfter = Long.parseLong(e.headers().getFirst("Retry-After"));
              assertThat(retryAfter).isBetween(1L, 60L);
            });
    assertThat(limiter.tryConsume(policy, "198.51.100.2")).isTrue();
    time.advance(Duration.ofSeconds(31));
    assertThat(limiter.tryConsume(policy, "198.51.100.1")).isTrue();
    assertThat(limiter.tryConsume(policy, "198.51.100.1")).isFalse();
    time.advance(Duration.ofMinutes(1));
    assertThat(limiter.tryConsume(policy, "198.51.100.1")).isTrue();
  }

  @Test
  void everyRefusalIsCountedPerPolicyWithoutTheKey() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    RateLimiter limiter = new RateLimiter(new ManualTime(), meters);
    RateLimitPolicy policy = RateLimitPolicy.of("test-metric", 1, Duration.ofHours(1));
    limiter.check(policy, "198.51.100.7");
    assertThat(meters.find(RateLimiter.REJECTED_METRIC).counters()).isEmpty();
    assertThatThrownBy(() -> limiter.check(policy, "198.51.100.7"))
        .isInstanceOf(ProblemException.class);
    assertThat(limiter.tryConsume(policy, "198.51.100.7")).isFalse();
    Counter rejected =
        meters.get(RateLimiter.REJECTED_METRIC).tag("policy", "test-metric").counter();
    assertThat(rejected.count()).isEqualTo(2);
    assertThat(rejected.getId().getTags()).hasSize(1);
    assertThat(meters.getMeters().toString()).doesNotContain("198.51.100.7");
  }

  @Test
  void policiesDoNotShareBuckets() {
    RateLimiter limiter = new RateLimiter(new ManualTime());
    RateLimitPolicy a = RateLimitPolicy.of("a", 1, Duration.ofHours(1));
    RateLimitPolicy b = RateLimitPolicy.of("b", 1, Duration.ofHours(1));
    assertThat(limiter.tryConsume(a, "k")).isTrue();
    assertThat(limiter.tryConsume(b, "k")).isTrue();
    assertThat(limiter.tryConsume(a, "k")).isFalse();
  }

  @Test
  void specifiedPoliciesMatchTheSpecification() {
    assertThat(RateLimitPolicy.LOGIN_PER_IP.capacity()).isEqualTo(10);
    assertThat(RateLimitPolicy.LOGIN_PER_IP.period()).isEqualTo(Duration.ofMinutes(1));
    assertThat(RateLimitPolicy.REGISTER_PER_IP.capacity()).isEqualTo(5);
    assertThat(RateLimitPolicy.FORGOT_RESEND_PER_IP.period()).isEqualTo(Duration.ofHours(1));
    assertThat(RateLimitPolicy.VERIFY_RESET_PER_IP.capacity()).isEqualTo(10);
    assertThat(RateLimitPolicy.PROMO_PER_IP.capacity()).isEqualTo(10);
    assertThat(RateLimitPolicy.PROMO_PER_USER.capacity()).isEqualTo(5);
    assertThat(RateLimitPolicy.ANONYMOUS_QUERY_PER_IP.period()).isEqualTo(Duration.ofDays(1));
    assertThat(RateLimitPolicy.ANONYMOUS_BROWSE_PER_IP.capacity()).isEqualTo(120);
    assertThat(RateLimitPolicy.REFRESH_PER_IP.capacity()).isEqualTo(60);
    assertThatThrownBy(() -> RateLimitPolicy.of("x", 0, Duration.ofMinutes(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RateLimitPolicy.of("x", 1, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void defaultLimiterUsesTheSystemClock() {
    RateLimiter limiter = new RateLimiter();
    RateLimitPolicy policy = RateLimitPolicy.of("system", 1, Duration.ofDays(1));
    assertThat(limiter.tryConsume(policy, "k")).isTrue();
    assertThatThrownBy(() -> limiter.check(policy, "k"))
        .isInstanceOfSatisfying(
            ProblemException.class,
            e ->
                assertThat(Long.parseLong(e.headers().getFirst("Retry-After")))
                    .isGreaterThan(TimeUnit.HOURS.toSeconds(23)));
  }
}
