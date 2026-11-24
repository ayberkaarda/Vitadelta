package com.vitadelta.api.imports;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository of {@link ImportJobError}. */
public interface ImportJobErrorRepository extends JpaRepository<ImportJobError, UUID> {

  List<ImportJobError> findByJobIdOrderByRowNumberAscSeqAsc(UUID jobId);
}
