package com.vitadelta.api.catalog.read;

import com.vitadelta.api.catalog.read.CatalogViews.CatalogProduct;
import com.vitadelta.api.catalog.read.CatalogViews.CountryView;
import com.vitadelta.api.catalog.read.CatalogViews.NutrientView;
import com.vitadelta.api.catalog.read.CatalogViews.ReferenceAmount;
import com.vitadelta.api.catalog.read.CatalogViews.UpperLimitView;
import java.util.List;
import java.util.Objects;

/**
 * Everything a scoring request needs for one country, read in one consistent snapshot at {@code
 * catalogVersion} (section 6.1). Built by {@link CatalogReadService#loadCountry(String)}; it holds
 * no persistence state.
 *
 * @param nutrients the whole nutrient registry, ordered by sort order then code (the scoring
 *     registry block must cover every code of the request)
 * @param referenceValues the reference values of the country's reference set, by nutrient code
 * @param upperLimits the rows of the country's UL set including exempt rows, by nutrient then form
 * @param products every product in market (AVAILABLE) in the country, by id, each with its offers
 *     in the country (possibly none) and its nutrients in canonical units
 */
public record CountryCatalog(
    long catalogVersion,
    CountryView country,
    List<NutrientView> nutrients,
    List<ReferenceAmount> referenceValues,
    List<UpperLimitView> upperLimits,
    List<CatalogProduct> products) {

  public CountryCatalog {
    Objects.requireNonNull(country, "country");
    nutrients = List.copyOf(nutrients);
    referenceValues = List.copyOf(referenceValues);
    upperLimits = List.copyOf(upperLimits);
    products = List.copyOf(products);
  }

  /**
   * The market profile of the country at {@link #catalogVersion()} (section 6.3): every in-market
   * product, independent of offers and stock.
   */
  public MarketProfile marketProfile() {
    return MarketProfile.of(catalogVersion, country.code(), products);
  }
}
