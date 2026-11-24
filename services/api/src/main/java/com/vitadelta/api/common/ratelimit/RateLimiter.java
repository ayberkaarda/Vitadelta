package com.vitadelta.api.common.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.vitadelta.api.common.problem.ProblemCode;
import com.vitadelta.api.common.problem.ProblemException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
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
 */
@Component
public class RateLimiter {

  private final TimeMeter timeMeter;
  private final Map<String, Cache<String, Bucket>> buckets = new ConcurrentHashMap<>();

  @Autowired
  public RateLimiter() {
    this(TimeMeter.SYSTEM_NANOTIME);
  }

  /** A limiter on a custom time source (tests). */
  public RateLimiter(TimeMeter timeMeter) {
    this.timeMeter = Objects.requireNonNull(timeMeter, "timeMeter");
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
    return bucket.tryConsumeAndReturnRemaining(1);
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
