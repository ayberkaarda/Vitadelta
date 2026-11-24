package com.vitadelta.api.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitadelta.api.support.PostgresIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ImportJobPersistenceTest extends PostgresIntegrationTest {

  @Autowired private ImportJobRepository jobs;
  @Autowired private ImportJobErrorRepository errors;

  @Test
  void jobAndErrorsRoundTrip() {
    Instant created = Instant.now().truncatedTo(ChronoUnit.MICROS);
    ImportJob job = new ImportJob(UUID.randomUUID(), ImportJob.Kind.OFFERS_CSV, created);
    assertThat(job.getStatus()).isEqualTo(ImportJob.Status.RUNNING);
    jobs.saveAndFlush(job);
    errors.saveAndFlush(new ImportJobError(UUID.randomUUID(), job.getId(), 3, "invalid_price: x"));
    errors.saveAndFlush(new ImportJobError(UUID.randomUUID(), job.getId(), 2, "missing_value: y"));

    ImportJob stored = jobs.findById(job.getId()).orElseThrow();
    stored.finish(ImportJob.Status.FAILED, created.plusSeconds(1), "{\"rows\": 2}");
    jobs.saveAndFlush(stored);

    ImportJob reloaded = jobs.findById(job.getId()).orElseThrow();
    assertThat(reloaded.getKind()).isEqualTo(ImportJob.Kind.OFFERS_CSV);
    assertThat(reloaded.getStatus()).isEqualTo(ImportJob.Status.FAILED);
    assertThat(reloaded.getCreatedAt()).isEqualTo(created);
    assertThat(reloaded.getFinishedAt()).isEqualTo(created.plusSeconds(1));
    assertThat(reloaded.getSummary()).contains("\"rows\"");
    assertThat(errors.findByJobIdOrderByRowNumberAscIdAsc(job.getId()))
        .extracting(ImportJobError::getRowNumber, ImportJobError::getMessage)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(2, "missing_value: y"),
            org.assertj.core.groups.Tuple.tuple(3, "invalid_price: x"));
    assertThat(errors.findByJobIdOrderByRowNumberAscIdAsc(job.getId()).getFirst().getJobId())
        .isEqualTo(job.getId());
    assertThat(errors.findByJobIdOrderByRowNumberAscIdAsc(job.getId()).getFirst().getId())
        .isNotNull();
  }

  @Test
  void jobFinishesExactlyOnce() {
    ImportJob job = new ImportJob(UUID.randomUUID(), ImportJob.Kind.PRODUCTS_JSON, Instant.now());
    assertThatThrownBy(() -> job.finish(ImportJob.Status.RUNNING, Instant.now(), null))
        .isInstanceOf(IllegalArgumentException.class);
    job.finish(ImportJob.Status.SUCCEEDED, Instant.now(), "{}");
    assertThatThrownBy(() -> job.finish(ImportJob.Status.FAILED, Instant.now(), "{}"))
        .isInstanceOf(IllegalStateException.class);
  }
}
