package com.vitadelta.api.common.watch;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Read seam of the saved products and alert subscriptions for the product-bearing responses ({@code
 * POST /recommendations/query} items and {@code GET /products/{id}}). The module that owns the
 * {@code saved_product} and {@code alert_subscription} tables provides exactly one bean of this
 * type.
 *
 * <p>The callers ask once per response, for a signed-in caller only, after ranking, locking and
 * paging (never inside a cached ranking), with the product ids of the page and the response's
 * country. An implementation answers with one batched read-only query; a product without saved row
 * or subscription may be missing from the map (it is shown as {@link WatchState#NONE}).
 *
 * <p>Without such a bean, {@link WatchSeamConfig} registers a default that answers an empty map.
 */
@FunctionalInterface
public interface WatchStateReader {

  /**
   * The watch state of {@code productIds} for {@code userId} in {@code countryCode}.
   *
   * @return state per product id; ids without state may be absent
   */
  Map<UUID, WatchState> forProducts(UUID userId, String countryCode, Collection<UUID> productIds);
}
