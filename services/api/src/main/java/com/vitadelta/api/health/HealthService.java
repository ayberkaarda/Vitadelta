package com.vitadelta.api.health;

import com.vitadelta.api.common.HealthStatus;
import com.vitadelta.api.common.health.OutboxHealthContributor;
import com.vitadelta.api.common.health.OutboxStats;
import com.vitadelta.api.common.jobs.JobLockKeys;
import com.vitadelta.api.common.jobs.JobRunRegistry;
import com.vitadelta.api.config.properties.HealthProperties;
import com.vitadelta.api.fx.FxFreshness;
import com.vitadelta.api.fx.FxRefreshJob;
import com.vitadelta.api.health.HealthViews.AdminHealth;
import com.vitadelta.api.health.HealthViews.AdminHealthComponents;
import com.vitadelta.api.health.HealthViews.ComponentHealth;
import com.vitadelta.api.health.HealthViews.FxHealth;
import com.vitadelta.api.health.HealthViews.JobRun;
import com.vitadelta.api.health.HealthViews.OutboxHealth;
import com.vitadelta.api.recommendation.ScoringHealth;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Status of the service and its dependencies (contract operations {@code GET /admin/health} and
 * {@code GET /health}).
 *
 * <ul>
 *   <li>{@code scoring}: {@link ScoringHealth#probe()} ({@code UP} or {@code DOWN}).
 *   <li>{@code database}: {@code UP} when a pooled connection is valid within {@value
 *       #DATABASE_CHECK_SECONDS} s, else {@code DOWN}.
 *   <li>{@code fx}: {@link FxFreshness#current()}; {@code DEGRADED} without readable rates.
 *   <li>{@code outbox}: the counts of the {@link OutboxHealthContributor}; {@code DEGRADED} when
 *       {@code failed > 0} or the oldest pending event is older than {@code
 *       vitadelta.health.outbox-max-pending-age}, {@code DOWN} when the counts cannot be read; dead
 *       letters do not change the status.
 *   <li>Overall: {@code DOWN} when the database is {@code DOWN}; otherwise {@code DEGRADED} when
 *       any component is not {@code UP}; otherwise {@code UP}.
 *   <li>{@code jobs}: the five scheduled jobs in contract order with the end time and outcome of
 *       their last run that obtained the advisory lock ({@link JobRunRegistry}). For {@code
 *       FX_REFRESH} the outcome recorded by {@link FxRefreshJob#lastRun()} is used when present,
 *       because that job handles failed bases itself and counts them as a failed run.
 * </ul>
 *
 * <p>{@link #adminHealth()} is evaluated on every call. {@link #publicStatus()} reuses the overall
 * status for {@code vitadelta.health.public-cache-ttl}; one evaluation runs at a time, so public
 * polling never multiplies calls to the scoring service or the database.
 */
@Service
public class HealthService {

  /** Seconds a database connection may take to prove itself valid. */
  static final int DATABASE_CHECK_SECONDS = 2;

  private static final Logger LOG = LoggerFactory.getLogger(HealthService.class);

  /** Contract job names in contract order, with their advisory-lock keys. */
  static final Map<String, Long> JOBS;

  static {
    Map<String, Long> jobs = new LinkedHashMap<>();
    jobs.put("OUTBOX_PROCESSOR", JobLockKeys.OUTBOX_PROCESSOR);
    jobs.put("FX_REFRESH", JobLockKeys.FX_REFRESH);
    jobs.put("SUBSCRIPTION_EXPIRY", JobLockKeys.SUBSCRIPTION_EXPIRY);
    jobs.put("HOUSEKEEPING_IDENTITY", JobLockKeys.HOUSEKEEPING_IDENTITY);
    jobs.put("HOUSEKEEPING_ALERTS", JobLockKeys.HOUSEKEEPING_ALERTS);
    JOBS = Collections.unmodifiableMap(jobs);
  }

  private final ScoringHealth scoring;
  private final DataSource dataSource;
  private final FxFreshness fxFreshness;
  private final FxRefreshJob fxRefresh;
  private final OutboxHealthContributor outbox;
  private final JobRunRegistry jobRuns;
  private final HealthProperties properties;
  private final Clock clock;
  private final LongSupplier ticker;

  private final Object publicLock = new Object();
  private HealthStatus publicStatus;
  private long publicComputedAt;

  @Autowired
  public HealthService(
      ScoringHealth scoring,
      DataSource dataSource,
      FxFreshness fxFreshness,
      FxRefreshJob fxRefresh,
      OutboxHealthContributor outbox,
      JobRunRegistry jobRuns,
      HealthProperties properties,
      Clock clock) {
    this(
        scoring,
        dataSource,
        fxFreshness,
        fxRefresh,
        outbox,
        jobRuns,
        properties,
        clock,
        System::nanoTime);
  }

  HealthService(
      ScoringHealth scoring,
      DataSource dataSource,
      FxFreshness fxFreshness,
      FxRefreshJob fxRefresh,
      OutboxHealthContributor outbox,
      JobRunRegistry jobRuns,
      HealthProperties properties,
      Clock clock,
      LongSupplier ticker) {
    this.scoring = scoring;
    this.dataSource = dataSource;
    this.fxFreshness = fxFreshness;
    this.fxRefresh = fxRefresh;
    this.outbox = outbox;
    this.jobRuns = jobRuns;
    this.properties = properties;
    this.clock = clock;
    this.ticker = ticker;
  }

  /** {@code GET /admin/health}: every component evaluated now. */
  public AdminHealth adminHealth() {
    ComponentHealth scoringHealth = new ComponentHealth(scoringStatus());
    ComponentHealth database = new ComponentHealth(databaseStatus());
    FxHealth fx = fxHealth();
    OutboxHealth outboxHealth = outboxHealth();
    HealthStatus overall =
        overall(
            database.status(), List.of(scoringHealth.status(), fx.status(), outboxHealth.status()));
    return new AdminHealth(
        overall,
        clock.instant(),
        new AdminHealthComponents(scoringHealth, database, fx, outboxHealth),
        jobs());
  }

  /** {@code GET /health}: the overall status, computed at most once per cache period. */
  public HealthStatus publicStatus() {
    synchronized (publicLock) {
      long now = ticker.getAsLong();
      if (publicStatus == null || now - publicComputedAt >= properties.publicCacheTtl().toNanos()) {
        publicStatus = adminHealth().status();
        publicComputedAt = ticker.getAsLong();
      }
      return publicStatus;
    }
  }

  /** The outbox counts ({@code GET /admin/outbox/stats}); errors propagate. */
  public OutboxStats outboxStats() {
    return outbox.stats();
  }

  /** The overall status rule. */
  static HealthStatus overall(HealthStatus database, List<HealthStatus> others) {
    if (database == HealthStatus.DOWN) {
      return HealthStatus.DOWN;
    }
    if (database != HealthStatus.UP || others.stream().anyMatch(s -> s != HealthStatus.UP)) {
      return HealthStatus.DEGRADED;
    }
    return HealthStatus.UP;
  }

  private HealthStatus scoringStatus() {
    try {
      return scoring.probe() ? HealthStatus.UP : HealthStatus.DOWN;
    } catch (RuntimeException e) {
      LOG.warn("Scoring health check failed: {}", e.getClass().getSimpleName());
      return HealthStatus.DOWN;
    }
  }

  private HealthStatus databaseStatus() {
    try (Connection connection = dataSource.getConnection()) {
      return connection.isValid(DATABASE_CHECK_SECONDS) ? HealthStatus.UP : HealthStatus.DOWN;
    } catch (SQLException | RuntimeException e) {
      LOG.warn("Database health check failed: {}", e.getClass().getSimpleName());
      return HealthStatus.DOWN;
    }
  }

  private FxHealth fxHealth() {
    try {
      FxFreshness.Snapshot snapshot = fxFreshness.current();
      return new FxHealth(
          HealthStatus.valueOf(snapshot.status()),
          snapshot.newestAsOf(),
          snapshot.rateAgeDays(),
          snapshot.stale());
    } catch (RuntimeException e) {
      LOG.warn("FX health check failed: {}", e.getClass().getSimpleName());
      return new FxHealth(HealthStatus.DEGRADED, null, null, false);
    }
  }

  private OutboxHealth outboxHealth() {
    OutboxStats stats;
    try {
      stats = outbox.stats();
    } catch (RuntimeException e) {
      LOG.warn("Outbox health check failed: {}", e.getClass().getSimpleName());
      return new OutboxHealth(0, 0, 0, null, HealthStatus.DOWN);
    }
    Instant oldestAllowed = clock.instant().minus(properties.outboxMaxPendingAge());
    boolean backlog =
        stats.failed() > 0
            || (stats.oldestPendingAt() != null && stats.oldestPendingAt().isBefore(oldestAllowed));
    return new OutboxHealth(
        stats.pending(),
        stats.failed(),
        stats.deadLetter(),
        stats.oldestPendingAt(),
        backlog ? HealthStatus.DEGRADED : HealthStatus.UP);
  }

  private List<JobRun> jobs() {
    List<JobRun> jobs = new ArrayList<>(JOBS.size());
    JOBS.forEach((name, key) -> jobs.add(job(name, key)));
    return jobs;
  }

  private JobRun job(String name, long key) {
    if (key == JobLockKeys.FX_REFRESH) {
      var fx = fxRefresh.lastRun();
      if (fx.isPresent()) {
        return new JobRun(
            name,
            fx.get().endedAt(),
            (fx.get().succeeded()
                    ? JobRunRegistry.Outcome.SUCCEEDED
                    : JobRunRegistry.Outcome.FAILED)
                .name());
      }
    }
    return jobRuns
        .lastRun(key)
        .filter(run -> run.lastFinishedAt() != null)
        .map(run -> new JobRun(name, run.lastFinishedAt(), run.lastOutcome().name()))
        .orElseGet(() -> new JobRun(name, null, null));
  }
}
