package com.vitadelta.api.catalog;

import java.math.BigDecimal;
import java.time.Instant;

/** Validated values of one offer; the natural key is (product, country, retailer). */
public record OfferValues(
    String productCode,
    String countryCode,
    String retailer,
    BigDecimal price,
    String currency,
    boolean inStock,
    String url,
    Instant checkedAt) {}
