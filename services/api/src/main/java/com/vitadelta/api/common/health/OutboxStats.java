package com.vitadelta.api.common.health;

import java.time.Instant;

/**
 * Backlog of the event outbox (contract schema {@code OutboxStats}).
 *
 * @param pending events with {@code processed_at IS NULL} (claimed or not)
 * @param failed pending events with {@code attempts > 0}
 * @param deadLetter retained events with outcome {@code DEAD_LETTER}
 * @param oldestPendingAt {@code created_at} of the oldest pending event, {@code null} when none
 */
public record OutboxStats(long pending, long failed, long deadLetter, Instant oldestPendingAt) {

  /** No event at all: what an application without an outbox reports. */
  public static final OutboxStats EMPTY = new OutboxStats(0, 0, 0, null);

  public OutboxStats {
    if (pending < 0 || failed < 0 || deadLetter < 0 || failed > pending) {
      throw new IllegalArgumentException(
          "invalid outbox counts: pending="
              + pending
              + ", failed="
              + failed
              + ", deadLetter="
              + deadLetter);
    }
  }
}
