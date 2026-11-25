package com.vitadelta.api.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Validated values of one offer; the natural key is (product, country, retailer).
 *
 * @param checkedAt the check time, truncated to microseconds (the precision of the {@code
 *     timestamptz} column), so a value read back from the database equals the imported one and an
 *     unchanged re-import is recognized as unchanged
 */
public record OfferValues(
    String productCode,
    String countryCode,
    String retailer,
    BigDecimal price,
    String currency,
    boolean inStock,
    String url,
    Instant checkedAt) {

  public OfferValues {
    checkedAt = checkedAt == null ? null : checkedAt.truncatedTo(ChronoUnit.MICROS);
  }
}
