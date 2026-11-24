package com.vitadelta.api.catalog.price;

/** Which offers may set the effective price (section 7.4). */
public enum StockFilter {
  /** Only in-stock offers; a product without one is excluded. */
  IN_STOCK_ONLY,
  /** In-stock offers first, otherwise the cheapest out-of-stock offer (flagged stale). */
  INCLUDE_OUT_OF_STOCK
}
