package com.vitadelta.api.catalog.seed;

import com.vitadelta.api.catalog.OfferValues;
import com.vitadelta.api.catalog.ProductImport;
import java.util.List;

/** Validated seed content: reference data, products (synthetic and real) and offers. */
public record SeedData(
    ReferenceData reference, List<ProductImport> products, List<OfferValues> offers) {

  public SeedData {
    products = List.copyOf(products);
    offers = List.copyOf(offers);
  }
}
