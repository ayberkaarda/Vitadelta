package com.vitadelta.api.catalog.price;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitadelta.api.config.properties.CatalogProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Effective price resolution (section 7.4) with a fixed clock. */
class EffectivePriceResolverTest {

  private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");

  private final EffectivePriceResolver resolver =
      new EffectivePriceResolver(Clock.fixed(NOW, ZoneOffset.UTC), new CatalogProperties(30, 1, 4));

  private static OfferSnapshot offer(String retailer, String price, boolean inStock, Instant at) {
    return new OfferSnapshot(
        retailer,
        new BigDecimal(price),
        "TRY",
        inStock,
        "https://shop.example.com/" + retailer,
        at);
  }

  private static Instant daysAgo(long days) {
    return NOW.minus(Duration.ofDays(days));
  }

  @Test
  void noOffersExcludeTheProductUnderBothFilters() {
    for (StockFilter filter : StockFilter.values()) {
      PriceResolution resolution = resolver.resolve(List.of(), filter);
      assertThat(resolution.isPriced()).isFalse();
      assertThat(resolution.exclusion()).isEqualTo(PriceResolution.Exclusion.NO_OFFER);
      assertThat(resolution.price()).isEmpty();
    }
  }

  @Test
  void theRetailerTieBreakIsBinaryUtf8OrderLikeCollateC() {
    // U+E000 sorts before U+10000 in UTF-8 bytes (EE 80 80 < F0 90 80 80) and in code points,
    // but after it in UTF-16 code units (E000 > D800).
    String privateUse = " Shop";
    String supplementary = new String(Character.toChars(0x10000)) + " Shop";
    Instant at = daysAgo(1);
    for (List<OfferSnapshot> offers :
        List.of(
            List.of(offer(supplementary, "10.00", true, at), offer(privateUse, "10.00", true, at)),
            List.of(
                offer(privateUse, "10.00", true, at), offer(supplementary, "10.00", true, at)))) {
      assertThat(
              resolver.resolve(offers, StockFilter.IN_STOCK_ONLY).price().orElseThrow().selected())
          .extracting(OfferSnapshot::retailer)
          .isEqualTo(privateUse);
      assertThat(EffectivePriceResolver.displayOrder(offers))
          .extracting(OfferSnapshot::retailer)
          .containsExactly(privateUse, supplementary);
    }
    assertThat(EffectivePriceResolver.compareBinary("a", "ab")).isNegative();
    assertThat(EffectivePriceResolver.compareBinary("ab", "a")).isPositive();
    assertThat(EffectivePriceResolver.compareBinary("Shop", "Shop")).isZero();
    assertThat(EffectivePriceResolver.compareBinary("B", "a")).isNegative();
  }

  @Test
  void theCheapestInStockOfferWinsUnderBothFilters() {
    List<OfferSnapshot> offers =
        List.of(
            offer("A", "12.00", true, daysAgo(1)),
            offer("B", "10.00", true, daysAgo(2)),
            offer("C", "5.00", false, daysAgo(1)));
    for (StockFilter filter : StockFilter.values()) {
      EffectivePrice price = resolver.resolve(offers, filter).price().orElseThrow();
      assertThat(price.price()).isEqualByComparingTo("10.00");
      assertThat(price.selected().retailer()).isEqualTo("B");
      assertThat(price.selected().url()).isEqualTo("https://shop.example.com/B");
      assertThat(price.outOfStock()).isFalse();
      assertThat(price.priceStale()).isFalse();
      assertThat(price.currency()).isEqualTo("TRY");
    }
  }

  @Test
  void onlyOutOfStockOffersAreExcludedInStockOnlyAndStaleOtherwise() {
    List<OfferSnapshot> offers =
        List.of(offer("A", "9.00", false, daysAgo(1)), offer("B", "8.00", false, daysAgo(1)));
    PriceResolution inStockOnly = resolver.resolve(offers, StockFilter.IN_STOCK_ONLY);
    assertThat(inStockOnly.exclusion()).isEqualTo(PriceResolution.Exclusion.NO_STOCK);
    EffectivePrice price =
        resolver.resolve(offers, StockFilter.INCLUDE_OUT_OF_STOCK).price().orElseThrow();
    assertThat(price.price()).isEqualByComparingTo("8.00");
    assertThat(price.selected().retailer()).isEqualTo("B");
    assertThat(price.outOfStock()).isTrue();
    assertThat(price.priceStale()).isTrue();
  }

