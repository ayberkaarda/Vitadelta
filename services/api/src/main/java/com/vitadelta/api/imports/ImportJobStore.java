package com.vitadelta.api.imports;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Rows of {@code import_job} and {@code import_job_error}. A job is created {@code RUNNING} and
 * leaves that state exactly once: every finishing update is conditional on {@code status =
 * 'RUNNING'}.
 */
@Component
public class ImportJobStore {

  /** Longest stored error message (column {@code import_job_error.message}). */
  static final int MAX_MESSAGE_LENGTH = 2000;

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Pattern CSV_ROW = Pattern.compile("^row ([0-9]{1,9})$");
  private static final Pattern PRODUCT_INDEX = Pattern.compile("^products\\[([0-9]{1,9})\\]");

  /** A job with its errors (contract schema {@code ImportJobDetail}). */
  public record JobDetail(ImportJob job, Map<String, Long> summary, List<ImportJobError> errors) {}

  private final ImportJobRepository jobs;
  private final ImportJobErrorRepository errors;
  private final JdbcClient jdbc;
  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate newTransaction;
  private final Clock clock;

  public ImportJobStore(
      ImportJobRepository jobs,
      ImportJobErrorRepository errors,
      JdbcClient jdbc,
      JdbcTemplate jdbcTemplate,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.jobs = jobs;
    this.errors = errors;
    this.jdbc = jdbc;
    this.jdbcTemplate = jdbcTemplate;
    this.newTransaction = new TransactionTemplate(transactionManager);
    this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.clock = clock;
  }

  /** Creates a {@code RUNNING} job in its own transaction. */
  public ImportJob create(ImportJob.Kind kind) {
    return newTransaction.execute(status -> {
      ImportJob job = new ImportJob(UUID.randomUUID(), kind, clock.instant());
      return jobs.saveAndFlush(job);
    });
  }

  /**
   * Marks a running job {@code SUCCEEDED} inside the caller's transaction (the import's write
   * transaction), so the job outcome commits atomically with the catalog writes.
   *
   * @throws IllegalStateException when the job is no longer running; the caller's transaction is
   *     then rolled back
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void succeed(UUID jobId, Map<String, Integer> summary) {
    int updated =
        jdbc.sql(
                "UPDATE import_job SET status = 'SUCCEEDED', finished_at = :at,"
                    + " summary = CAST(:summary AS jsonb) WHERE id = :id AND status = 'RUNNING'")
            .param("at", Timestamp.from(clock.instant()))
            .param("summary", JSON.writeValueAsString(summary))
            .param("id", jobId)
            .update();
    if (updated != 1) {
      throw new IllegalStateException("import job is not running: " + jobId);
    }
  }

  /**
   * Marks a running job {@code FAILED} with its errors in its own transaction; does nothing when
   * the job has already finished.
   *
   * @return whether the job was failed by this call
   */
  public boolean fail(UUID jobId, List<ImportProblem> problems, Map<String, Integer> summary) {
    Boolean failed =
        newTransaction.execute(status -> {
          Instant now = clock.instant();
          int updated =
              jdbc.sql(
                      "UPDATE import_job SET status = 'FAILED', finished_at = :at,"
                          + " summary = CAST(:summary AS jsonb)"
                          + " WHERE id = :id AND status = 'RUNNING'")
                  .param("at", Timestamp.from(now))
                  .param("summary", JSON.writeValueAsString(summary))
                  .param("id", jobId)
                  .update();
          if (updated != 1) {
            return false;
          }
          insertErrors(jobId, problems);
          return true;
        });
    return Boolean.TRUE.equals(failed);
  }

  /** Whether the job exists and is still {@code RUNNING}. */
  public boolean isRunning(UUID jobId) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM import_job WHERE id = ? AND status = 'RUNNING')")
        .param(jobId)
        .query(Boolean.class)
        .single();
  }

  /**
   * Fails every job that is still {@code RUNNING} (start-up recovery after a crash or a shutdown
   * that interrupted imports), each with one {@code import_failed} error.
   *
   * @return the number of jobs failed
   */
  public int failAllRunning(String reason) {
    List<UUID> running =
        jdbc.sql("SELECT id FROM import_job WHERE status = 'RUNNING' ORDER BY created_at")
            .query(UUID.class)
            .list();
    int failed = 0;
    for (UUID id : running) {
      Map<String, Integer> summary = new LinkedHashMap<>();
      if (fail(id, List.of(new ImportProblem("row 1", "import_failed", reason)), summary)) {
        failed++;
      }
    }
    return failed;
  }

  /** A job with its summary and errors (errors ordered by row number, then detection order). */
  @Transactional(readOnly = true)
  public Optional<JobDetail> detail(UUID jobId) {
    return jobs.findById(jobId)
        .map(job ->
            new JobDetail(
                job,
                job.getSummary() == null
                    ? null
                    : JSON.readValue(job.getSummary(), new TypeReference<Map<String, Long>>() {}),
                errors.findByJobIdOrderByRowNumberAscSeqAsc(jobId)));
  }

  private void insertErrors(UUID jobId, List<ImportProblem> problems) {
    List<Object[]> rows = new ArrayList<>(problems.size());
    for (int i = 0; i < problems.size(); i++) {
      ImportProblem problem = problems.get(i);
      rows.add(new Object[] {UUID.randomUUID(), jobId, rowNumber(problem), message(problem), i});
    }
    jdbcTemplate.batchUpdate(
        "INSERT INTO import_job_error (id, job_id, row_number, message, seq)"
            + " VALUES (?, ?, ?, ?, ?)",
        rows);
  }

  /**
   * The public row number of a problem: the CSV record number for {@code row N} locations, the
   * 1-based product position for {@code products[i]...} paths, otherwise 1 (document level).
   */
  static int rowNumber(ImportProblem problem) {
    Matcher row = CSV_ROW.matcher(problem.location());
    if (row.matches()) {
      return Math.max(1, Integer.parseInt(row.group(1)));
    }
    Matcher product = PRODUCT_INDEX.matcher(problem.location());
    if (product.find()) {
      return Integer.parseInt(product.group(1)) + 1;
    }
    return 1;
  }

  /** {@code code: detail (location)}, cut to the column length. */
  static String message(ImportProblem problem) {
    String text = problem.describe();
    return text.length() <= MAX_MESSAGE_LENGTH ? text : text.substring(0, MAX_MESSAGE_LENGTH);
  }

  /** The error code of a stored message: its prefix before {@code ": "}. */
  public static String code(String message) {
    int colon = message.indexOf(": ");
    return colon > 0 ? message.substring(0, colon) : message;
  }
}
