package com.vitadelta.api.catalog;

import com.vitadelta.api.catalog.unit.ConversionRule;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** One unit conversion rule; generic rows use nutrient code {@code *} and form {@code ANY}. */
@Entity
@Table(name = "unit_conversion")
public class UnitConversion {

  @EmbeddedId private UnitConversionId id;

  @Column(name = "factor", nullable = false, precision = 18, scale = 8)
  private BigDecimal factor;

  @Column(name = "note", length = 500)
  private String note;

  protected UnitConversion() {}

  public UnitConversion(UnitConversionId id, BigDecimal factor, String note) {
    this.id = id;
    this.factor = factor;
    this.note = note;
  }

  /** Copies the given values; returns whether anything changed. */
  public boolean update(BigDecimal factor, String note) {
    boolean changed = Changes.differ(this.factor, factor) || Changes.differ(this.note, note);
    if (changed) {
      this.factor = factor;
      this.note = note;
    }
    return changed;
  }

  public UnitConversionId getId() {
    return id;
  }

  public BigDecimal getFactor() {
    return factor;
  }

  public String getNote() {
    return note;
  }

  /** The rule this row defines. */
  public ConversionRule toRule() {
    return new ConversionRule(
        id.getNutrientCode(), id.getFromUnit(), id.getToUnit(), factor, id.getFormCode(), note);
  }
}
