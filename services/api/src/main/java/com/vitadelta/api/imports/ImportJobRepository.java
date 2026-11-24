package com.vitadelta.api.imports;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository of {@link ImportJob}. */
public interface ImportJobRepository extends JpaRepository<ImportJob, UUID> {}
