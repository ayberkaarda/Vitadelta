package com.vitadelta.api.imports;

import com.vitadelta.api.catalog.FormCodes;
import com.vitadelta.api.catalog.ProductNutrientValues;
import com.vitadelta.api.catalog.unit.IuAllowList;
import com.vitadelta.api.catalog.unit.Unit;
import com.vitadelta.api.catalog.unit.UnitConversionException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Importer rules for one labelled nutrient (unit normalization, IU rules, form sentinel and
 * label-basis fields; project specification sections 5.3 to 5.5).
 *
 * <ul>
 *   <li>The nutrient code must exist in the registry ({@code unknown_nutrient}).
 *   <li>The amount is {@code > 0} with at most 4 decimals ({@code invalid_amount}).
 *   <li>Units {@code µg}, {@code μg}, {@code ug} become {@code mcg} and {@code iu} becomes {@code
 *       IU}; other spellings are {@code invalid_unit}. The stored amount stays in the labelled
 *       unit; it must be convertible into the canonical unit: IU only for nutrients with an IU rule
 *       ({@code iu_not_allowed}) and, for VIT_A, only for the retinol forms ({@code
 *       unsupported_iu_form}).
 *   <li>A missing form, {@code UNKNOWN} or a form outside the registry is stored as {@code
 *       UNKNOWN}; the raw text is kept in {@code form_label} (the raw form code becomes the label
 *       when no label is given). {@code ANY} is not a product form ({@code invalid_form_code}); a
 *       registered form of another nutrient is {@code form_nutrient_mismatch}.
 *   <li>{@code ul_amount_per_serving} is optional, {@code >= 0}, at most the amount and at most 4
 *       decimals ({@code invalid_ul_amount}).
 * </ul>
 */
public final class ProductNutrientNormalizer {

  public static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999.9999");
  public static final int MAX_FORM_LABEL_LENGTH = 200;

  /** A labelled nutrient as read from an import document. */
  public record RawNutrient(
      String nutrientCode,
      BigDecimal amountPerServing,
      String unit,
      BigDecimal ulAmountPerServing,
      String formCode,
      String formLabel) {}

  /** Normalized values or the problems found; exactly one of them is present. */
  public record Outcome(Optional<ProductNutrientValues> values, List<ImportProblem> problems) {}

  private ProductNutrientNormalizer() {}

  public static Outcome normalize(RawNutrient raw, CatalogRegistry registry, String location) {
    List<ImportProblem> problems = new ArrayList<>();
    Optional<Unit> canonical = registry.canonicalUnit(raw.nutrientCode());
    if (canonical.isEmpty()) {
      problems.add(
          new ImportProblem(
              location, "unknown_nutrient", "nutrient " + raw.nutrientCode() + " is unknown"));
    }
    if (!isValidAmount(raw.amountPerServing(), false)) {
      problems.add(
          new ImportProblem(
              location,
              "invalid_amount",
              "amount_per_serving must be > 0 with at most 4 decimals"));
    }
    if (raw.ulAmountPerServing() != null
        && (!isValidAmount(raw.ulAmountPerServing(), true)
            || raw.amountPerServing() != null
                && raw.ulAmountPerServing().compareTo(raw.amountPerServing()) > 0)) {
      problems.add(
          new ImportProblem(
              location,
              "invalid_ul_amount",
              "ul_amount_per_serving must be between 0 and amount_per_serving"));
    }
    String rawForm = blankToNull(raw.formCode());
    String formLabel = blankToNull(raw.formLabel());
    String formCode;
    if (FormCodes.ANY.equals(rawForm)) {
      problems.add(new ImportProblem(location, "invalid_form_code", "ANY is not a product form"));
      formCode = FormCodes.UNKNOWN;
    } else if (rawForm != null && registry.formNutrients().containsKey(rawForm)) {
      formCode = rawForm;
      String owner = registry.formNutrients().get(rawForm);
      if (!owner.equals(raw.nutrientCode())) {
        problems.add(
            new ImportProblem(
                location,
                "form_nutrient_mismatch",
                "form " + rawForm + " belongs to " + owner + ", not " + raw.nutrientCode()));
      }
    } else {
      formCode = FormCodes.UNKNOWN;
      if (rawForm != null && !FormCodes.UNKNOWN.equals(rawForm) && formLabel == null) {
        formLabel = rawForm;
      }
    }
    if (formLabel != null && formLabel.length() > MAX_FORM_LABEL_LENGTH) {
      problems.add(
          new ImportProblem(location, "invalid_form_label", "form_label exceeds 200 characters"));
    }
    Optional<Unit> unit = Unit.normalize(raw.unit());
    if (unit.isEmpty()) {
      problems.add(
          new ImportProblem(location, "invalid_unit", "unit " + raw.unit() + " is invalid"));
    } else if (canonical.isPresent()) {
      Optional<UnitConversionException.Reason> denied =
          unit.get() == Unit.IU
              ? IuAllowList.check(raw.nutrientCode(), formCode)
              : Optional.empty();
      if (denied.isPresent()) {
        problems.add(
            new ImportProblem(
                location,
                denied.get().code(),
                "IU is not accepted for " + raw.nutrientCode() + " with form " + formCode));
      } else {
        convertible(raw, unit.get(), formCode, canonical.get(), registry, location, problems);
      }
    }
    if (!problems.isEmpty()) {
      return new Outcome(Optional.empty(), List.copyOf(problems));
    }
    return new Outcome(
        Optional.of(
            new ProductNutrientValues(
                raw.nutrientCode(),
                raw.amountPerServing(),
                raw.ulAmountPerServing(),
                unit.get(),
                formCode,
                formLabel)),
        List.of());
  }

  private static void convertible(
      RawNutrient raw,
      Unit unit,
      String formCode,
      Unit canonical,
      CatalogRegistry registry,
      String location,
      List<ImportProblem> problems) {
    try {
      registry.conversions().convert(raw.nutrientCode(), BigDecimal.ONE, unit, formCode, canonical);
    } catch (UnitConversionException e) {
      problems.add(new ImportProblem(location, e.reason().code(), e.detail()));
    }
  }

  private static boolean isValidAmount(BigDecimal amount, boolean zeroAllowed) {
    return amount != null
        && (zeroAllowed ? amount.signum() >= 0 : amount.signum() > 0)
        && amount.compareTo(MAX_AMOUNT) <= 0
        && amount.stripTrailingZeros().scale() <= 4;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
