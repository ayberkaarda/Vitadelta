package com.vitadelta.api.catalog;

import java.math.BigDecimal;
import java.util.Objects;

/** Value comparison used by entity updates so that unchanged values never cause an UPDATE. */
final class Changes {

  private Changes() {}

  /** Whether two values differ; decimals are compared numerically (2.0 equals 2.00). */
  static boolean differ(Object current, Object next) {
    if (current instanceof BigDecimal a && next instanceof BigDecimal b) {
      return a.compareTo(b) != 0;
    }
    return !Objects.equals(current, next);
  }
}
