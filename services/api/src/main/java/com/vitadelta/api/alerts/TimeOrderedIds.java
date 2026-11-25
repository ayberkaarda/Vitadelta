package com.vitadelta.api.alerts;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Time-ordered notification ids (RFC 9562 version 7): 48 bits of Unix milliseconds, a 12-bit
 * counter and 62 random bits. Ids of one process increase strictly (the counter carries into the
 * millisecond when needed), and PostgreSQL orders {@code uuid} values by their bytes, so the inbox
 * order {@code created_at desc, id desc} lists notifications created at the same instant (one
 * outbox run, a fixed test clock) newest first. The time part is the system clock, not the
 * application clock: it only orders ids and carries no meaning.
 */
final class TimeOrderedIds {

  private static final SecureRandom RANDOM = new SecureRandom();

  private static final TimeOrderedIds SYSTEM = new TimeOrderedIds(System::currentTimeMillis);

  private static final int COUNTER_BITS = 12;

  private static final long COUNTER_MASK = (1L << COUNTER_BITS) - 1;

  private final LongSupplier millis;

  /** Last issued value of (milliseconds << 12 | counter). */
  private long last;

  TimeOrderedIds(LongSupplier millis) {
    this.millis = millis;
  }

  /** A new id of the process-wide generator. */
  static UUID next() {
    return SYSTEM.nextId();
  }

  synchronized UUID nextId() {
    long candidate = millis.getAsLong() << COUNTER_BITS;
    last = Math.max(candidate, last + 1);
    long timestamp = last >>> COUNTER_BITS;
    long counter = last & COUNTER_MASK;
    long most = (timestamp << 16) | (0x7L << 12) | counter;
    long least = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
    return new UUID(most, least);
  }
}
