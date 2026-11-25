package com.vitadelta.api.common.jobs;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs a job under a session-level PostgreSQL advisory lock (ADR-0011).
 *
 * <p>{@link #runExclusive(String, long, Runnable)} takes a <b>dedicated</b> connection from the
 * pool, calls {@code pg_try_advisory_lock(key)} on it and, only when the lock is obtained, runs the
 * task; the lock is released with {@code pg_advisory_unlock(key)} and the connection returned when
 * the task ends, also when it throws. When another session holds the lock, the run is skipped
 * (logged at INFO) and never blocks. The task itself uses its own (pooled) connections and may run
 * several short transactions; it must not be called inside a transaction.
 *
 * <p>Every run that obtains the lock is recorded in the {@link JobRunRegistry}: its start, its end
 * and its outcome ({@code FAILED} when the task throws or calls {@link #reportFailure()}, otherwise
 * {@code SUCCEEDED}).
 *
 * <pre>{@code
 * @Scheduled(cron = "0 5 0 * * *", zone = "UTC")
 * void run() {
 *   jobLock.runExclusive("SubscriptionExpiryJob", JobLockKeys.SUBSCRIPTION_EXPIRY, this::expire);
 * }
 * }</pre>
 */
@Component
public class JobLock {

  private static final Logger LOG = LoggerFactory.getLogger(JobLock.class);

  /** Failure marker of the run executing on the current thread ({@code null} outside a run). */
  private static final ThreadLocal<boolean[]> CURRENT_RUN_FAILED = new ThreadLocal<>();

  private final DataSource dataSource;
  private final JobRunRegistry runs;

  @Autowired
  public JobLock(DataSource dataSource, JobRunRegistry runs) {
    this.dataSource = dataSource;
    this.runs = runs;
  }

  /** A lock with its own registry on the UTC system clock (for code outside the context). */
  public JobLock(DataSource dataSource) {
    this(dataSource, new JobRunRegistry(Clock.systemUTC()));
  }

  /**
   * Marks the run executing on the current thread as {@code FAILED} without ending it, for jobs
   * that handle their errors themselves (for example a refresh in which one part failed). Has no
   * effect outside {@link #runExclusive}.
   */
  public static void reportFailure() {
    boolean[] failed = CURRENT_RUN_FAILED.get();
    if (failed != null) {
      failed[0] = true;
    }
  }

  /**
   * Runs {@code task} if the advisory lock {@code key} can be obtained.
   *
   * @param jobName name used in log lines
   * @return true when the task ran, false when the run was skipped
   * @throws JobLockException when the lock cannot be queried or released
   */
  public boolean runExclusive(String jobName, long key, Runnable task) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("JobLock must not be used inside a transaction: " + jobName);
    }
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(true);
      if (!call(connection, "SELECT pg_try_advisory_lock(?)", key)) {
        LOG.info("Job {} skipped: lock {} is held by another session", jobName, key);
        return false;
      }
      try {
        runRecorded(jobName, key, task);
        return true;
      } finally {
        if (!call(connection, "SELECT pg_advisory_unlock(?)", key)) {
          LOG.warn("Job {}: lock {} was not held at release", jobName, key);
        }
      }
    } catch (SQLException e) {
      throw new JobLockException("advisory lock failed for job " + jobName, e);
    }
  }

  private void runRecorded(String jobName, long key, Runnable task) {
    boolean[] previous = CURRENT_RUN_FAILED.get();
    boolean[] failed = {false};
    CURRENT_RUN_FAILED.set(failed);
    runs.started(key, jobName);
    JobRunRegistry.Outcome outcome = JobRunRegistry.Outcome.FAILED;
    try {
      task.run();
      outcome = failed[0] ? JobRunRegistry.Outcome.FAILED : JobRunRegistry.Outcome.SUCCEEDED;
    } finally {
      if (previous == null) {
        CURRENT_RUN_FAILED.remove();
      } else {
        CURRENT_RUN_FAILED.set(previous);
      }
      runs.finished(key, jobName, outcome);
    }
  }

  private static boolean call(Connection connection, String sql, long key) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, key);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() && result.getBoolean(1);
      }
    }
  }

  /** The advisory lock could not be queried or released. */
  public static class JobLockException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JobLockException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
