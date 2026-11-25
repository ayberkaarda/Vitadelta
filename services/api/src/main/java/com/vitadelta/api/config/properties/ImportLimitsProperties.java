package com.vitadelta.api.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bounds of one admin import file, so that a hostile file below the 20 MB body limit cannot exhaust
 * memory while it is parsed and validated.
 *
 * @param maxOfferRows data rows an offers CSV may have (200 000); a larger file fails with one
 *     {@code too_many_records} error before its rows are validated
 * @param maxProducts products a products document may have (50 000), same rule
 * @param maxFieldLength characters of one CSV field or JSON string (4096); a longer value is an
 *     error of its row (CSV) or of the document (JSON)
 * @param maxErrors errors collected for one file (1000); when more are found, validation stops and
 *     the job fails with the first {@code maxErrors} errors plus one {@code too_many_errors} error
 */
@ConfigurationProperties(prefix = "vitadelta.catalog.import-limits")
public record ImportLimitsProperties(
    @DefaultValue("200000") int maxOfferRows,
    @DefaultValue("50000") int maxProducts,
    @DefaultValue("4096") int maxFieldLength,
    @DefaultValue("1000") int maxErrors) {

  /** The defaults of {@code application.yml}. */
  public static final ImportLimitsProperties DEFAULTS =
      new ImportLimitsProperties(200_000, 50_000, 4096, 1000);

  public ImportLimitsProperties {
    if (maxOfferRows < 1 || maxProducts < 1 || maxErrors < 1) {
      throw new IllegalArgumentException(
          "vitadelta.catalog.import-limits.max-offer-rows, max-products and max-errors must be"
              + " >= 1");
    }
    if (maxFieldLength < 2048) {
      throw new IllegalArgumentException(
          "vitadelta.catalog.import-limits.max-field-length must be >= 2048 (the longest offer"
              + " URL): "
              + maxFieldLength);
    }
  }
}
