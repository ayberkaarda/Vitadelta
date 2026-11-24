package com.vitadelta.api.catalog.unit;

import com.vitadelta.api.catalog.Nutrient;
import com.vitadelta.api.catalog.NutrientRepository;
import com.vitadelta.api.catalog.UnitConversion;
import com.vitadelta.api.catalog.UnitConversionRepository;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Converts stored label amounts into a nutrient's canonical unit with the conversion rules held in
 * the {@code unit_conversion} table. Used when scoring input is constructed; stored amounts stay in
 * their labelled unit.
 */
@Service
public class UnitConversionService {

  private final UnitConversionRepository conversions;
  private final NutrientRepository nutrients;

  public UnitConversionService(UnitConversionRepository conversions, NutrientRepository nutrients) {
    this.conversions = conversions;
    this.nutrients = nutrients;
  }

  /** The current conversion table. */
  @Transactional(readOnly = true)
  public UnitConversionTable currentTable() {
    return new UnitConversionTable(
        conversions.findAll().stream().map(UnitConversion::toRule).toList());
  }

  /**
   * Converts an amount of a nutrient into its canonical unit.
   *
   * @throws IllegalArgumentException when the nutrient is not in the registry
   * @throws UnitConversionException when the conversion is not allowed or not defined
   */
  @Transactional(readOnly = true)
  public ConversionResult toCanonical(
      String nutrientCode, BigDecimal amount, Unit unit, String productForm) {
    Nutrient nutrient =
        nutrients
            .findById(nutrientCode)
            .orElseThrow(() -> new IllegalArgumentException("unknown nutrient: " + nutrientCode));
    return currentTable()
        .convert(nutrientCode, amount, unit, productForm, nutrient.getCanonicalUnit());
  }
}
