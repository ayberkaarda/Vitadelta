package com.vitadelta.api.common.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitadelta.api.support.PostgresIntegrationTest;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Failure paths of {@link JobLock} without a Spring context: an unreachable database is reported as
 * {@link JobLock.JobLockException} without running the task, and a lock that was released behind
 * the job's back is logged at release instead of failing the run.
 */
class JobLockFailureTest extends PostgresIntegrationTest {

  private static final long KEY = 0x5644_4A42_0000_7E60L;

  /** A data source whose connections cannot be obtained. */
  private static final class UnreachableDataSource implements DataSource {

    @Override
    public Connection getConnection() throws SQLException {
      throw new SQLException("database unreachable");
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      return getConnection();
    }

    @Override
    public PrintWriter getLogWriter() {
      return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
      // The data source never logs.
    }

    @Override
    public void setLoginTimeout(int seconds) {
      // No connections are ever opened.
    }

    @Override
    public int getLoginTimeout() {
      return 0;
    }

    @Override
    public Logger getParentLogger() {
      return Logger.getGlobal();
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
      throw new SQLException("not a wrapper");
    }

    @Override
    public boolean isWrapperFor(Class<?> type) {
      return false;
    }
  }

  @Test
  void anUnreachableDatabaseFailsTheRunWithoutRunningTheTask() {
    AtomicInteger runs = new AtomicInteger();
    JobLock lock = new JobLock(new UnreachableDataSource());
    assertThatThrownBy(() -> lock.runExclusive("unreachable", KEY, runs::incrementAndGet))
        .isInstanceOf(JobLock.JobLockException.class)
        .hasMessage("advisory lock failed for job unreachable")
        .hasCauseInstanceOf(SQLException.class);
    assertThat(runs.get()).isZero();
  }

  @Test
  void aLockReleasedByTheTaskIsReportedAtReleaseAndTheRunStillCounts() {
    SingleConnectionDataSource shared =
        new SingleConnectionDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
    try {
      JobLock lock = new JobLock(shared);
      JdbcTemplate sameSession = new JdbcTemplate(shared);
      boolean ran =
          lock.runExclusive(
              "self-releasing",
              KEY,
              () ->
                  assertThat(
                          sameSession.queryForObject(
                              "SELECT pg_advisory_unlock(?)", Boolean.class, KEY))
                      .isTrue());
      assertThat(ran).isTrue();
      assertThat(sameSession.queryForObject("SELECT pg_try_advisory_lock(?)", Boolean.class, KEY))
          .isTrue();
      assertThat(sameSession.queryForObject("SELECT pg_advisory_unlock(?)", Boolean.class, KEY))
          .isTrue();
    } finally {
      shared.destroy();
    }
  }
}
