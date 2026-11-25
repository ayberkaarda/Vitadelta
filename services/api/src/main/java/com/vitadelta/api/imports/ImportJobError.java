package com.vitadelta.api.imports;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** One row error of an import job; the message starts with the error code. */
@Entity
@Table(name = "import_job_error")
public class ImportJobError {

  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "job_id", nullable = false)
  private UUID jobId;

  @Column(name = "row_number", nullable = false)
  private int rowNumber;

  @Column(name = "message", nullable = false, length = 2000)
  private String message;

  @Column(name = "seq", nullable = false)
  private int seq;

  protected ImportJobError() {}

  /**
   * @param seq detection order of the error within its job (0-based)
   */
  public ImportJobError(UUID id, UUID jobId, int rowNumber, String message, int seq) {
    this.id = id;
    this.jobId = jobId;
    this.rowNumber = rowNumber;
    this.message = message;
    this.seq = seq;
  }

  public UUID getId() {
    return id;
  }

  public UUID getJobId() {
    return jobId;
  }

  public int getRowNumber() {
    return rowNumber;
  }

  public String getMessage() {
    return message;
  }

  public int getSeq() {
    return seq;
  }
}
