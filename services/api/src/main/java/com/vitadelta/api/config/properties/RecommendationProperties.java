package com.vitadelta.api.config.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Ranking cache and catalog size limits of the recommendation module (project specification section
 * 7.5).
 *
 * @param cacheTtl lifetime of a cached ranking after it was stored (10 minutes)
 * @param cacheMaxEntries maximum number of cached rankings per API instance (500)
 * @param cacheMaxRankedItems maximum number of ranked products held by all cached rankings together
 *     (100 000); the cache evicts rankings when either bound is reached
 * @param maxProductsPerCountry largest number of in-market products of one country that a
 *     recommendation or comparison request loads and sends to the scoring service (5000); a larger
 *     catalog is an operator error, logged, and answered {@code 503 scoring_unavailable}
 */
@ConfigurationProperties(prefix = "vitadelta.recommendation")
public record RecommendationProperties(
    @DefaultValue("10m") Duration cacheTtl,
    @DefaultValue("500") int cacheMaxEntries,
    @DefaultValue("100000") int cacheMaxRankedItems,
    @DefaultValue("5000") int maxProductsPerCountry) {

  public RecommendationProperties {
    if (cacheTtl.isNegative() || cacheTtl.isZero() || cacheMaxEntries < 1) {
      throw new IllegalArgumentException(
          "vitadelta.recommendation.cache-ttl and cache-max-entries must be positive");
    }
    if (cacheMaxRankedItems < cacheMaxEntries) {
      throw new IllegalArgumentException(
          "vitadelta.recommendation.cache-max-ranked-items must be >= cache-max-entries");
    }
    if (maxProductsPerCountry < 1) {
      throw new IllegalArgumentException(
          "vitadelta.recommendation.max-products-per-country must be >= 1");
    }
  }

  /**
   * The cache weight every ranking has at least, so that the weight bound also limits the number of
   * entries to {@link #cacheMaxEntries}.
   */
  public int minimumRankingWeight() {
    return Math.max(1, cacheMaxRankedItems / cacheMaxEntries);
  }
}
