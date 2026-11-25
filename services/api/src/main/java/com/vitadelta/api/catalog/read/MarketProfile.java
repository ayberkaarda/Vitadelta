package com.vitadelta.api.catalog.read;

import com.vitadelta.api.catalog.read.CatalogViews.CatalogProduct;
import com.vitadelta.api.catalog.read.CatalogViews.ProductNutrientView;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The market profile of one country at one catalog version (section 6.3): every product in market
 * (AVAILABLE) in the country with its daily nutrient amounts, independent of the stock filter,
 * offers, required nutrients, weights and UL filters. Products are de-duplicated by id and ordered
 * by the id string (binary order); nutrients by code.
 */
public record MarketProfile(long catalogVersion, String countryCode, List<Product> products) {

  /** One in-market product. */
  public record Product(UUID productId, List<Nutrient> nutrients) {

    public Product {
      nutrients = List.copyOf(nutrients);
    }
  }

  /**
   * One nutrient of a product.
   *
   * @param amountPerDay canonical amount per serving x servings per day, exact
   */
  public record Nutrient(String code, BigDecimal amountPerDay) {}

  public MarketProfile {
    products = List.copyOf(products);
  }

  /** Builds the profile from in-market products; their offers are ignored. */
  public static MarketProfile of(
      long catalogVersion, String countryCode, Collection<CatalogProduct> inMarket) {
    Map<UUID, Product> byId = new LinkedHashMap<>();
    inMarket.stream()
        .sorted(Comparator.comparing(p -> p.id().toString()))
        .forEach(p ->
            byId.putIfAbsent(
                p.id(),
                new Product(
                    p.id(),
                    p.nutrients().stream()
                        .sorted(Comparator.comparing(ProductNutrientView::code))
                        .map(n -> new Nutrient(n.code(), n.amountPerDay(p.servingsPerDay())))
                        .toList())));
    return new MarketProfile(catalogVersion, countryCode, List.copyOf(byId.values()));
  }
}
