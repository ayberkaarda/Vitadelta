package com.vitadelta.api.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A UTC clock that tests move forward explicitly. */
final class MutableClock extends Clock {

  private final AtomicReference<Instant> now;

  MutableClock(Instant start) {
    this.now = new AtomicReference<>(start);
  }

  void advance(Duration duration) {
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
