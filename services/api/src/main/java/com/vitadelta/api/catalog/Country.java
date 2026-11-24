package com.vitadelta.api.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A supported country with its currency and its reference and UL sets. */
@Entity
@Table(name = "country")
public class Country {

  @Id
  @Column(name = "code", length = 2)
  private String code;

  @Column(name = "name_en", nullable = false, length = 200)
  private String nameEn;

  @Column(name = "currency", nullable = false, length = 3)
  private String currency;

  @Column(name = "reference_set_code", nullable = false, length = 40)
  private String referenceSetCode;

  @Column(name = "ul_set_code", nullable = false, length = 40)
  private String ulSetCode;

  @Column(name = "default_locale", nullable = false, length = 10)
  private String defaultLocale;

  protected Country() {}

  public Country(
      String code,
      String nameEn,
      String currency,
      String referenceSetCode,
      String ulSetCode,
      String defaultLocale) {
    this.code = code;
    this.nameEn = nameEn;
    this.currency = currency;
    this.referenceSetCode = referenceSetCode;
    this.ulSetCode = ulSetCode;
    this.defaultLocale = defaultLocale;
  }

  /** Copies the given values; returns whether anything changed. */
  public boolean update(
      String nameEn,
      String currency,
      String referenceSetCode,
      String ulSetCode,
      String defaultLocale) {
    boolean changed =
        Changes.differ(this.nameEn, nameEn)
            || Changes.differ(this.currency, currency)
            || Changes.differ(this.referenceSetCode, referenceSetCode)
            || Changes.differ(this.ulSetCode, ulSetCode)
            || Changes.differ(this.defaultLocale, defaultLocale);
    if (changed) {
      this.nameEn = nameEn;
      this.currency = currency;
      this.referenceSetCode = referenceSetCode;
      this.ulSetCode = ulSetCode;
      this.defaultLocale = defaultLocale;
    }
    return changed;
  }

  public String getCode() {
    return code;
  }

  public String getNameEn() {
    return nameEn;
  }

  public String getCurrency() {
    return currency;
  }

  public String getReferenceSetCode() {
    return referenceSetCode;
  }

  public String getUlSetCode() {
    return ulSetCode;
  }

  public String getDefaultLocale() {
    return defaultLocale;
  }
}
