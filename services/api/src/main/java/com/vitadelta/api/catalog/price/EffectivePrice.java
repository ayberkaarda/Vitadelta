package com.vitadelta.api.catalog.price;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The effective price of a product in one country under one stock filter (section 7.4).
 *
 * @param price the selected offer's price (exact, native currency)
 * @param outOfStock true when no offer is in stock and an out-of-stock offer was selected
 * @param priceStale true when the selection is out of stock or the selected offer was checked more
 *     than {@code price_stale_after_days} days ago
 * @param selected the selected offer, whose retailer and url are shown with the product
 */
public record EffectivePrice(
    BigDecimal price,
    String currency,
    boolean outOfStock,
    boolean priceStale,
    OfferSnapshot selected) {

  public EffectivePrice {
    Objects.requireNonNull(price, "price");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(selected, "selected");
  }

  /**
   * The state that decides whether a (product, country) pair changed between two offer sets ({@code
   * OFFERS_CHANGED}, offers CSV contract): price, stock state and the selected offer's retailer and
   * url.
   */
  public State state() {
    return new State(price.stripTrailingZeros(), !outOfStock, selected.retailer(), selected.url());
  }

  /** Comparable effective state of a pair; the price is normalized so 2.0 equals 2.00. */
  public record State(BigDecimal price, boolean inStock, String retailer, String url) {}
}