  @Test
  void equalPricesPreferTheMostRecentCheckThenTheLowestRetailerInBinaryOrder() {
    OfferSnapshot older = offer("Alpha", "10.00", true, daysAgo(3));
    OfferSnapshot newerZeta = offer("Zeta", "10.0", true, daysAgo(1));
    OfferSnapshot newerBeta = offer("beta", "10", true, daysAgo(1));
    OfferSnapshot newerUpper = offer("Beta", "10.00", true, daysAgo(1));
    List<OfferSnapshot> offers = List.of(older, newerZeta, newerBeta, newerUpper);
    EffectivePrice price =
        resolver.resolve(offers, StockFilter.IN_STOCK_ONLY).price().orElseThrow();
    // "Beta" < "Zeta" < "beta" in binary order; the older offer loses despite "Alpha".
    assertThat(price.selected().retailer()).isEqualTo("Beta");
    assertThat(resolver.resolve(List.of(older, newerZeta), StockFilter.IN_STOCK_ONLY))
        .extracting(r -> r.effective().selected().retailer())
        .isEqualTo("Zeta");
    for (int i = 0; i < 4; i++) {
      List<OfferSnapshot> rotated =
          List.of(
              offers.get(i % 4),
              offers.get((i + 1) % 4),
              offers.get((i + 2) % 4),
              offers.get((i + 3) % 4));
      assertThat(resolver.resolve(rotated, StockFilter.IN_STOCK_ONLY).effective().selected())
          .isEqualTo(newerUpper);
    }
    assertThat(EffectivePriceResolver.displayOrder(offers))
        .containsExactly(newerUpper, newerZeta, newerBeta, older);
  }

  @Test
  void anInStockOfferOlderThanThirtyDaysIsStale() {
    OfferSnapshot exactlyThirty = offer("A", "10.00", true, daysAgo(30));
    OfferSnapshot older = offer("B", "10.00", true, daysAgo(30).minusSeconds(1));
    assertThat(resolver.resolve(List.of(exactlyThirty), StockFilter.IN_STOCK_ONLY).effective())
        .extracting(EffectivePrice::priceStale)
        .isEqualTo(false);
    EffectivePrice stale = resolver.resolve(List.of(older), StockFilter.IN_STOCK_ONLY).effective();
    assertThat(stale.priceStale()).isTrue();
    assertThat(stale.outOfStock()).isFalse();
    assertThat(resolver.isStale(older, NOW)).isTrue();
    assertThat(resolver.isStale(exactlyThirty, NOW)).isFalse();
    assertThat(resolver.now()).isEqualTo(NOW);
    EffectivePriceResolver strict =
        new EffectivePriceResolver(
            Clock.fixed(NOW, ZoneOffset.UTC), new CatalogProperties(1, 1, 1));
    assertThat(
            strict.resolve(
                List.of(offer("C", "1.00", true, daysAgo(2))), StockFilter.IN_STOCK_ONLY))
        .extracting(r -> r.effective().priceStale())
        .isEqualTo(true);
  }

  @Test
  void theEffectiveStateComparesPricesNumerically() {
    EffectivePrice a =
        resolver
            .resolve(List.of(offer("A", "10.0", true, daysAgo(1))), StockFilter.IN_STOCK_ONLY)
            .effective();
    EffectivePrice b =
        resolver
            .resolve(List.of(offer("A", "10.00", true, daysAgo(5))), StockFilter.IN_STOCK_ONLY)
            .effective();
    assertThat(a.state()).isEqualTo(b.state());
    EffectivePrice outOfStock =
        resolver
            .resolve(
                List.of(offer("A", "10.00", false, daysAgo(1))), StockFilter.INCLUDE_OUT_OF_STOCK)
            .effective();
    assertThat(outOfStock.state()).isNotEqualTo(a.state());
    assertThat(outOfStock.state().inStock()).isFalse();
  }

  @Test
  void invalidValuesAreRejected() {
    assertThatThrownBy(() -> new PriceResolution(null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CatalogProperties(0, 1, 1))
        .hasMessageContaining("price-stale-after-days");
    assertThatThrownBy(() -> new CatalogProperties(30, 0, 1))
        .hasMessageContaining("import-threads");
    assertThatThrownBy(() -> offer(null, "1", true, NOW)).isInstanceOf(NullPointerException.class);
  }
}
