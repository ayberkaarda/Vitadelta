package com.vitadelta.api.catalog;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Business attributes of a product, keyed by {@link #code()}. */
public record ProductDetails(
    String code,
    String brand,
    String name,
    DosageForm dosageForm,
    int unitsPerContainer,
    int unitsPerServing,
    BigDecimal servingsPerDay,
    String gtin,
    String source,
    String sourceRef,
    LocalDate sourceEntryDate,
    boolean synthetic) {}
