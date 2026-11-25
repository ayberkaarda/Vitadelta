package com.vitadelta.api.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Version 7 notification ids that increase strictly in PostgreSQL's byte order. */
class TimeOrderedIdsTest {

  /** The 16 bytes of the id as unsigned hex, the order PostgreSQL compares {@code uuid} in. */
  private static String bytes(UUID id) {
    return HexFormat.of()
        .formatHex(
            java.nio.ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits())
                .array());
  }

  @Test
  void idsOfTheSameMillisecondAndOfABackwardClockStillIncrease() {
    AtomicLong millis = new AtomicLong(1_764_000_000_000L);
    TimeOrderedIds ids = new TimeOrderedIds(millis::get);
    List<String> issued = new ArrayList<>();
    for (int i = 0; i < 5000; i++) {
      if (i == 2500) {
        millis.addAndGet(-10);
      }
      UUID id = ids.nextId();
      assertThat(id.version()).isEqualTo(7);
      assertThat(id.variant()).isEqualTo(2);
      issued.add(bytes(id));
    }
    assertThat(issued).isSorted().doesNotHaveDuplicates();
  }

  @Test
  void theTimePartIsTheMillisecond() {
    TimeOrderedIds ids = new TimeOrderedIds(() -> 0x0123_4567_89ABL);
    assertThat(bytes(ids.nextId())).startsWith("0123456789ab7000");
    assertThat(bytes(ids.nextId())).startsWith("0123456789ab7001");
  }
}
