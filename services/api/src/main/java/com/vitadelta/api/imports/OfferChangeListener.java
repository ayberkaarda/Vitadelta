package com.vitadelta.api.imports;

import com.vitadelta.api.catalog.price.EffectivePrice;
import java.util.List;
import java.util.UUID;

/**
 * Seam for the {@code OFFERS_CHANGED} outbox events (sections 5.3 and 8.6, Phase 5).
 *
 * <p>After a successful import has written its offers, {@link #offersChanged(List)} is called once
 * with every (product, country) pair whose effective state (section 7.4 on the {@code
 * INCLUDE_OUT_OF_STOCK} basis: price, stock state, selected retailer and url) differs before and
 * after the import. It runs inside the import's write transaction, before the catalog version bump,
 * so outbox rows written by the listener commit or roll back together with the offers. It is not
 * called when no pair changed. The imports package registers a listener that does nothing unless
 * another bean of this type exists.
 */
public interface OfferChangeListener {

  /**
   * One changed pair.
   *
   * @param before the effective state before the import, {@code null} when the pair had no offer
   * @param after the effective state after the import, {@code null} when the pair has no offer
   */
  record OfferPairChange(
      UUID productId,
      String productCode,
      String countryCode,
      EffectivePrice.State before,
      EffectivePrice.State after) {}

  /** Called with the changed pairs of one import, ordered by product code, then country. */
  void offersChanged(List<OfferPairChange> changes);
}
