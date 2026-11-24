package com.vitadelta.api.imports;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One run of an admin import; its row errors are {@link ImportJobError} rows. */
@Entity
@Table(name = "import_job")
public class ImportJob {

  /** Kind of imported document. */
  public enum Kind {
    PRODUCTS_JSON,
    OFFERS_CSV
  }

  /** Lifecycle of a job; a job leaves {@link #RUNNING} exactly once. */
  public enum Status {
    RUNNING,
    SUCCEEDED,
    FAILED
  }

  @Id
  @Column(name = "id")
  private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 20)
  private Kind kind;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private Status status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "finished_at")
  private Instant finishedAt;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "summary", columnDefinition = "jsonb")
  private String summary;

  protected ImportJob() {}

  /** A new running job. */
  public ImportJob(UUID id, Kind kind, Instant createdAt) {
    this.id = id;
    this.kind = kind;
    this.status = Status.RUNNING;
    this.createdAt = createdAt;
  }

  /**
   * Ends the job.
   *
   * @param summaryJson job summary as a JSON object text
   * @throws IllegalStateException when the job has already finished
   * @throws IllegalArgumentException when {@code outcome} is {@link Status#RUNNING}
   */
  public void finish(Status outcome, Instant at, String summaryJson) {
    if (status != Status.RUNNING) {
      throw new IllegalStateException("import job already finished: " + id);
    }
    if (outcome == Status.RUNNING) {
      throw new IllegalArgumentException("a job cannot finish as RUNNING");
    }
    this.status = outcome;
    this.finishedAt = at;
    this.summary = summaryJson;
  }

  public UUID getId() {
    return id;
  }

  public Kind getKind() {
    return kind;
  }

  public Status getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getFinishedAt() {
    return finishedAt;
  }

  public String getSummary() {
    return summary;
  }
}
