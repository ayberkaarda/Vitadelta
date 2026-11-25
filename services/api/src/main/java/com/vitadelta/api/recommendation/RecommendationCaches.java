package com.vitadelta.api.recommendation;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.vitadelta.api.catalog.read.MarketProfile;
import com.vitadelta.api.config.properties.RecommendationProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * The in-memory caches of the recommendation module (section 7.5), per API instance:
 *
 * <ul>
 *   <li>rankings: full native rankings by {@link RankingQuery#cacheKey(long)}, TTL 10 minutes, at
 *       most 500 entries and at most {@code cache-max-ranked-items} ranked products in total (each
 *       ranking weighs its product count, at least the share of one entry); invalidated implicitly
 *       because the catalog version is part of the key;
 *   <li>market profiles by country and catalog version (section 6.3);
 *   <li>registry snapshots by catalog version (request validation).
 * </ul>
 *
 * Hit and miss counts of the ranking cache are exported as {@code cache.gets{cache=
 * recommendation.ranking}} (Micrometer Caffeine binder), from which the hit ratio is derived.
 */
@Component
public class RecommendationCaches {

  /** Metric name of the ranking cache. */
  public static final String RANKING_CACHE = "recommendation.ranking";

  private final Cache<String, NativeRanking> rankings;
  private final Cache<MarketKey, MarketProfile> marketProfiles;
  private final Cache<Long, ReferenceSnapshot> references;

  public RecommendationCaches(RecommendationProperties properties, MeterRegistry meters) {
    int minimumWeight = properties.minimumRankingWeight();
    this.rankings =
        Caffeine.newBuilder()
            .expireAfterWrite(properties.cacheTtl())
            .maximumWeight(properties.cacheMaxRankedItems())
            .<String, NativeRanking>weigher((key, ranking) -> weight(ranking, minimumWeight))
            .recordStats()
            .build();
    this.marketProfiles =
        Caffeine.newBuilder().expireAfterWrite(properties.cacheTtl()).maximumSize(64).build();
    this.references =
        Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(10)).maximumSize(4).build();
    CaffeineCacheMetrics.monitor(meters, rankings, RANKING_CACHE);
  }

  /**
   * Cache weight of a ranking: its number of ranked products, but at least {@code minimum}, so the
   * weight bound (total ranked products) also caps the number of entries.
   */
  static int weight(NativeRanking ranking, int minimum) {
    return Math.max(minimum, ranking.total());
  }

  /** Total weight of the cached rankings (after pending maintenance), for tests and operations. */
  public long rankingWeight() {
    rankings.cleanUp();
    return rankings.policy().eviction().map(e -> e.weightedSize().orElse(0L)).orElse(0L);
  }

  /** The cached ranking of {@code key}, if present. */
  Optional<NativeRanking> ranking(String key) {
    return Optional.ofNullable(rankings.getIfPresent(key));
  }

  void putRanking(String key, NativeRanking ranking) {
    rankings.put(key, ranking);
  }

  /** The market profile of a country at a catalog version, loading it on a miss. */
  MarketProfile marketProfile(String country, long catalogVersion, Supplier<MarketProfile> load) {
    return marketProfiles.get(new MarketKey(country, catalogVersion), key -> load.get());
  }

  /** The registry snapshot of a catalog version, loading it on a miss. */
  ReferenceSnapshot reference(long catalogVersion, Supplier<ReferenceSnapshot> load) {
    return references.get(catalogVersion, key -> load.get());
  }

  /** Removes every cached entry (tests and operations). */
  public void clear() {
    rankings.invalidateAll();
    marketProfiles.invalidateAll();
    references.invalidateAll();
  }

  /** Number of cached rankings (after pending maintenance). */
  public long rankingCount() {
    rankings.cleanUp();
    return rankings.estimatedSize();
  }

  private record MarketKey(String country, long catalogVersion) {}
}
