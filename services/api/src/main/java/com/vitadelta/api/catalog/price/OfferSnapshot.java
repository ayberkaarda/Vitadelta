package com.vitadelta.api.catalog.price;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * The fields of one offer that effective price resolution needs, detached from the persistence
 * context.
 *
 * @param price price in {@code currency} (the country currency), exact as stored
 */
public record OfferSnapshot(
    String retailer,
    BigDecimal price,
    String currency,
    boolean inStock,
    String url,
    Instant checkedAt) {

  public OfferSnapshot {
    Objects.requireNonNull(retailer, "retailer");
    Objects.requireNonNull(price, "price");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(url, "url");
    Objects.requireNonNull(checkedAt, "checkedAt");
  }
}
