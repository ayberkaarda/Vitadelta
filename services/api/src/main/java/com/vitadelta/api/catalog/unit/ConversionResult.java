package com.vitadelta.api.catalog.unit;

import java.math.BigDecimal;

/**
 * Result of a unit conversion. The amount is exact (no rounding).
 *
 * @param assumed true when a form-independent rule was used for a nutrient whose conversion depends
 *     on the form (flag {@code CONVERSION_ASSUMED})
 */
public record ConversionResult(BigDecimal amount, Unit unit, boolean assumed) {}
