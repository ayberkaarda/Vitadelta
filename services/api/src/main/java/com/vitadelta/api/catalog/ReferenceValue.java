package com.vitadelta.api.catalog;

import com.vitadelta.api.catalog.unit.Unit;
import com.vitadelta.api.catalog.unit.UnitAttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** Regulatory reference value of one nutrient in one reference set. */
@Entity
@Table(name = "reference_value")
public class ReferenceValue {

  @EmbeddedId private ReferenceValueId id;

  @Column(name = "amount", nullable = false, precision = 14, scale = 4)
  private BigDecimal amount;

  @Convert(converter = UnitAttributeConverter.class)
  @Column(name = "unit", nullable = false, length = 8)
  private Unit unit;

  protected ReferenceValue() {}

  public ReferenceValue(ReferenceValueId id, BigDecimal amount, Unit unit) {
    this.id = id;
    this.amount = amount;
    this.unit = unit;
  }

  /** Copies the given values; returns whether anything changed. */
  public boolean update(BigDecimal amount, Unit unit) {
    boolean changed = Changes.differ(this.amount, amount) || Changes.differ(this.unit, unit);
    if (changed) {
      this.amount = amount;
      this.unit = unit;
    }
    return changed;
  }

  public ReferenceValueId getId() {
    return id;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public Unit getUnit() {
    return unit;
  }
}
