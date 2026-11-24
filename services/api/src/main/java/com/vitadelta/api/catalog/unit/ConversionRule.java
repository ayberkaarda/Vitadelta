package com.vitadelta.api.catalog.unit;

import com.vitadelta.api.catalog.FormCodes;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * One row of the unit conversion table: {@code amount[to] = amount[from] * factor}.
 *
 * @param nutrientCode nutrient code, or {@code *} for a generic rule
 * @param formCode form the rule applies to, or {@code ANY}
 */
public record ConversionRule(
    String nutrientCode, Unit from, Unit to, BigDecimal factor, String formCode, String note) {

  public ConversionRule {
    Objects.requireNonNull(nutrientCode, "nutrientCode");
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    Objects.requireNonNull(factor, "factor");
    Objects.requireNonNull(formCode, "formCode");
    if (factor.signum() <= 0) {
      throw new IllegalArgumentException("factor must be positive: " + factor);
    }
    if (from == to) {
      throw new IllegalArgumentException("from and to units must differ: " + from);
    }
    if (!to.isMass()) {
      throw new IllegalArgumentException("target unit must be a mass unit: " + to);
    }
    if (FormCodes.UNKNOWN.equals(formCode)) {
      throw new IllegalArgumentException("UNKNOWN is not a reference form");
    }
    if (FormCodes.GENERIC_NUTRIENT.equals(nutrientCode) && !FormCodes.ANY.equals(formCode)) {
      throw new IllegalArgumentException("generic rules must use form ANY");
    }
  }

  public boolean isGeneric() {
    return FormCodes.GENERIC_NUTRIENT.equals(nutrientCode);
  }

  public boolean isAnyForm() {
    return FormCodes.ANY.equals(formCode);
  }
}
