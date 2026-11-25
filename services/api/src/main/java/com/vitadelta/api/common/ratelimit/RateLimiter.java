package com.vitadelta.api.common.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.vitadelta.api.common.problem.ProblemCode;
import com.vitadelta.api.common.problem.ProblemException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * In-memory rate limits on Bucket4j (single API instance, ADR-0011). Buckets are kept per policy
 * and key in Caffeine caches and evicted after one idle period, so memory stays bounded by the
 * number of active keys.
 *
 * <pre>{@code
 * rateLimiter.check(RateLimitPolicy.LOGIN_PER_IP, clientIpResolver.resolve(request));
 * }</pre>
 *
 * Every refused request is counted in {@value #REJECTED_METRIC} with the tag {@code policy} (the
 * policy name, never the key: client IPs, e-mail addresses and user ids stay out of metrics).
 */
@Component
public class RateLimiter {

  /** Counter of requests refused by a rate limit, tag {@code policy}. */
  public static final String REJECTED_METRIC = "vitadelta.ratelimit.rejected";

  private final TimeMeter timeMeter;
  private final MeterRegistry meters;
  private final Map<String, Cache<String, Bucket>> buckets = new ConcurrentHashMap<>();

  @Autowired
  public RateLimiter(MeterRegistry meters) {
    this(TimeMeter.SYSTEM_NANOTIME, meters);
  }

  /** A limiter measured in Micrometer's global registry. */
  public RateLimiter() {
    this(TimeMeter.SYSTEM_NANOTIME);
  }

  /** A limiter on a custom time source (tests), measured in Micrometer's global registry. */
  public RateLimiter(TimeMeter timeMeter) {
    this(timeMeter, Metrics.globalRegistry);
  }

  /** A limiter on a custom time source measured in {@code meters}. */
  public RateLimiter(TimeMeter timeMeter, MeterRegistry meters) {
    this.timeMeter = Objects.requireNonNull(timeMeter, "timeMeter");
    this.meters = Objects.requireNonNull(meters, "meters");
  }

  /**
   * Consumes one token of {@code key}'s bucket.
   *
   * @throws ProblemException {@code 429 rate_limited} with {@code Retry-After} (seconds, at least
   *     1) when the bucket is empty
   */
  public void check(RateLimitPolicy policy, String key) {
    ConsumptionProbe probe = consume(policy, key);
    if (!probe.isConsumed()) {
      long seconds =
          Math.max(
              1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill() + 999_999_999L));
      throw ProblemException.of(ProblemCode.RATE_LIMITED, "Too many requests; retry later.")
          .withHeader(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
    }
  }

  /** Consumes one token if available; returns whether it was consumed. */
  public boolean tryConsume(RateLimitPolicy policy, String key) {
    return consume(policy, key).isConsumed();
  }

  private ConsumptionProbe consume(RateLimitPolicy policy, String key) {
    Objects.requireNonNull(key, "key");
    Cache<String, Bucket> cache =
        buckets.computeIfAbsent(policy.name(), name -> newCache(policy.period()));
    Bucket bucket = cache.get(key, k -> newBucket(policy));
    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
    if (!probe.isConsumed()) {
      meters.counter(REJECTED_METRIC, "policy", policy.name()).increment();
    }
    return probe;
  }

  private Cache<String, Bucket> newCache(Duration idle) {
    return Caffeine.newBuilder().expireAfterAccess(idle).maximumSize(1_000_000).build();
  }

  private Bucket newBucket(RateLimitPolicy policy) {
    return Bucket.builder()
        .addLimit(
            Bandwidth.builder()
                .capacity(policy.capacity())
                .refillGreedy(policy.capacity(), policy.period())
                .build())
        .withCustomTimePrecision(timeMeter)
        .build();
  }
}
