package com.vitadelta.api.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Catalog read and import settings.
 *
 * @param priceStaleAfterDays age in days after which a selected offer is flagged {@code
 *     PRICE_STALE} (config key {@code price_stale_after_days}, default 30)
 * @param importThreads worker threads of the admin import executor (default 1: imports run one
 *     after another)
 * @param importQueueCapacity imports that may wait for a worker; when the queue is full a new
 *     import job fails at once
 */
@ConfigurationProperties(prefix = "vitadelta.catalog")
public record CatalogProperties(
    @DefaultValue("30") int priceStaleAfterDays,
    @DefaultValue("1") int importThreads,
    @DefaultValue("4") int importQueueCapacity) {

  public CatalogProperties {
    if (priceStaleAfterDays < 1) {
      throw new IllegalArgumentException(
          "vitadelta.catalog.price-stale-after-days must be >= 1: " + priceStaleAfterDays);
    }
    if (importThreads < 1 || importQueueCapacity < 1) {
      throw new IllegalArgumentException(
          "vitadelta.catalog.import-threads and import-queue-capacity must be >= 1");
    }
  }
}
