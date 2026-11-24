package com.vitadelta.api.catalog.unit;

import java.util.Optional;
import java.util.Set;

/**
 * Fixed allow-list for amounts in IU (unit conversion rules, project specification section 5.5),
 * enforced independently of the conversion table so that an added conversion row cannot widen it:
 * IU is accepted only for VIT_A, VIT_D and VIT_E, and VIT_A in IU only for the retinol forms (the
 * database enforces the same rule with CHECK constraints on {@code product_nutrient}).
 */
public final class IuAllowList {

  public static final Set<String> NUTRIENTS = Set.of("VIT_A", "VIT_D", "VIT_E");

  public static final Set<String> VITAMIN_A_FORMS =
      Set.of("RETINOL", "RETINYL_ACETATE", "RETINYL_PALMITATE");

  private IuAllowList() {}

  /**
   * Checks an amount in IU of a nutrient with the given form ({@code ANY} for a reference rule).
   *
   * @return the reason the combination is not allowed, or empty when it is
   */
  public static Optional<UnitConversionException.Reason> check(String nutrientCode, String form) {
    if (!NUTRIENTS.contains(nutrientCode)) {
      return Optional.of(UnitConversionException.Reason.IU_NOT_ALLOWED);
    }
    if (nutrientCode.equals("VIT_A") && !VITAMIN_A_FORMS.contains(form)) {
      return Optional.of(UnitConversionException.Reason.UNSUPPORTED_IU_FORM);
    }
    return Optional.empty();
  }
}
