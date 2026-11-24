package com.vitadelta.api.catalog.unit;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Stores {@link Unit} as its symbol ({@code mg}, {@code mcg}, {@code g}, {@code IU}). */
@Converter
public class UnitAttributeConverter implements AttributeConverter<Unit, String> {

  @Override
  public String convertToDatabaseColumn(Unit unit) {
    return unit == null ? null : unit.symbol();
  }

  @Override
  public Unit convertToEntityAttribute(String symbol) {
    if (symbol == null) {
      return null;
    }
    return Unit.fromSymbol(symbol)
        .orElseThrow(() -> new IllegalStateException("Unknown unit in database: " + symbol));
  }
}
