package com.vitadelta.api.config.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Ranking cache of the recommendation module (project specification section 7.5).
 *
 * @param cacheTtl lifetime of a cached ranking after it was stored (10 minutes)
 * @param cacheMaxEntries maximum number of cached rankings per API instance (500)
 */
@ConfigurationProperties(prefix = "vitadelta.recommendation")
public record RecommendationProperties(
    @DefaultValue("10m") Duration cacheTtl, @DefaultValue("500") int cacheMaxEntries) {

  public RecommendationProperties {
    if (cacheTtl.isNegative() || cacheTtl.isZero() || cacheMaxEntries < 1) {
      throw new IllegalArgumentException(
          "vitadelta.recommendation.cache-ttl and cache-max-entries must be positive");
    }
  }
}
