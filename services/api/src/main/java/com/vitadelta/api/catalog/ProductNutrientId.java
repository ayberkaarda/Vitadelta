package com.vitadelta.api.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Primary key of {@link ProductNutrient}. */
@Embeddable
public class ProductNutrientId implements Serializable {

  private static final long serialVersionUID = 1L;

  @Column(name = "product_id")
  private UUID productId;

  @Column(name = "nutrient_code", length = 40)
  private String nutrientCode;

  protected ProductNutrientId() {}

  public ProductNutrientId(UUID productId, String nutrientCode) {
    this.productId = productId;
    this.nutrientCode = nutrientCode;
  }

  public UUID getProductId() {
    return productId;
  }

  public String getNutrientCode() {
    return nutrientCode;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ProductNutrientId that
        && Objects.equals(productId, that.productId)
        && Objects.equals(nutrientCode, that.nutrientCode);
  }

  @Override
  public int hashCode() {
    return Objects.hash(productId, nutrientCode);
  }
}
