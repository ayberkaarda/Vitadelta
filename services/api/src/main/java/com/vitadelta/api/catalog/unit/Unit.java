package com.vitadelta.api.catalog.unit;

import java.util.Optional;

/** Amount units accepted for nutrients. {@link #symbol()} is the stored and serialized form. */
public enum Unit {
  MG("mg"),
  MCG("mcg"),
  G("g"),
  IU("IU");

  private final String symbol;

  Unit(String symbol) {
    this.symbol = symbol;
  }

  public String symbol() {
    return symbol;
  }

  /** Whether the unit measures mass (as opposed to biological activity). */
  public boolean isMass() {
    return this != IU;
  }

  /**
   * Resolves a stored symbol exactly as written ({@code mg}, {@code mcg}, {@code g}, {@code IU}).
   */
  public static Optional<Unit> fromSymbol(String symbol) {
    for (Unit unit : values()) {
      if (unit.symbol.equals(symbol)) {
        return Optional.of(unit);
      }
    }
    return Optional.empty();
  }

  /**
   * Normalizes a unit as written on a label or in an import file. {@code µg} (micro sign), {@code
   * μg} (Greek mu), {@code ug} and {@code mcg} become {@code mcg}; {@code iu} becomes {@code IU}.
   * Surrounding whitespace is ignored; every other spelling is rejected.
   */
  public static Optional<Unit> normalize(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    return switch (raw.strip()) {
      case "mg" -> Optional.of(MG);
      case "mcg", "ug", "µg", "μg" -> Optional.of(MCG);
      case "g" -> Optional.of(G);
      case "IU", "iu" -> Optional.of(IU);
      default -> Optional.empty();
    };
  }
}
