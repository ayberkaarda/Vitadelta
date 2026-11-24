package com.vitadelta.api.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Form registry entry ({@code data/reference/forms.csv}): a form code of one nutrient. Product,
 * upper-limit and unit-conversion rows may use only registered forms of their nutrient besides the
 * sentinels {@link FormCodes#UNKNOWN} and {@link FormCodes#ANY}.
 */
@Entity
@Table(name = "nutrient_form")
public class NutrientForm {

  @Id
  @Column(name = "code", length = 40)
  private String code;

  @Column(name = "nutrient_code", nullable = false, length = 40)
  private String nutrientCode;

  @Column(name = "name_en", nullable = false, length = 200)
  private String nameEn;

  protected NutrientForm() {}

  public NutrientForm(String code, String nutrientCode, String nameEn) {
    this.code = code;
    this.nutrientCode = nutrientCode;
    this.nameEn = nameEn;
  }

  /** Copies the given values; returns whether anything changed. */
  public boolean update(String nutrientCode, String nameEn) {
    boolean changed =
        Changes.differ(this.nutrientCode, nutrientCode) || Changes.differ(this.nameEn, nameEn);
    if (changed) {
      this.nutrientCode = nutrientCode;
      this.nameEn = nameEn;
    }
    return changed;
  }

  public String getCode() {
    return code;
  }

  public String getNutrientCode() {
    return nutrientCode;
  }

  public String getNameEn() {
    return nameEn;
  }
}
