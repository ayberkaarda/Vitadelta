package com.vitadelta.api.fx;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.vitadelta.api.fx.FxRateRepository.StoredRate;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Rate lookup of the response assembly (section 7.7): the stored row with the newest {@code as_of}
 * for {@code (native, display)}. Lookups are cached per pair for {@code
 * vitadelta.fx.lookup-cache-ttl} (also the absence of a row); {@link #invalidate()} clears the
 * cache after a refresh. Staleness is evaluated against the clock on every call, never cached.
 */
@Component
public class FxRateLookup {

  /** Pairs held at most (supported currencies squared). */
  private static final int MAX_PAIRS = 1024;

  private final FxRateRepository rates;
  private final FxProperties properties;
  private final Clock clock;
  private final LoadingCache<Pair, Optional<StoredRate>> cache;

  public FxRateLookup(FxRateRepository rates, FxProperties properties, Clock clock) {
    this.rates = rates;
    this.properties = properties;
    this.clock = clock;
    this.cache =
        Caffeine.newBuilder()
            .expireAfterWrite(properties.lookupCacheTtl())
            .maximumSize(MAX_PAIRS)
            .build(pair -> rates.newest(pair.base(), pair.quote()));
  }

  private record Pair(String base, String quote) {}

  /** The newest stored rate from {@code base} to {@code quote}. */
  public Optional<StoredRate> newest(String base, String quote) {
    return cache.get(new Pair(base, quote));
  }

  /** Whether a rate dated {@code asOf} is older than {@code fx_stale_after_days} (today in UTC). */
  public boolean isStale(LocalDate asOf) {
    return asOf.isBefore(today().minusDays(properties.staleAfterDays()));
  }

  /** Today in UTC by the application clock. */
  public LocalDate today() {
    return LocalDate.now(clock.withZone(ZoneOffset.UTC));
  }

  /** Clears every cached lookup. */
  public void invalidate() {
    cache.invalidateAll();
  }
}
