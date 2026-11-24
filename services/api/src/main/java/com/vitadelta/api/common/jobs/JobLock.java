package com.vitadelta.api.common.jobs;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  private final DataSource dataSource;

  public JobLock(DataSource dataSource) {
    this.dataSource = dataSource;
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
        task.run();
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
