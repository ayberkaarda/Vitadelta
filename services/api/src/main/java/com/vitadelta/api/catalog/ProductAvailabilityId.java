package com.vitadelta.api.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Primary key of {@link ProductAvailability}. */
@Embeddable
public class ProductAvailabilityId implements Serializable {

  private static final long serialVersionUID = 1L;

  @Column(name = "product_id")
  private UUID productId;

  @Column(name = "country_code", length = 2)
  private String countryCode;

  protected ProductAvailabilityId() {}

  public ProductAvailabilityId(UUID productId, String countryCode) {
    this.productId = productId;
    this.countryCode = countryCode;
  }

  public UUID getProductId() {
    return productId;
  }

  public String getCountryCode() {
    return countryCode;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ProductAvailabilityId that
        && Objects.equals(productId, that.productId)
        && Objects.equals(countryCode, that.countryCode);
  }

  @Override
  public int hashCode() {
    return Objects.hash(productId, countryCode);
  }
}
