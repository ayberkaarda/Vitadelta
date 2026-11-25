package com.vitadelta.api.alerts;

import com.vitadelta.api.common.health.OutboxStats;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Meters of the alerts module (section 11.2): outbox backlog gauges (pending, failed, dead letter;
 * refreshed after every processor and housekeeping run, so a scrape never queries the database),
 * {@code outbox.dead.letter} ({@code outbox_dead_letter_total} in the Prometheus format), alert
 * e-mails sent and failed, evaluator firings by trigger and housekeeping purges.
 */
@Component
public class AlertMetrics {

  /** Counter of events that became {@code DEAD_LETTER}. */
  public static final String DEAD_LETTER = "outbox.dead.letter";

  /** Gauges of the backlog. */
  public static final String PENDING = "vitadelta.outbox.pending";

  public static final String FAILED = "vitadelta.outbox.failed";
  public static final String DEAD_LETTERS_RETAINED = "vitadelta.outbox.dead.letters";

  /** Alert and cap e-mails, tag {@code type}. */
  public static final String EMAIL_SENT = "vitadelta.alerts.email.sent";

  public static final String EMAIL_FAILED = "vitadelta.alerts.email.failed";

  /** Alert e-mails not sent, tag {@code reason} (deleted, ineligible). */
  public static final String EMAIL_SKIPPED = "vitadelta.alerts.email.skipped";

  /** Firings of the evaluator, tag {@code trigger}. */
  public static final String FIRED = "vitadelta.alerts.fired";

  /** Firings skipped by the monthly cap. */
  public static final String CAPPED = "vitadelta.alerts.capped";

  /** Rows removed by the outbox housekeeping, tag {@code purge}. */
  public static final String PURGED = "vitadelta.housekeeping.alerts.purged";

  private final MeterRegistry registry;
  private final AtomicReference<OutboxStats> backlog = new AtomicReference<>(OutboxStats.EMPTY);

  public AlertMetrics(MeterRegistry registry) {
    this.registry = registry;
    Gauge.builder(PENDING, backlog, b -> b.get().pending())
        .description("Outbox events not processed yet")
        .register(registry);
    Gauge.builder(FAILED, backlog, b -> b.get().failed())
        .description("Pending outbox events with at least one failed attempt")
        .register(registry);
    Gauge.builder(DEAD_LETTERS_RETAINED, backlog, b -> b.get().deadLetter())
        .description("Retained dead-letter outbox events")
        .register(registry);
  }

  /** Publishes the latest backlog counts. */
  public void backlog(OutboxStats stats) {
    backlog.set(stats);
  }

  public void deadLetter() {
    counter(DEAD_LETTER, "Outbox events that exhausted their attempts").increment();
  }

  public void emailSent(NotificationType type) {
    registry.counter(EMAIL_SENT, "type", type.name()).increment();
  }

  public void emailFailed(NotificationType type) {
    registry.counter(EMAIL_FAILED, "type", type.name()).increment();
  }

  public void emailSkipped(OutboxOutcome outcome) {
    registry.counter(EMAIL_SKIPPED, "reason", outcome.name()).increment();
  }

  public void fired(AlertTrigger trigger) {
    registry.counter(FIRED, "trigger", trigger.name()).increment();
  }

  public void capped() {
    registry.counter(CAPPED).increment();
  }

  public void purged(String purge, int rows) {
    registry.counter(PURGED, "purge", purge).increment(rows);
  }

  private Counter counter(String name, String description) {
    return Counter.builder(name).description(description).register(registry);
  }
}
