package com.vitadelta.api.catalog;

import com.vitadelta.api.catalog.unit.Unit;
import com.vitadelta.api.catalog.unit.UnitAttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** Registry entry of a nutrient with its canonical unit. */
@Entity
@Table(name = "nutrient")
public class Nutrient {

  @Id
  @Column(name = "code", length = 40)
  private String code;

  @Column(name = "name_en", nullable = false, length = 200)
  private String nameEn;

  @Enumerated(EnumType.STRING)
  @Column(name = "category", nullable = false, length = 20)
  private NutrientCategory category;

  @Convert(converter = UnitAttributeConverter.class)
  @Column(name = "canonical_unit", nullable = false, length = 8)
  private Unit canonicalUnit;

  @Column(name = "presence_floor_per_day", precision = 14, scale = 4)
  private BigDecimal presenceFloorPerDay;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  protected Nutrient() {}

  public Nutrient(
      String code,
      String nameEn,
      NutrientCategory category,
      Unit canonicalUnit,
      BigDecimal presenceFloorPerDay,
      int sortOrder) {
    this.code = code;
    this.nameEn = nameEn;
    this.category = category;
    this.canonicalUnit = canonicalUnit;
    this.presenceFloorPerDay = presenceFloorPerDay;
    this.sortOrder = sortOrder;
  }

  /** Copies the given values; returns whether anything changed. */
  public boolean update(
      String nameEn,
      NutrientCategory category,
      Unit canonicalUnit,
      BigDecimal presenceFloorPerDay,
      int sortOrder) {
    boolean changed =
        Changes.differ(this.nameEn, nameEn)
            || Changes.differ(this.category, category)
            || Changes.differ(this.canonicalUnit, canonicalUnit)
            || Changes.differ(this.presenceFloorPerDay, presenceFloorPerDay)
            || this.sortOrder != sortOrder;
    if (changed) {
      this.nameEn = nameEn;
      this.category = category;
      this.canonicalUnit = canonicalUnit;
      this.presenceFloorPerDay = presenceFloorPerDay;
      this.sortOrder = sortOrder;
    }
    return changed;
  }

  public String getCode() {
    return code;
  }

  public String getNameEn() {
    return nameEn;
  }

  public NutrientCategory getCategory() {
    return category;
  }

  public Unit getCanonicalUnit() {
    return canonicalUnit;
  }

  public BigDecimal getPresenceFloorPerDay() {
    return presenceFloorPerDay;
  }

  public int getSortOrder() {
    return sortOrder;
  }
}
