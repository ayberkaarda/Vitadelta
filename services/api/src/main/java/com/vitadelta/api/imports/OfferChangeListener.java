package com.vitadelta.api.imports;

import com.vitadelta.api.catalog.price.EffectivePrice;
import java.util.List;
import java.util.UUID;

/**
 * Seam for the {@code OFFERS_CHANGED} outbox events (sections 5.3 and 8.6).
 *
 * <p>After a successful import or seed run has written its offers, {@link #offersChanged(List,
 * long)} is called once with every (product, country) pair whose effective state (section 7.4 on
 * the {@code INCLUDE_OUT_OF_STOCK} basis: price, stock state, selected retailer and url) differs
 * before and after the write ({@link OfferChangeTracker}). It runs inside the write transaction,
 * right after the single catalog version bump, and receives the version that transaction commits
 * with; outbox rows written by the listener commit or roll back together with the offers. It is not
 * called when no pair changed (and a write that changes no catalog row bumps nothing). The imports
 * package registers a listener that does nothing unless another bean of this type exists.
 */
public interface OfferChangeListener {

  /**
   * One changed pair.
   *
   * @param before the effective state before the write, {@code null} when the pair had no offer
   * @param after the effective state after the write, {@code null} when the pair has no offer
   */
  record OfferPairChange(
      UUID productId,
      String productCode,
      String countryCode,
      EffectivePrice.State before,
      EffectivePrice.State after) {}

  /**
   * Called with the changed pairs of one write, ordered by product code, then country.
   *
   * @param catalogVersion the catalog version the write transaction commits with
   */
  void offersChanged(List<OfferPairChange> changes, long catalogVersion);
}
