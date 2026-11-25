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
 * use. A run never throws to its caller. After every run the lookup cache is cleared.
 *
 * <p>Scheduling ({@link FxRefreshSchedule}): at start-up only when {@link #isRefreshDue()}, then
 * every {@code vitadelta.fx.refresh-interval}. {@code POST /admin/fx/refresh} calls {@link #run()}.
 *
 * <p>Metrics: {@code vitadelta.fx.refresh.success} (one per refreshed base), {@code
 * vitadelta.fx.refresh.failure} with tag {@code reason} (one per failed base or failed run), and
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
   * @param failedBases bases whose rates were not refreshed, sorted
   * @param aborted true when an error ended the run before every base was tried (for example the
   *     country registry could not be read)
   */
  public record Result(boolean ran, int upsertedRows, List<String> failedBases, boolean aborted) {

    public Result {
      failedBases = List.copyOf(failedBases);
    }

    static Result skipped() {
      return new Result(false, 0, List.of(), false);
    }

    /** Whether the run obtained the lock and refreshed every base. */
    public boolean succeeded() {
      return ran && !aborted && failedBases.isEmpty();
    }
  }

  /**
   * End time and outcome of the last run that obtained the lock (input of {@code GET
   * /admin/health}).
   *
   * @param succeeded false when a base failed or an error ended the run
   */
  public record LastRun(Instant endedAt, boolean succeeded) {}

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
      return done != null ? done : new Result(true, 0, List.of(), true);
    } finally {
      lookup.invalidate();
    }
  }

  /** One run under the lock: never throws, records its end and outcome. */
  private Result guarded() {
    Result result;
    try {
      result = refresh();
    } catch (RuntimeException e) {
      meters.counter("vitadelta.fx.refresh.failure", "reason", RUN_FAILURE).increment();
      LOG.warn("{}: run aborted ({})", NAME, e.getClass().getSimpleName());
      result = new Result(true, 0, List.of(), true);
    }
    lastRun.set(new LastRun(clock.instant(), result.succeeded()));
    return result;
  }

  private Result refresh() {
    SortedSet<String> supported = currencies.all();
    Instant fetchedAt = clock.instant();
    int rows = 0;
    List<String> failed = new ArrayList<>();
    for (String base : supported) {
      SortedSet<String> quotes = new TreeSet<>(supported);
      quotes.remove(base);
      if (quotes.isEmpty()) {
        continue;
      }
      try {
        FxQuote quote = provider.fetchLatest(base, quotes);
        Integer written =
            transactions.execute(status -> repository.upsert(quote, fetchedAt, provider.source()));
        rows += written == null ? 0 : written;
        meters.counter("vitadelta.fx.refresh.success").increment();
      } catch (FxProviderException e) {
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
        "{}: {} rows upserted for {} bases, failed bases {}",
        NAME,
        rows,
        supported.size() - failed.size(),
        failed);
    return new Result(true, rows, failed, false);
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
