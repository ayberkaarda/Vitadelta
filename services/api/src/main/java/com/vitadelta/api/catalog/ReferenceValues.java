package com.vitadelta.api.catalog;

import com.vitadelta.api.catalog.unit.Unit;
import java.math.BigDecimal;

/** Validated rows of the reference data files, keyed by their business keys. */
public final class ReferenceValues {

  private ReferenceValues() {}

  /** A nutrient registry row. */
  public record NutrientRow(
      String code,
      String nameEn,
      NutrientCategory category,
      Unit canonicalUnit,
      BigDecimal presenceFloorPerDay,
      int sortOrder) {}

  /** A reference set or UL set row. */
  public record SetRow(String code, String name, String sourceCitation) {}

  /** A reference value row. */
  public record ReferenceValueRow(
      String referenceSetCode, String nutrientCode, BigDecimal amount, Unit unit) {}

  /** An upper limit row; amount and basis are both null for exempt rows. */
  public record UpperLimitRow(
      String ulSetCode,
      String nutrientCode,
      String formCode,
      BigDecimal amount,
      Unit unit,
      UlBasis basis) {}

  /** A country registry row. */
  public record CountryRow(
      String code,
      String nameEn,
      String currency,
      String referenceSetCode,
      String ulSetCode,
      String defaultLocale) {}
}
