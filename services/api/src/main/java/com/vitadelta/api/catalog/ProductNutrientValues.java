package com.vitadelta.api.catalog;

import com.vitadelta.api.catalog.unit.Unit;
import java.math.BigDecimal;

/** Normalized values of one product nutrient row. */
public record ProductNutrientValues(
    String nutrientCode,
    BigDecimal amountPerServing,
    BigDecimal ulAmountPerServing,
    Unit unit,
    String formCode,
    String formLabel) {}
