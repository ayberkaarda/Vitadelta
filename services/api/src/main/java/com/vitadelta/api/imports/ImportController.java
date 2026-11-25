package com.vitadelta.api.imports;

import com.vitadelta.api.common.problem.FieldViolation;
import com.vitadelta.api.common.problem.ProblemException;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin import endpoints (role ADMIN; the route matrix also protects {@code /admin/**}). Imports
 * are asynchronous: the response is {@code 202} with the job and its URL in {@code Location}; the
 * outcome, summary and row errors are read with {@code GET /admin/imports/{jobId}}. Bodies are
 * limited to 20 MB (413). Content errors are job errors, never synchronous 422s; only an empty
 * products body or a missing {@code file} part is a {@code 422 validation_failed}.
 */
@RestController
@RequestMapping(path = "/api/v1/admin/imports", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasRole('ADMIN')")
public class ImportController {

  private static final String BASE_PATH = "/api/v1/admin/imports/";

  /** Contract schema {@code ImportJob}. */
  public record ImportJobView(
      UUID id,
      ImportJob.Kind kind,
      ImportJob.Status status,
      Instant createdAt,
      Instant finishedAt,
      Map<String, Long> summary) {}

  /** Contract schema {@code ImportJobError}. */
  public record ImportJobErrorView(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int rowNumber,
      @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String code,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String message) {}

  /** Contract schema {@code ImportJobDetail}. */
  public record ImportJobDetailView(
      UUID id,
      ImportJob.Kind kind,
      ImportJob.Status status,
      Instant createdAt,
      Instant finishedAt,
      Map<String, Long> summary,
      List<ImportJobErrorView> errors) {}

  private final ImportService imports;
  private final ImportJobStore store;

  public ImportController(ImportService imports, ImportJobStore store) {
    this.imports = imports;
    this.store = store;
  }

  @PostMapping(path = "/products", consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.ACCEPTED)
  public ResponseEntity<ImportJobView> importProducts(@RequestBody(required = false) byte[] body) {
    if (body == null || body.length == 0) {
      throw ProblemException.validation(
          "The request contains invalid fields.",
          List.of(new FieldViolation("body", "required", "A products document is required.")));
    }
    return accepted(imports.submit(ImportJob.Kind.PRODUCTS_JSON, body));
  }

  @PostMapping(path = "/offers", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.ACCEPTED)
  public ResponseEntity<ImportJobView> importOffers(
      // Checked here (422 with a field error); documented as required like the contract.
      @Parameter(required = true, schema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
          @RequestPart(name = "file", required = false)
          MultipartFile file) {
    if (file == null) {
      throw ProblemException.validation(
          "The request contains invalid fields.",
          List.of(new FieldViolation("file", "required", "An offers CSV file is required.")));
    }
    byte[] content;
    try {
      content = file.getBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read the uploaded file", e);
    }
    return accepted(imports.submit(ImportJob.Kind.OFFERS_CSV, content));
  }

  @GetMapping("/{jobId}")
  public ImportJobDetailView job(@PathVariable("jobId") String jobId) {
    ImportJobStore.JobDetail detail =
        parse(jobId).flatMap(store::detail).orElseThrow(ImportController::notFound);
    ImportJob job = detail.job();
    return new ImportJobDetailView(
        job.getId(),
        job.getKind(),
        job.getStatus(),
        job.getCreatedAt(),
        job.getFinishedAt(),
        detail.summary(),
        detail.errors().stream()
            .map(e ->
                new ImportJobErrorView(
                    e.getRowNumber(), ImportJobStore.code(e.getMessage()), e.getMessage()))
            .toList());
  }

  private ResponseEntity<ImportJobView> accepted(ImportJob job) {
    Map<String, Long> summary =
        job.getStatus() == ImportJob.Status.RUNNING
            ? null
            : store.detail(job.getId()).map(ImportJobStore.JobDetail::summary).orElse(null);
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .location(URI.create(BASE_PATH + job.getId()))
        .body(
            new ImportJobView(
                job.getId(),
                job.getKind(),
                job.getStatus(),
                job.getCreatedAt(),
                job.getFinishedAt(),
                summary));
  }

  private static Optional<UUID> parse(String id) {
    try {
      UUID parsed = UUID.fromString(id);
      return parsed.toString().equalsIgnoreCase(id) ? Optional.of(parsed) : Optional.empty();
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND);
  }
}
