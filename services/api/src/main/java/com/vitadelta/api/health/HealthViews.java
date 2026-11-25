package com.vitadelta.api.health;

import com.vitadelta.api.common.HealthStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Response bodies of {@code GET /admin/health} (contract schema {@code AdminHealth}). */
public final class HealthViews {

  private HealthViews() {}

  /** Contract schema {@code ComponentHealth}. */
  public record ComponentHealth(HealthStatus status) {}

  /** Contract schema {@code FxHealth}. */
  public record FxHealth(
      HealthStatus status, LocalDate newestAsOf, Integer rateAgeDays, boolean stale) {}

  /** Contract schema {@code OutboxHealth}. */
  public record OutboxHealth(
      long pending, long failed, long deadLetter, Instant oldestPendingAt, HealthStatus status) {}

  /** The {@code components} object of {@code AdminHealth} (inline in the contract). */
  public record AdminHealthComponents(
      ComponentHealth scoring, ComponentHealth database, FxHealth fx, OutboxHealth outbox) {}

  /** Contract schema {@code JobRun}. */
  public record JobRun(
      @Schema(
              allowableValues = {
                "OUTBOX_PROCESSOR",
                "FX_REFRESH",
                "SUBSCRIPTION_EXPIRY",
                "HOUSEKEEPING_IDENTITY",
                "HOUSEKEEPING_ALERTS"
              })
          String name,
      Instant lastRunAt,
      @Schema(allowableValues = {"SUCCEEDED", "FAILED"}) String lastOutcome) {}

  /** Contract schema {@code AdminHealth}. */
  public record AdminHealth(
      HealthStatus status,
      Instant checkedAt,
      AdminHealthComponents components,
      List<JobRun> jobs) {}
}
