package com.vitadelta.api.common.jobs;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * In-memory record of the job runs since start, fed by {@link JobLock}: for every advisory-lock key
 * the start of the last run that obtained the lock, and the end and outcome of the last finished
 * one. Runs skipped because the lock was held elsewhere are not recorded. Input of the {@code jobs}
 * list of {@code GET /admin/health}. Thread-safe; nothing is persisted, so the record starts empty
 * after every restart.
 */
@Component
public class JobRunRegistry {

  /** Outcome of a finished run (contract schema {@code JobRun.lastOutcome}). */
  public enum Outcome {
    /** The task returned normally and did not report a failure. */
    SUCCEEDED,
    /** The task threw, or reported a failure through {@link JobLock#reportFailure()}. */
    FAILED
  }

  /**
   * What is known about one job.
   *
   * @param jobName name of the job in log lines (of the last run)
   * @param lastStartedAt start of the last run that obtained the lock
   * @param lastFinishedAt end of the last finished run, {@code null} while the first one runs
   * @param lastOutcome outcome of the last finished run, {@code null} while the first one runs
   */
  public record JobRun(
      String jobName, Instant lastStartedAt, Instant lastFinishedAt, Outcome lastOutcome) {}

  private final ConcurrentMap<Long, JobRun> runs = new ConcurrentHashMap<>();
  private final Clock clock;

  public JobRunRegistry(Clock clock) {
    this.clock = clock;
  }

  /** Records the start of a run that obtained the lock {@code key}. */
  void started(long key, String jobName) {
    Instant now = clock.instant();
    runs.compute(
        key,
        (k, previous) ->
            previous == null
                ? new JobRun(jobName, now, null, null)
                : new JobRun(jobName, now, previous.lastFinishedAt(), previous.lastOutcome()));
  }

  /** Records the end of a run of the lock {@code key}. */
  void finished(long key, String jobName, Outcome outcome) {
    Instant now = clock.instant();
    runs.compute(
        key,
        (k, previous) ->
            new JobRun(jobName, previous == null ? now : previous.lastStartedAt(), now, outcome));
  }

  /** The record of the job with the advisory-lock key {@code key}; empty before its first run. */
  public Optional<JobRun> lastRun(long key) {
    return Optional.ofNullable(runs.get(key));
  }
}
