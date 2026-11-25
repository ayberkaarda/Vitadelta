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
 * @param userRequestsPerMinute per signed-in user: query cache misses and comparisons admitted per
 *     minute (30); further ones are answered {@code 429 rate_limited}
 * @param userConcurrency per signed-in user: query cache misses and comparisons in flight at the
 *     same time (2)
 */
@ConfigurationProperties(prefix = "vitadelta.recommendation")
public record RecommendationProperties(
    @DefaultValue("10m") Duration cacheTtl,
    @DefaultValue("500") int cacheMaxEntries,
    @DefaultValue("100000") int cacheMaxRankedItems,
    @DefaultValue("5000") int maxProductsPerCountry,
    @DefaultValue("30") int userRequestsPerMinute,
    @DefaultValue("2") int userConcurrency) {

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
    if (userRequestsPerMinute < 1 || userConcurrency < 1) {
      throw new IllegalArgumentException(
          "vitadelta.recommendation.user-requests-per-minute and user-concurrency must be >= 1");
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
