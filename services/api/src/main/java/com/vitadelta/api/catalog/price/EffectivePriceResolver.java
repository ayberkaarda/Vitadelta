package com.vitadelta.api.catalog.price;

import com.vitadelta.api.config.properties.CatalogProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Effective price resolution (section 7.4): a pure function of the offers, the stock filter and the
 * time, which is taken from the injected {@link Clock} or passed explicitly.
 *
 * <ul>
 *   <li>{@link StockFilter#IN_STOCK_ONLY}: the cheapest in-stock offer; without one the product is
 *       excluded ({@code NO_STOCK} when offers exist, {@code NO_OFFER} otherwise).
 *   <li>{@link StockFilter#INCLUDE_OUT_OF_STOCK}: the cheapest in-stock offer, otherwise the
 *       cheapest out-of-stock offer with {@code outOfStock} and {@code priceStale} set; without any
 *       offer the product is excluded ({@code NO_OFFER}).
 *   <li>Equal prices: the most recent {@code checked_at} wins, then the lowest retailer in binary
 *       order (unsigned UTF-8 bytes, as {@code COLLATE "C"}, see {@link #compareBinary}); retailers
 *       are unique per pair, so the order is total.
 *   <li>{@code priceStale} is also set when the selected offer was checked more than {@code
 *       vitadelta.catalog.price-stale-after-days} (default 30) days before now.
 * </ul>
 */
@Component
public class EffectivePriceResolver {

  /** Selection and display order of offers: price, newest check, retailer. */
  public static final Comparator<OfferSnapshot> SELECTION_ORDER =
      Comparator.comparing(OfferSnapshot::price)
          .thenComparing(OfferSnapshot::checkedAt, Comparator.reverseOrder())
          .thenComparing(OfferSnapshot::retailer, EffectivePriceResolver::compareBinary);

  /**
   * Binary order of two strings as PostgreSQL {@code COLLATE "C"} sorts them in a UTF-8 database:
   * unsigned UTF-8 bytes, which is the order of the Unicode code points. It differs from {@link
   * String#compareTo} (UTF-16 code units) for characters above U+FFFF against U+E000 to U+FFFF.
   */
  public static int compareBinary(String a, String b) {
    int i = 0;
    int j = 0;
    while (i < a.length() && j < b.length()) {
      int ca = a.codePointAt(i);
      int cb = b.codePointAt(j);
      if (ca != cb) {
        return Integer.compare(ca, cb);
      }
      i += Character.charCount(ca);
      j += Character.charCount(cb);
    }
    return Integer.compare(a.length() - i, b.length() - j);
  }

  private final Clock clock;
  private final Duration staleAfter;

  public EffectivePriceResolver(Clock clock, CatalogProperties properties) {
    this.clock = clock;
    this.staleAfter = Duration.ofDays(properties.priceStaleAfterDays());
  }

  /** Resolves the effective price of one product's offers in one country at the current time. */
  public PriceResolution resolve(Collection<OfferSnapshot> offers, StockFilter filter) {
    return resolve(offers, filter, clock.instant());
  }

  /** Resolves the effective price of one product's offers in one country at {@code now}. */
  public PriceResolution resolve(
      Collection<OfferSnapshot> offers, StockFilter filter, Instant now) {
    if (offers.isEmpty()) {
      return PriceResolution.excluded(PriceResolution.Exclusion.NO_OFFER);
    }
    Optional<OfferSnapshot> inStock = cheapest(offers, true);
    if (inStock.isPresent()) {
      OfferSnapshot offer = inStock.get();
      return PriceResolution.priced(
          new EffectivePrice(offer.price(), offer.currency(), false, isStale(offer, now), offer));
    }
    if (filter == StockFilter.IN_STOCK_ONLY) {
      return PriceResolution.excluded(PriceResolution.Exclusion.NO_STOCK);
    }
    OfferSnapshot offer = cheapest(offers, false).orElseThrow();
    return PriceResolution.priced(
        new EffectivePrice(offer.price(), offer.currency(), true, true, offer));
  }

  /** Whether {@code offer} was checked more than the stale period before {@code now}. */
  public boolean isStale(OfferSnapshot offer, Instant now) {
    return offer.checkedAt().isBefore(now.minus(staleAfter));
  }

  /** The current time of the resolver's clock. */
  public Instant now() {
    return clock.instant();
  }

  /** The offers in display order: price ascending, newest check first, retailer ascending. */
  public static List<OfferSnapshot> displayOrder(Collection<OfferSnapshot> offers) {
    return offers.stream().sorted(SELECTION_ORDER).toList();
  }

  private static Optional<OfferSnapshot> cheapest(
      Collection<OfferSnapshot> offers, boolean inStock) {
    return offers.stream().filter(o -> o.inStock() == inStock).min(SELECTION_ORDER);
  }
}
