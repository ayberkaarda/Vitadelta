package com.vitadelta.api.catalog;

import com.vitadelta.api.catalog.unit.Unit;
import com.vitadelta.api.catalog.unit.UnitAttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/** Primary key of {@link UnitConversion}. */
@Embeddable
public class UnitConversionId implements Serializable {

  private static final long serialVersionUID = 1L;

  @Column(name = "nutrient_code", length = 40)
  private String nutrientCode;

  @Convert(converter = UnitAttributeConverter.class)
  @Column(name = "from_unit", length = 8)
  private Unit fromUnit;

  @Convert(converter = UnitAttributeConverter.class)
  @Column(name = "to_unit", length = 8)
  private Unit toUnit;

  @Column(name = "form_code", length = 40)
  private String formCode;

  protected UnitConversionId() {}

  public UnitConversionId(String nutrientCode, Unit fromUnit, Unit toUnit, String formCode) {
    this.nutrientCode = nutrientCode;
    this.fromUnit = fromUnit;
    this.toUnit = toUnit;
    this.formCode = formCode;
  }

  public String getNutrientCode() {
    return nutrientCode;
  }

  public Unit getFromUnit() {
    return fromUnit;
  }

  public Unit getToUnit() {
    return toUnit;
  }

  public String getFormCode() {
    return formCode;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof UnitConversionId that
        && Objects.equals(nutrientCode, that.nutrientCode)
        && fromUnit == that.fromUnit
        && toUnit == that.toUnit
        && Objects.equals(formCode, that.formCode);
  }

  @Override
  public int hashCode() {
    return Objects.hash(nutrientCode, fromUnit, toUnit, formCode);
  }
}
