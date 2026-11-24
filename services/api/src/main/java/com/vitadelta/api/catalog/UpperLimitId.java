package com.vitadelta.api.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/** Primary key of {@link UpperLimit}. */
@Embeddable
public class UpperLimitId implements Serializable {

  private static final long serialVersionUID = 1L;

  @Column(name = "ul_set_code", length = 40)
  private String ulSetCode;

  @Column(name = "nutrient_code", length = 40)
  private String nutrientCode;

  @Column(name = "form_code", length = 40)
  private String formCode;

  protected UpperLimitId() {}

  public UpperLimitId(String ulSetCode, String nutrientCode, String formCode) {
    this.ulSetCode = ulSetCode;
    this.nutrientCode = nutrientCode;
    this.formCode = formCode;
  }

  public String getUlSetCode() {
    return ulSetCode;
  }

  public String getNutrientCode() {
    return nutrientCode;
  }

  public String getFormCode() {
    return formCode;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof UpperLimitId that
        && Objects.equals(ulSetCode, that.ulSetCode)
        && Objects.equals(nutrientCode, that.nutrientCode)
        && Objects.equals(formCode, that.formCode);
  }

  @Override
  public int hashCode() {
    return Objects.hash(ulSetCode, nutrientCode, formCode);
  }
}
