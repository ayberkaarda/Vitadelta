package com.vitadelta.api.catalog.read;

/**
 * A country has more in-market products than a request may load (an operator configuration error:
 * the catalog outgrew {@code vitadelta.recommendation.max-products-per-country}).
 */
public class CatalogTooLargeException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String country;
  private final long products;
  private final int limit;

  public CatalogTooLargeException(String country, long products, int limit) {
    super(country + " has " + products + " in-market products, more than the limit of " + limit);
    this.country = country;
    this.products = products;
    this.limit = limit;
  }

  public String country() {
    return country;
  }

  public long products() {
    return products;
  }

  public int limit() {
    return limit;
  }
}
