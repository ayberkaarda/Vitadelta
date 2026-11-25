package com.vitadelta.api.fx;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Freshness of the stored rates for {@code GET /admin/health} (contract schema {@code FxHealth}),
 * read from the table on every call: {@code newestAsOf} is the newest {@code as_of} of all rows,
 * {@code rateAgeDays} the days from it to today (UTC), {@code stale} when that age exceeds {@code
 * fx_stale_after_days}. Status is {@code UP}, or {@code DEGRADED} when the rates are stale or none
 * is stored; FX never makes the service {@code DOWN}.
 */
@Component
public class FxFreshness {

  /** Component status values (contract schema {@code HealthStatus}). */
  public static final String UP = "UP";

  public static final String DEGRADED = "DEGRADED";

  private final FxRateRepository repository;
  private final FxRateLookup lookup;

  public FxFreshness(FxRateRepository repository, FxRateLookup lookup) {
    this.repository = repository;
    this.lookup = lookup;
  }

  /**
   * One reading.
   *
   * @param newestAsOf {@code null} without rates
   * @param rateAgeDays {@code null} without rates
   */
  public record Snapshot(String status, LocalDate newestAsOf, Integer rateAgeDays, boolean stale) {}

  /** The current freshness. */
  public Snapshot current() {
    Optional<LocalDate> newest = repository.newestAsOf();
    if (newest.isEmpty()) {
      return new Snapshot(DEGRADED, null, null, false);
    }
    LocalDate asOf = newest.get();
    int age = (int) Math.max(0, ChronoUnit.DAYS.between(asOf, lookup.today()));
    boolean stale = lookup.isStale(asOf);
    return new Snapshot(stale ? DEGRADED : UP, asOf, age, stale);
  }
}
