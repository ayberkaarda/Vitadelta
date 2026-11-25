package com.vitadelta.api.catalog.price;

import java.util.Optional;

/**
 * Outcome of effective price resolution for one product: either an {@link EffectivePrice} or the
 * reason the product is excluded.
 */
public record PriceResolution(EffectivePrice effective, Exclusion exclusion) {

  /** Why a product has no effective price. */
  public enum Exclusion {
    /** {@link StockFilter#IN_STOCK_ONLY}: offers exist but none is in stock ({@code noStock}). */
    NO_STOCK,
    /** The product has no offer in the country ({@code noOffer}). */
    NO_OFFER
  }

  public PriceResolution {
    if ((effective == null) == (exclusion == null)) {
      throw new IllegalArgumentException("exactly one of effective and exclusion must be set");
    }
  }

  public static PriceResolution priced(EffectivePrice effective) {
    return new PriceResolution(effective, null);
  }

  public static PriceResolution excluded(Exclusion exclusion) {
    return new PriceResolution(null, exclusion);
  }

  public boolean isPriced() {
    return effective != null;
  }

  public Optional<EffectivePrice> price() {
    return Optional.ofNullable(effective);
  }
}
