package com.vitadelta.api.catalog;

import com.vitadelta.api.catalog.unit.Unit;
import com.vitadelta.api.catalog.unit.UnitAttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * Tolerable upper intake level of one nutrient form in one UL set. An exempt row (a form explicitly
 * without a UL) has neither amount nor basis.
 */
@Entity
@Table(name = "upper_limit")
public class UpperLimit {

  @EmbeddedId private UpperLimitId id;

  @Column(name = "amount", precision = 14, scale = 4)
  private BigDecimal amount;

  @Convert(converter = UnitAttributeConverter.class)
  @Column(name = "unit", nullable = false, length = 8)
  private Unit unit;

  @Enumerated(EnumType.STRING)
  @Column(name = "basis", length = 20)
  private UlBasis basis;

  @Column(name = "note", nullable = false, columnDefinition = "text")
  private String note;

  protected UpperLimit() {}

  public UpperLimit(UpperLimitId id, BigDecimal amount, Unit unit, UlBasis basis, String note) {
    this.id = id;
    this.amount = amount;
    this.unit = unit;
    this.basis = basis;
    this.note = note;
  }

  /** Copies the given values; returns whether anything changed. */
  public boolean update(BigDecimal amount, Unit unit, UlBasis basis, String note) {
    boolean changed =
        Changes.differ(this.amount, amount)
            || Changes.differ(this.unit, unit)
            || Changes.differ(this.basis, basis)
            || Changes.differ(this.note, note);
    if (changed) {
      this.amount = amount;
      this.unit = unit;
      this.basis = basis;
      this.note = note;
    }
    return changed;
  }

  /** Citation note of the row ({@code upper_limits.csv} column {@code note}). */
  public String getNote() {
    return note;
  }

  public UpperLimitId getId() {
    return id;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public Unit getUnit() {
    return unit;
  }

  public UlBasis getBasis() {
    return basis;
  }

  public boolean isExempt() {
    return amount == null;
  }
}
