package com.vitadelta.api.fx;

import com.vitadelta.api.common.jobs.JobLock;
import com.vitadelta.api.common.jobs.JobLockKeys;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Refresh of the stored FX rates (section 7.7, ADR-0009), under the advisory lock {@link
 * JobLockKeys#FX_REFRESH} (ADR-0011; a run that does not obtain it is skipped).
 *
 * <p>For every supported currency as base it requests all other supported currencies from the
 * {@link FxRateProvider} and upserts the rows with the provider's source in one short transaction
 * per base. A failed base (provider error, rejected answer, database error) is logged with a
 * bounded summary and counted, and the run continues with the next base; the stored rates stay in
 * use. A quote the provider does not publish (for example a mistyped extra currency) is dropped
 * from the request of a base instead of failing it ({@link #fetchPublished}). A base is unsupported
 * only when the provider answers with the verified 422 {@code invalid currency: <base>} for exactly
 * that base ({@link FxProviderException#baseRejected()}): it is left out of the run (operator
 * warning, counted in {@code vitadelta.fx.refresh.unsupported}), listed among the unsupported bases
 * of the result and not among the failed ones, and does not fail the run on its own. Any other
 * rejection fails the base. A run fails when a base failed, when no base at all could be refreshed,
 * or when it was aborted. A run never throws to its caller. After every run the lookup cache is
 * cleared.
 *
 * <p>Scheduling ({@link FxRefreshSchedule}): at start-up only when {@link #isRefreshDue()}, then
 * every {@code vitadelta.fx.refresh-interval}. {@code POST /admin/fx/refresh} calls {@link #run()}.
 *
 * <p>Metrics: {@code vitadelta.fx.refresh.success} (one per refreshed base), {@code
 * vitadelta.fx.refresh.failure} with tag {@code reason} (one per failed base or failed run), {@code
 * vitadelta.fx.refresh.unsupported} with tag {@code currency} (one per dropped quote or base), and
 * the gauge {@code vitadelta.fx.rate.age} (days between today in UTC and the newest stored rate
 * date; NaN without rates).
 */
@Component
public class FxRefreshJob {

  /** Name of the job in log lines. */
  public static final String NAME = "FxRefreshJob";

  /** {@code reason} tag of a failure outside a provider fetch. */
  static final String STORE_FAILURE = "STORE";

  /** {@code reason} tag of a run that failed as a whole (lock or registry query). */
  static final String RUN_FAILURE = "RUN";

  private static final Logger LOG = LoggerFactory.getLogger(FxRefreshJob.class);

  private final JobLock jobLock;
  private final FxRateProvider provider;
  private final FxRateRepository repository;
  private final SupportedCurrencies currencies;
  private final FxRateLookup lookup;
  private final FxProperties properties;
  private final TransactionTemplate transactions;
  private final Clock clock;
  private final MeterRegistry meters;
  private final AtomicReference<LastRun> lastRun = new AtomicReference<>();

  public FxRefreshJob(
      JobLock jobLock,
      FxRateProvider provider,
      FxRateRepository repository,
      SupportedCurrencies currencies,
      FxRateLookup lookup,
      FxProperties properties,
      PlatformTransactionManager transactionManager,
      Clock clock,
      MeterRegistry meters) {
    this.jobLock = jobLock;
    this.provider = provider;
    this.repository = repository;
    this.currencies = currencies;
    this.lookup = lookup;
    this.properties = properties;
    this.transactions = new TransactionTemplate(transactionManager);
    this.clock = clock;
    this.meters = meters;
    Gauge.builder("vitadelta.fx.rate.age", repository, rows -> rateAgeDays(rows, lookup))
        .baseUnit("days")
        .description("Days between today (UTC) and the newest stored FX rate date")
        .register(meters);
  }

  /**
   * Outcome of a run.
   *
   * @param ran false when the run was skipped because another session holds the lock
   * @param upsertedRows rows inserted or updated
   * @param failedBases bases whose rates were not refreshed because the provider call or the write
   *     failed, sorted; the unsupported bases are not among them
   * @param unsupportedBases bases the provider rejected with the verified 422 {@code invalid
   *     currency: <base>} answer, sorted; they get no rate and do not fail the run on their own
   *     (they cannot be refreshed until the configuration changes)
   * @param refreshedBases bases whose rates were fetched and stored
   * @param aborted true when an error ended the run before every base was tried (for example the
   *     country registry could not be read)
   */
  public record Result(
      boolean ran,
      int upsertedRows,
      List<String> failedBases,
      List<String> unsupportedBases,
      int refreshedBases,
      boolean aborted) {

    public Result {
      failedBases = List.copyOf(failedBases);
      unsupportedBases = List.copyOf(unsupportedBases);
    }

    static Result skipped() {
      return new Result(false, 0, List.of(), List.of(), 0, false);
    }

    static Result abortedRun() {
      return new Result(true, 0, List.of(), List.of(), 0, true);
    }

    /**
     * Whether the run obtained the lock, was not aborted, no base failed and at least one base was
     * refreshed (contract {@code JobRun.lastOutcome}). Unsupported bases alone do not fail a run.
     */
    public boolean succeeded() {
      return ran && !aborted && failedBases.isEmpty() && refreshedBases > 0;
    }
  }

  /**
   * End time, outcome and unsupported bases of the last run that obtained the lock (input of {@code
   * GET /admin/health}).
   *
   * @param succeeded false when a base failed, nothing was refreshed or an error ended the run
   * @param unsupportedBases {@link Result#unsupportedBases()} of that run
   */
  public record LastRun(Instant endedAt, boolean succeeded, List<String> unsupportedBases) {

    public LastRun {
      unsupportedBases = List.copyOf(unsupportedBases);
    }
  }

  /** The last run that obtained the lock since start; empty before the first one. */
  public Optional<LastRun> lastRun() {
    return Optional.ofNullable(lastRun.get());
  }

  /**
   * Whether the start-up rule asks for a refresh: no rate is stored, or the newest stored date is
   * older than {@code vitadelta.fx.startup-max-age} days before today (UTC).
   */
  public boolean isRefreshDue() {
    Optional<LocalDate> newest = repository.newestAsOf();
    return newest.isEmpty()
        || newest.get().isBefore(lookup.today().minusDays(properties.startupMaxAge()));
  }

  /**
   * The start-up run: {@link #run()} when {@link #isRefreshDue()}, otherwise nothing.
   *
   * @return the result, or empty when no refresh was due
   */
  public Optional<Result> runIfDue() {
    boolean due;
    try {
      due = isRefreshDue();
    } catch (RuntimeException e) {
      meters.counter("vitadelta.fx.refresh.failure", "reason", RUN_FAILURE).increment();
      LOG.warn("{}: start-up check failed ({})", NAME, e.getClass().getSimpleName());
      return Optional.empty();
    }
    if (!due) {
      LOG.info("{}: stored rates are current, no start-up refresh", NAME);
      return Optional.empty();
    }
    return Optional.of(run());
  }

  /** Refreshes every supported base under the advisory lock; never throws. */
  public Result run() {
    AtomicReference<Result> result = new AtomicReference<>();
    try {
      boolean ran = jobLock.runExclusive(NAME, JobLockKeys.FX_REFRESH, () -> result.set(guarded()));
      return ran ? result.get() : Result.skipped();
    } catch (RuntimeException e) {
      meters.counter("vitadelta.fx.refresh.failure", "reason", RUN_FAILURE).increment();
      LOG.warn("{}: run failed ({})", NAME, e.getClass().getSimpleName());
      Result done = result.get();
      return done != null ? done : Result.abortedRun();
    } finally {
      lookup.invalidate();
    }
  }

  /**
   * One run under the lock: never throws, records its end and outcome; a run with a failed base or
   * an aborted run is reported to the job-run registry as {@code FAILED}.
   */
  private Result guarded() {
    Result result;
    try {
      result = refresh();
    } catch (RuntimeException e) {
      meters.counter("vitadelta.fx.refresh.failure", "reason", RUN_FAILURE).increment();
      LOG.warn("{}: run aborted ({})", NAME, e.getClass().getSimpleName());
      result = Result.abortedRun();
    }
    if (!result.succeeded()) {
      // The run handled its failures itself: the job-run registry must still record it as FAILED.
      JobLock.reportFailure();
    }
    lastRun.set(new LastRun(clock.instant(), result.succeeded(), result.unsupportedBases()));
    return result;
  }

  private Result refresh() {
    SortedSet<String> supported = currencies.all();
    Instant fetchedAt = clock.instant();
    int rows = 0;
    int refreshed = 0;
    List<String> failed = new ArrayList<>();
    List<String> unsupportedBases = new ArrayList<>();
    for (String base : supported) {
      SortedSet<String> quotes = new TreeSet<>(supported);
      quotes.remove(base);
      if (quotes.isEmpty()) {
        continue;
      }
      try {
        List<FxQuote> fetched = fetchPublished(base, quotes);
        Integer written =
            transactions.execute(status -> {
              int upserted = 0;
              for (FxQuote quote : fetched) {
                upserted += repository.upsert(quote, fetchedAt, provider.source());
              }
              return upserted;
            });
        rows += written == null ? 0 : written;
        refreshed++;
        meters.counter("vitadelta.fx.refresh.success").increment();
      } catch (FxProviderException e) {
        if (e.baseRejected()) {
          unsupportedBases.add(base);
          unsupportedBase(base, e);
          continue;
        }
        failed.add(base);
        meters.counter("vitadelta.fx.refresh.failure", "reason", e.reason().name()).increment();
        LOG.warn(
            "{}: rates for base {} not refreshed ({}: {})", NAME, base, e.reason(), e.getMessage());
      } catch (RuntimeException e) {
        failed.add(base);
        meters.counter("vitadelta.fx.refresh.failure", "reason", STORE_FAILURE).increment();
        LOG.warn("{}: rates for base {} not stored ({})", NAME, base, e.getClass().getSimpleName());
      }
    }
    LOG.info(
        "{}: {} rows upserted for {} bases, failed bases {}, unsupported bases {}",
        NAME,
        rows,
        refreshed,
        failed,
        unsupportedBases);
    return new Result(true, rows, failed, unsupportedBases, refreshed, false);
  }

  /**
   * Fetches the rates of {@code base}, dropping quotes the provider does not publish: when a
   * rejection names requested quotes (or the answer leaves them out), the base is requested once
   * more without them; when a 400 or 422 answer names none of them (or the second request is
   * rejected again), every quote is requested on its own. Each dropped quote is logged as an
   * operator warning and counted ({@code vitadelta.fx.refresh.unsupported}, tag {@code currency});
   * its pair keeps no rate, so conversions to it stay {@code UNAVAILABLE}.
   *
   * @throws FxProviderException when no rate of the base could be fetched
   */
  private List<FxQuote> fetchPublished(String base, SortedSet<String> quotes) {
    try {
      return List.of(provider.fetchLatest(base, quotes));
    } catch (FxProviderException e) {
      SortedSet<String> offending = new TreeSet<>(e.unsupportedQuotes());
      offending.retainAll(quotes);
      if (!offending.isEmpty() && !e.baseRejected()) {
        unsupported(base, offending);
        SortedSet<String> remaining = new TreeSet<>(quotes);
        remaining.removeAll(offending);
        if (remaining.isEmpty()) {
          throw e;
        }
        try {
          return List.of(provider.fetchLatest(base, remaining));
        } catch (FxProviderException again) {
          if (rejectsQuotes(again) && remaining.size() > 1) {
            return oneByOne(base, remaining, again);
          }
          throw again;
        }
      }
      if (e.requestRejected() && !e.baseRejected() && quotes.size() > 1) {
        return oneByOne(base, quotes, e);
      }
      throw e;
    }
  }

  private List<FxQuote> oneByOne(String base, SortedSet<String> quotes, FxProviderException cause) {
    List<FxQuote> fetched = new ArrayList<>();
    for (String quote : quotes) {
      try {
        fetched.add(provider.fetchLatest(base, Set.of(quote)));
      } catch (FxProviderException e) {
        if (!rejectsQuotes(e) || e.baseRejected()) {
          throw e;
        }
        unsupported(base, Set.of(quote));
      }
    }
    if (fetched.isEmpty()) {
      throw cause;
    }
    return fetched;
  }

  private static boolean rejectsQuotes(FxProviderException e) {
    return e.requestRejected() || !e.unsupportedQuotes().isEmpty();
  }

  private void unsupported(String base, Set<String> quotes) {
    for (String quote : quotes) {
      meters.counter("vitadelta.fx.refresh.unsupported", "currency", quote).increment();
      LOG.warn(
          "{}: the provider publishes no rate from {} to {}; {} is left out of the request for {}"
              + " and stays without a rate (check VITADELTA_FX_EXTRA_CURRENCIES)",
          NAME,
          base,
          quote,
          quote,
          base);
    }
  }

  /**
   * A base the provider names as an invalid currency: left out of this run (it cannot be refreshed
   * until the configuration changes), logged as an operator warning and counted as unsupported.
   */
  private void unsupportedBase(String base, FxProviderException e) {
    meters.counter("vitadelta.fx.refresh.unsupported", "currency", base).increment();
    LOG.warn(
        "{}: the provider does not publish base {} ({}); {} is left out of this run and"
            + " conversions from it stay unavailable (check VITADELTA_FX_EXTRA_CURRENCIES)",
        NAME,
        base,
        e.getMessage(),
        base);
  }

  private static double rateAgeDays(FxRateRepository repository, FxRateLookup lookup) {
    try {
      return repository
          .newestAsOf()
          .map(asOf -> (double) ChronoUnit.DAYS.between(asOf, lookup.today()))
          .orElse(Double.NaN);
    } catch (RuntimeException e) {
      return Double.NaN;
    }
  }
}
