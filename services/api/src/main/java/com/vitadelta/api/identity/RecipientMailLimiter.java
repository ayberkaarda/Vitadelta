package com.vitadelta.api.identity;

import com.vitadelta.api.common.ratelimit.RateLimitPolicy;
import com.vitadelta.api.common.ratelimit.RateLimiter;
import io.github.bucket4j.TimeMeter;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Per-recipient limit of auth e-mails (section 8.2): at most one e-mail per kind per 10 minutes and
 * five per kind per 24 hours for a normalized address. A suppressed e-mail is simply not queued;
 * the endpoint still answers {@code 202}. The buckets follow the application clock.
 */
@Component
public class RecipientMailLimiter {

  /** The rate-limited auth e-mail kinds (template names). */
  public enum MailKind {
    VERIFY_EMAIL("verify-email"),
    ALREADY_REGISTERED("already-registered"),
    RESET_PASSWORD("reset-password");

    private final String template;

    MailKind(String template) {
      this.template = template;
    }

    public String template() {
      return template;
    }
  }

  private record Policies(RateLimitPolicy shortTerm, RateLimitPolicy daily) {}

  private final RateLimiter rateLimiter;
  private final Map<MailKind, Policies> policies = new EnumMap<>(MailKind.class);

  public RecipientMailLimiter(Clock clock) {
    this.rateLimiter = new RateLimiter(new ClockTimeMeter(clock));
    for (MailKind kind : MailKind.values()) {
      policies.put(
          kind,
          new Policies(
              RateLimitPolicy.of("mail-" + kind.template() + "-10m", 1, Duration.ofMinutes(10)),
              RateLimitPolicy.of("mail-" + kind.template() + "-24h", 5, Duration.ofHours(24))));
    }
  }

  /** Whether an e-mail of {@code kind} may be sent to {@code normalizedEmail} now. */
  public boolean tryAcquire(MailKind kind, String normalizedEmail) {
    Policies limits = policies.get(kind);
    return rateLimiter.tryConsume(limits.shortTerm(), normalizedEmail)
        && rateLimiter.tryConsume(limits.daily(), normalizedEmail);
  }

  /** Bucket4j time source backed by the application clock. */
  private record ClockTimeMeter(Clock clock) implements TimeMeter {

    @Override
    public long currentTimeNanos() {
      return TimeUnit.MILLISECONDS.toNanos(clock.millis());
    }

    @Override
    public boolean isWallClockBased() {
      return true;
    }
  }
}
