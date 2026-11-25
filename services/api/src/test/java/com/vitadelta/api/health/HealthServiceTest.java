package com.vitadelta.api.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vitadelta.api.common.HealthStatus;
import com.vitadelta.api.common.health.OutboxHealthContributor;
import com.vitadelta.api.common.health.OutboxStats;
import com.vitadelta.api.common.jobs.JobLock;
import com.vitadelta.api.common.jobs.JobLockKeys;
import com.vitadelta.api.common.jobs.JobRunRegistry;
import com.vitadelta.api.config.properties.HealthProperties;
import com.vitadelta.api.fx.FxFreshness;
import com.vitadelta.api.fx.FxRefreshJob;
import com.vitadelta.api.health.HealthViews.AdminHealth;
import com.vitadelta.api.health.HealthViews.JobRun;
import com.vitadelta.api.recommendation.ScoringHealth;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.ResponseEntity;

/** Aggregation, outbox and job rules and the public cache of {@link HealthService}. */
class HealthServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");

  private final ScoringHealth scoring = mock(ScoringHealth.class);
  private final DataSource dataSource = mock(DataSource.class);
  private final Connection connection = mock(Connection.class);
  private final FxFreshness fx = mock(FxFreshness.class);
  private final FxRefreshJob fxRefresh = mock(FxRefreshJob.class);
  private final AtomicReference<OutboxStats> outbox = new AtomicReference<>(OutboxStats.EMPTY);
  private final JobRunRegistry registry = new JobRunRegistry(Clock.fixed(NOW, ZoneOffset.UTC));
  private final AtomicLong ticker = new AtomicLong();
  private HealthService service;

  @BeforeEach
  void setUp() throws SQLException {
    OutboxHealthContributor contributor =
        () -> {
          OutboxStats stats = outbox.get();
          if (stats == null) {
            throw new IllegalStateException("outbox unreadable");
          }
          return stats;
        };
    service =
        new HealthService(
            scoring,
            dataSource,
            fx,
            fxRefresh,
            contributor,
            registry,
            new HealthProperties(
                Duration.ofSeconds(10), Duration.ofMinutes(5), Duration.ofSeconds(30)),
            Clock.fixed(NOW, ZoneOffset.UTC),
            ticker::get);
    when(scoring.probe()).thenReturn(true);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.isValid(HealthService.DATABASE_CHECK_SECONDS)).thenReturn(true);
    when(fx.current())
        .thenReturn(new FxFreshness.Snapshot("UP", LocalDate.parse("2026-11-24"), 1, false));
    when(fxRefresh.lastRun()).thenReturn(Optional.empty());
  }

  @ParameterizedTest
  @CsvSource({
    "UP, UP, UP, UP, UP",
    "DOWN, UP, UP, UP, DOWN",
    "DOWN, DOWN, DEGRADED, DOWN, DOWN",
    "UP, DOWN, UP, UP, DEGRADED",
    "UP, UP, DEGRADED, UP, DEGRADED",
    "UP, UP, UP, DOWN, DEGRADED",
    "DEGRADED, UP, UP, UP, DEGRADED"
  })
  void theOverallStatusFollowsTheDatabaseFirst(
      HealthStatus database,
      HealthStatus scoringStatus,
      HealthStatus fxStatus,
      HealthStatus outboxStatus,
      HealthStatus expected) {
    assertThat(HealthService.overall(database, List.of(scoringStatus, fxStatus, outboxStatus)))
        .isEqualTo(expected);
  }

  @Test
  void everyComponentUpIsUp() {
    AdminHealth health = service.adminHealth();
    assertThat(health.status()).isEqualTo(HealthStatus.UP);
    assertThat(health.checkedAt()).isEqualTo(NOW);
    assertThat(health.components().fx().newestAsOf()).isEqualTo(LocalDate.parse("2026-11-24"));
    assertThat(health.components().fx().rateAgeDays()).isEqualTo(1);
    assertThat(health.components().outbox().status()).isEqualTo(HealthStatus.UP);
    assertThat(health.jobs())
        .extracting(JobRun::name)
        .containsExactly(
            "OUTBOX_PROCESSOR",
            "FX_REFRESH",
            "SUBSCRIPTION_EXPIRY",
            "HOUSEKEEPING_IDENTITY",
            "HOUSEKEEPING_ALERTS");
    assertThat(health.jobs()).allSatisfy(job -> assertThat(job.lastRunAt()).isNull());
    assertThat(health.jobs()).allSatisfy(job -> assertThat(job.lastOutcome()).isNull());
  }

  @Test
  void anUnreachableDatabaseIsDown() throws SQLException {
    when(dataSource.getConnection()).thenThrow(new SQLException("refused"));
    when(fx.current()).thenThrow(new IllegalStateException("no connection"));
    AdminHealth health = service.adminHealth();
    assertThat(health.components().database().status()).isEqualTo(HealthStatus.DOWN);
    assertThat(health.components().fx().status()).isEqualTo(HealthStatus.DEGRADED);
    assertThat(health.components().fx().newestAsOf()).isNull();
    assertThat(health.status()).isEqualTo(HealthStatus.DOWN);
  }

  @Test
  void anInvalidConnectionIsDown() throws SQLException {
    when(connection.isValid(HealthService.DATABASE_CHECK_SECONDS)).thenReturn(false);
    assertThat(service.adminHealth().components().database().status()).isEqualTo(HealthStatus.DOWN);
  }

  @Test
  void aFailingScoringProbeIsDown() {
    when(scoring.probe()).thenThrow(new IllegalStateException("unexpected"));
    AdminHealth health = service.adminHealth();
    assertThat(health.components().scoring().status()).isEqualTo(HealthStatus.DOWN);
    assertThat(health.status()).isEqualTo(HealthStatus.DEGRADED);
  }

  @Test
  void staleOrMissingRatesDegrade() {
    when(fx.current()).thenReturn(new FxFreshness.Snapshot("DEGRADED", null, null, false));
    assertThat(service.adminHealth().status()).isEqualTo(HealthStatus.DEGRADED);
  }

  @Test
  void theOutboxStatusFollowsFailuresAndBacklogAgeButNotDeadLetters() {
    outbox.set(new OutboxStats(3, 0, 7, NOW.minus(Duration.ofMinutes(5))));
    assertThat(service.adminHealth().components().outbox().status()).isEqualTo(HealthStatus.UP);
    outbox.set(new OutboxStats(3, 0, 0, NOW.minus(Duration.ofMinutes(5)).minusSeconds(1)));
    assertThat(service.adminHealth().components().outbox().status())
        .isEqualTo(HealthStatus.DEGRADED);
    outbox.set(new OutboxStats(3, 1, 0, NOW));
    AdminHealth failed = service.adminHealth();
    assertThat(failed.components().outbox().status()).isEqualTo(HealthStatus.DEGRADED);
    assertThat(failed.components().outbox().pending()).isEqualTo(3);
    assertThat(failed.components().outbox().failed()).isEqualTo(1);
    assertThat(failed.status()).isEqualTo(HealthStatus.DEGRADED);
    outbox.set(null);
    AdminHealth unreadable = service.adminHealth();
    assertThat(unreadable.components().outbox().status()).isEqualTo(HealthStatus.DOWN);
    assertThat(unreadable.status()).isEqualTo(HealthStatus.DEGRADED);
  }

  @Test
  void jobsShowTheEndAndOutcomeOfTheirLastFinishedRun() throws SQLException {
    JobLock lock = jobLockOn(registry);
    lock.runExclusive("SubscriptionExpiryJob", JobLockKeys.SUBSCRIPTION_EXPIRY, () -> {});
    lock.runExclusive(
        "IdentityHousekeepingJob", JobLockKeys.HOUSEKEEPING_IDENTITY, JobLock::reportFailure);
    // The registry would say SUCCEEDED (the FX job handles failed bases itself); its own record
    // of the run counts a failed base as FAILED.
    lock.runExclusive("FxRefreshJob", JobLockKeys.FX_REFRESH, () -> {});
    when(fxRefresh.lastRun())
        .thenReturn(Optional.of(new FxRefreshJob.LastRun(NOW.minusSeconds(30), false)));
    List<JobRun> jobs = service.adminHealth().jobs();
    assertThat(jobs.get(0)).isEqualTo(new JobRun("OUTBOX_PROCESSOR", null, null));
    assertThat(jobs.get(1)).isEqualTo(new JobRun("FX_REFRESH", NOW.minusSeconds(30), "FAILED"));
    assertThat(jobs.get(2)).isEqualTo(new JobRun("SUBSCRIPTION_EXPIRY", NOW, "SUCCEEDED"));
    assertThat(jobs.get(3)).isEqualTo(new JobRun("HOUSEKEEPING_IDENTITY", NOW, "FAILED"));
    assertThat(jobs.get(4)).isEqualTo(new JobRun("HOUSEKEEPING_ALERTS", null, null));

    when(fxRefresh.lastRun()).thenReturn(Optional.empty());
    assertThat(service.adminHealth().jobs().get(1))
        .isEqualTo(new JobRun("FX_REFRESH", NOW, "SUCCEEDED"));
  }

  @Test
  void theFirstRunOfAJobIsShownOnlyWhenItEnded() throws Exception {
    JobLock lock = jobLockOn(registry);
    lock.runExclusive(
        "SubscriptionExpiryJob",
        JobLockKeys.SUBSCRIPTION_EXPIRY,
        () ->
            assertThat(service.adminHealth().jobs().get(2))
                .isEqualTo(new JobRun("SUBSCRIPTION_EXPIRY", null, null)));
    assertThat(service.adminHealth().jobs().get(2).lastOutcome()).isEqualTo("SUCCEEDED");
  }

  @Test
  void thePublicStatusIsCachedForTheConfiguredPeriod() {
    assertThat(service.publicStatus()).isEqualTo(HealthStatus.UP);
    when(scoring.probe()).thenReturn(false);
    ticker.addAndGet(Duration.ofSeconds(9).toNanos());
    assertThat(service.publicStatus()).isEqualTo(HealthStatus.UP);
    verify(scoring, times(1)).probe();
    ticker.addAndGet(Duration.ofSeconds(1).toNanos());
    assertThat(service.publicStatus()).isEqualTo(HealthStatus.DEGRADED);
    verify(scoring, times(2)).probe();
  }

  @Test
  void thePublicEndpointAnswers503OnlyWhenDown() throws SQLException {
    HealthController controller = new HealthController(service);
    ResponseEntity<?> up = controller.health();
    assertThat(up.getStatusCode().value()).isEqualTo(200);
    when(dataSource.getConnection()).thenThrow(new SQLException("refused"));
    ticker.addAndGet(Duration.ofSeconds(10).toNanos());
    ResponseEntity<com.vitadelta.api.common.HealthResponse> down = controller.health();
    assertThat(down.getStatusCode().value()).isEqualTo(503);
    assertThat(down.getBody())
        .isEqualTo(new com.vitadelta.api.common.HealthResponse(HealthStatus.DOWN));
    doReturn(connection).when(dataSource).getConnection();
    when(scoring.probe()).thenReturn(false);
    ticker.addAndGet(Duration.ofSeconds(10).toNanos());
    assertThat(controller.health().getStatusCode().value()).isEqualTo(200);
  }

  @Test
  void outboxStatsAreTheContributorsCounts() {
    OutboxStats stats = new OutboxStats(2, 1, 4, NOW);
    outbox.set(stats);
    assertThat(service.outboxStats()).isEqualTo(stats);
  }

  /** A lock whose connection always obtains and releases the advisory lock. */
  private static JobLock jobLockOn(JobRunRegistry registry) throws SQLException {
    DataSource locks = mock(DataSource.class);
    Connection lockConnection = mock(Connection.class);
    java.sql.PreparedStatement statement = mock(java.sql.PreparedStatement.class);
    java.sql.ResultSet result = mock(java.sql.ResultSet.class);
    when(locks.getConnection()).thenReturn(lockConnection);
    when(lockConnection.prepareStatement(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(statement);
    when(statement.executeQuery()).thenReturn(result);
    when(result.next()).thenReturn(true);
    when(result.getBoolean(1)).thenReturn(true);
    return new JobLock(locks, registry);
  }
}
