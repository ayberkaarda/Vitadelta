package com.vitadelta.api.identity;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import org.springframework.stereotype.Component;

/**
 * Per-recipient limit of auth e-mails (section 8.2), as rolling-window counts per kind and
 * normalized address: no e-mail when one was sent within the last {@link #SHORT_WINDOW}, and at
 * most {@value #DAILY_LIMIT} within the last {@link #DAILY_WINDOW}. A suppressed e-mail is simply
 * not queued; the endpoint still answers {@code 202}. Each key keeps at most {@value #DAILY_LIMIT}
 * timestamps; idle keys are evicted after one daily window. The windows follow the application
 * clock.
 */
@Component
public class RecipientMailLimiter {

  public static final Duration SHORT_WINDOW = Duration.ofMinutes(10);
  public static final Duration DAILY_WINDOW = Duration.ofHours(24);
  public static final int DAILY_LIMIT = 5;

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

  private final Cache<String, Deque<Instant>> sent =
      Caffeine.newBuilder().expireAfterAccess(DAILY_WINDOW).maximumSize(1_000_000).build();
  private final Clock clock;

  public RecipientMailLimiter(Clock clock) {
    this.clock = clock;
  }

  /**
   * Whether an e-mail of {@code kind} may be sent to {@code normalizedEmail} now (and counts it).
   */
  public boolean tryAcquire(MailKind kind, String normalizedEmail) {
    Deque<Instant> times =
        sent.get(kind.template() + ":" + normalizedEmail, key -> new ArrayDeque<>());
    Instant now = clock.instant();
    synchronized (times) {
      Instant dayStart = now.minus(DAILY_WINDOW);
      while (!times.isEmpty() && !times.peekFirst().isAfter(dayStart)) {
        times.pollFirst();
      }
      if (!times.isEmpty() && times.peekLast().isAfter(now.minus(SHORT_WINDOW))) {
        return false;
      }
      if (times.size() >= DAILY_LIMIT) {
        return false;
      }
      times.addLast(now);
      return true;
    }
  }
}
