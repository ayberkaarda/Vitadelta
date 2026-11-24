package com.vitadelta.api.catalog;

import com.vitadelta.api.catalog.unit.Unit;
import com.vitadelta.api.catalog.unit.UnitAttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * Labelled amount of one nutrient per serving of a product, in the labelled (normalized) unit.
 * {@code ulAmountPerServing} is set only when the UL-relevant amount differs from the labelled
 * amount; {@code formCode} is the form of the UL-relevant part or {@code UNKNOWN}.
 */
@Entity
@Table(name = "product_nutrient")
public class ProductNutrient {

  @EmbeddedId private ProductNutrientId id;

  @Column(name = "amount_per_serving", nullable = false, precision = 14, scale = 4)
  private BigDecimal amountPerServing;

  @Column(name = "ul_amount_per_serving", precision = 14, scale = 4)
  private BigDecimal ulAmountPerServing;

  @Convert(converter = UnitAttributeConverter.class)
  @Column(name = "unit", nullable = false, length = 8)
  private Unit unit;

  @Column(name = "form_code", nullable = false, length = 40)
  private String formCode;

  @Column(name = "form_label", length = 200)
  private String formLabel;

  protected ProductNutrient() {}

  public ProductNutrient(ProductNutrientId id, ProductNutrientValues values) {
    this.id = id;
    assign(values);
  }

  /** Copies the given values; returns whether anything changed. */
  public boolean update(ProductNutrientValues values) {
    boolean changed =
        Changes.differ(amountPerServing, values.amountPerServing())
            || Changes.differ(ulAmountPerServing, values.ulAmountPerServing())
            || Changes.differ(unit, values.unit())
            || Changes.differ(formCode, values.formCode())
            || Changes.differ(formLabel, values.formLabel());
    if (changed) {
      assign(values);
    }
    return changed;
  }

  private void assign(ProductNutrientValues values) {
    this.amountPerServing = values.amountPerServing();
    this.ulAmountPerServing = values.ulAmountPerServing();
    this.unit = values.unit();
    this.formCode = values.formCode();
    this.formLabel = values.formLabel();
  }

  public ProductNutrientId getId() {
    return id;
  }

  public BigDecimal getAmountPerServing() {
    return amountPerServing;
  }

  public BigDecimal getUlAmountPerServing() {
    return ulAmountPerServing;
  }

  public Unit getUnit() {
    return unit;
  }

  public String getFormCode() {
    return formCode;
  }

  public String getFormLabel() {
    return formLabel;
  }
}
