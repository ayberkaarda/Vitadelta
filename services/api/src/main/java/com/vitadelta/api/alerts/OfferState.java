package com.vitadelta.api.alerts;

import com.vitadelta.api.catalog.price.EffectivePrice;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Effective-price state of a product in a country (section 7.4, {@code INCLUDE_OUT_OF_STOCK}) as
 * carried by {@code OFFERS_CHANGED} and copied into alert notifications (contract schema {@code
 * OfferState}). Every field is {@code null} when no offer exists.
 *
 * @param effectivePrice native price with the 2 decimals of {@code offer.price}
 */
public record OfferState(BigDecimal effectivePrice, Boolean inStock, String retailer, String url) {

  /** The state of a pair without any offer. */
  public static final OfferState NONE = new OfferState(null, null, null, null);

  /** The state of {@code state}, {@link #NONE} for {@code null}. */
  public static OfferState of(EffectivePrice.State state) {
    if (state == null) {
      return NONE;
    }
    return new OfferState(
        state.price().setScale(2, RoundingMode.HALF_EVEN),
        state.inStock(),
        state.retailer(),
        state.url());
  }

  /** Whether a price is present. */
  public boolean priced() {
    return effectivePrice != null;
  }
}
