package com.vitadelta.api.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/** Primary key of {@link ReferenceValue}. */
@Embeddable
public class ReferenceValueId implements Serializable {

  private static final long serialVersionUID = 1L;

  @Column(name = "reference_set_code", length = 40)
  private String referenceSetCode;

  @Column(name = "nutrient_code", length = 40)
  private String nutrientCode;

  protected ReferenceValueId() {}

  public ReferenceValueId(String referenceSetCode, String nutrientCode) {
    this.referenceSetCode = referenceSetCode;
    this.nutrientCode = nutrientCode;
  }

  public String getReferenceSetCode() {
    return referenceSetCode;
  }

  public String getNutrientCode() {
    return nutrientCode;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ReferenceValueId that
        && Objects.equals(referenceSetCode, that.referenceSetCode)
        && Objects.equals(nutrientCode, that.nutrientCode);
  }

  @Override
  public int hashCode() {
    return Objects.hash(referenceSetCode, nutrientCode);
  }
}
