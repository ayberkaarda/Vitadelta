package com.vitadelta.api.imports;

import com.vitadelta.api.catalog.CatalogWriteTransaction;
import com.vitadelta.api.catalog.Offer;
import com.vitadelta.api.catalog.OfferRepository;
import com.vitadelta.api.catalog.OfferValues;
import com.vitadelta.api.catalog.Product;
import com.vitadelta.api.catalog.ProductRepository;
import com.vitadelta.api.catalog.price.EffectivePrice;
import com.vitadelta.api.catalog.price.EffectivePriceResolver;
import com.vitadelta.api.catalog.price.OfferSnapshot;
import com.vitadelta.api.catalog.price.StockFilter;
import com.vitadelta.api.imports.OfferChangeListener.OfferPairChange;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Detects the (product, country) pairs whose effective offer state changes in one catalog write
 * (imports and seed runs): {@link #capture} records the state of every pair the write touches
 * before it writes, {@link #changes} compares it with the state afterwards. Both run inside the
 * same {@link CatalogWriteTransaction}, after the products are locked.
 */
@Component
public class OfferChangeTracker {

  /** The pairs a write touches and their effective state before it. */
  public static final class Before {
    private final Set<PairKey> pairs;
    private final Map<PairKey, EffectivePrice.State> states;
    private final Instant now;

    private Before(Set<PairKey> pairs, Map<PairKey, EffectivePrice.State> states, Instant now) {
      this.pairs = pairs;
      this.states = states;
      this.now = now;
    }
  }

  private final ProductRepository products;
  private final OfferRepository offers;
  private final EffectivePriceResolver resolver;

  public OfferChangeTracker(
      ProductRepository products, OfferRepository offers, EffectivePriceResolver resolver) {
    this.products = products;
    this.offers = offers;
    this.resolver = resolver;
  }

  /** Records the effective state of every pair of {@code offerRows} before they are written. */
  public Before capture(Collection<OfferValues> offerRows, Instant now) {
    Set<PairKey> pairs = new LinkedHashSet<>();
    offerRows.forEach(o -> pairs.add(new PairKey(o.productCode(), o.countryCode())));
    return new Before(pairs, states(pairs, now), now);
  }

  /**
   * The pairs whose effective state differs now from {@code before}, ordered by product code, then
   * country.
   */
  public List<OfferPairChange> changes(Before before) {
    Map<PairKey, EffectivePrice.State> after = states(before.pairs, before.now);
    Map<PairKey, OfferPairChange> changes =
        new TreeMap<>(
            Comparator.comparing(PairKey::productCode).thenComparing(PairKey::countryCode));
    for (PairKey pair : before.pairs) {
      EffectivePrice.State old = before.states.get(pair);
      EffectivePrice.State current = after.get(pair);
      if (!Objects.equals(old, current)) {
        UUID productId = products.findByCode(pair.productCode()).map(Product::getId).orElse(null);
        changes.put(
            pair,
            new OfferPairChange(productId, pair.productCode(), pair.countryCode(), old, current));
      }
    }
    return List.copyOf(changes.values());
  }

  /** Effective state of each pair (INCLUDE_OUT_OF_STOCK basis); pairs without offers are absent. */
  private Map<PairKey, EffectivePrice.State> states(Set<PairKey> pairs, Instant now) {
    Map<PairKey, EffectivePrice.State> states = new LinkedHashMap<>();
    Map<String, Optional<Product>> byCode = new LinkedHashMap<>();
    for (PairKey pair : pairs) {
      Optional<Product> product = byCode.computeIfAbsent(pair.productCode(), products::findByCode);
      if (product.isEmpty()) {
        continue;
      }
      List<OfferSnapshot> snapshots =
          offers.findByProductIdAndCountryCode(product.get().getId(), pair.countryCode()).stream()
              .map(OfferChangeTracker::snapshot)
              .toList();
      resolver
          .resolve(snapshots, StockFilter.INCLUDE_OUT_OF_STOCK, now)
          .price()
          .ifPresent(p -> states.put(pair, p.state()));
    }
    return states;
  }

  private static OfferSnapshot snapshot(Offer offer) {
    return new OfferSnapshot(
        offer.getRetailer(),
        offer.getPrice(),
        offer.getCurrency(),
        offer.isInStock(),
        offer.getUrl(),
        offer.getCheckedAt());
  }

  private record PairKey(String productCode, String countryCode) {}
}
