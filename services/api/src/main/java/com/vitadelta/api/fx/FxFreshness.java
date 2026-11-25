package com.vitadelta.api.fx;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Freshness of the stored rates for {@code GET /admin/health} (contract schema {@code FxHealth}),
 * read from the table on every call: {@code newestAsOf} is the newest {@code as_of} of all rows,
 * {@code rateAgeDays} the days from it to today (UTC), {@code stale} when that age exceeds {@code
 * fx_stale_after_days}. Status is {@code UP}, or {@code DEGRADED} when the rates are stale or none
 * is stored; FX never makes the service {@code DOWN}. {@code unsupportedBases} are those of the
 * last {@link FxRefreshJob} run of this process (empty before the first); they do not change the
 * status.
 */
@Component
public class FxFreshness {

  /** Component status values (contract schema {@code HealthStatus}). */
  public static final String UP = "UP";

  public static final String DEGRADED = "DEGRADED";

  private final FxRateRepository repository;
  private final FxRateLookup lookup;
  private final FxRefreshJob job;

  public FxFreshness(FxRateRepository repository, FxRateLookup lookup, FxRefreshJob job) {
    this.repository = repository;
    this.lookup = lookup;
    this.job = job;
  }

  /**
   * One reading.
   *
   * @param newestAsOf {@code null} without rates
   * @param rateAgeDays {@code null} without rates
   * @param unsupportedBases unsupported bases of the last refresh run, ascending
   */
  public record Snapshot(
      String status,
      LocalDate newestAsOf,
      Integer rateAgeDays,
      boolean stale,
      List<String> unsupportedBases) {

    public Snapshot {
      unsupportedBases = List.copyOf(unsupportedBases);
    }
  }

  /** The current freshness. */
  public Snapshot current() {
    List<String> unsupported =
        job.lastRun().map(FxRefreshJob.LastRun::unsupportedBases).orElse(List.of());
    Optional<LocalDate> newest = repository.newestAsOf();
    if (newest.isEmpty()) {
      return new Snapshot(DEGRADED, null, null, false, unsupported);
    }
    LocalDate asOf = newest.get();
    int age = (int) Math.max(0, ChronoUnit.DAYS.between(asOf, lookup.today()));
    boolean stale = lookup.isStale(asOf);
    return new Snapshot(stale ? DEGRADED : UP, asOf, age, stale, unsupported);
  }
}
