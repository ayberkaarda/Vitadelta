package com.vitadelta.api.imports;

import com.vitadelta.api.common.jobs.JobLock;
import com.vitadelta.api.common.jobs.JobLockKeys;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Start-up recovery of the import pipeline: a job left {@code RUNNING} by a crash or an interrupted
 * shutdown can never finish (its bytes lived only in memory), so it is marked {@code FAILED} with
 * one {@code import_failed} error. The recovery takes the import lock {@link
 * JobLockKeys#CATALOG_IMPORT}; when another session holds it (an import is running there), the
 * recovery is skipped, so a live import is never failed.
 */
@Component
@Profile("!seed")
public class ImportJobRecovery implements ApplicationRunner {

  private static final Logger LOG = LoggerFactory.getLogger(ImportJobRecovery.class);

  static final String REASON = "the import was interrupted by a restart";

  private final ImportJobStore store;
  private final JobLock jobLock;

  public ImportJobRecovery(ImportJobStore store, JobLock jobLock) {
    this.store = store;
    this.jobLock = jobLock;
  }

  @Override
  public void run(ApplicationArguments args) {
    recover();
  }

  /**
   * Fails the jobs left running.
   *
   * @return the number of jobs failed, or -1 when the import lock was held elsewhere
   */
  public int recover() {
    AtomicInteger failed = new AtomicInteger();
    boolean ran =
        jobLock.runExclusive(
            "ImportJobRecovery",
            JobLockKeys.CATALOG_IMPORT,
            () -> failed.set(store.failAllRunning(REASON)));
    if (!ran) {
      return -1;
    }
    if (failed.get() > 0) {
      LOG.warn("Import recovery: {} interrupted import job(s) marked FAILED", failed.get());
    }
    return failed.get();
  }
}
