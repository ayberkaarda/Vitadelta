package com.vitadelta.api.adversarial;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A UTC application clock that the adversarial tests move forward explicitly. */
public final class AdversaryClock extends Clock {

  private final AtomicReference<Instant> now;

  public AdversaryClock(Instant start) {
    this.now = new AtomicReference<>(start);
  }

  /** Moves the clock forward. */
  public void advance(Duration duration) {
    now.updateAndGet(instant -> instant.plus(duration));
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }

  @Override
  public Instant instant() {
    return now.get();
  }
}
