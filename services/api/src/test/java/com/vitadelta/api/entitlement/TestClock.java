package com.vitadelta.api.entitlement;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A UTC clock that the entitlement and billing tests move explicitly. */
public final class TestClock extends Clock {

  private final AtomicReference<Instant> now;

  public TestClock(Instant start) {
    this.now = new AtomicReference<>(start);
  }

  public void advance(Duration duration) {
    now.updateAndGet(instant -> instant.plus(duration));
  }

  public void set(Instant instant) {
    now.set(instant);
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
