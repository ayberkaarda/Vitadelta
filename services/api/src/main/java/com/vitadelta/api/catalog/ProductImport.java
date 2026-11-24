package com.vitadelta.api.catalog;

import java.util.List;

/**
 * One validated product of a products document: the nutrient set replaces the stored one,
 * availability is upserted per country and offers are upserted with replace-set per (product,
 * country) pair present in {@code offers}.
 */
public record ProductImport(
    ProductDetails details,
    List<ProductNutrientValues> nutrients,
    List<AvailabilityValues> availability,
    List<OfferValues> offers) {

  public ProductImport {
    nutrients = List.copyOf(nutrients);
    availability = List.copyOf(availability);
    offers = List.copyOf(offers);
  }
}
