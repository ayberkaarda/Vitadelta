package com.vitadelta.api.common.health;

/**
 * Read seam of the event outbox for {@code GET /admin/health} ({@code components.outbox}) and
 * {@code GET /admin/outbox/stats}. The module that owns the outbox tables provides exactly one bean
 * of this type; its {@link #stats()} runs one read-only query and never claims or changes events.
 * The health service derives the status from the counts ({@code DEGRADED} when {@code failed > 0}
 * or the oldest pending event is older than {@code vitadelta.health.outbox-max-pending-age}).
 *
 * <p>Without such a bean, {@link HealthSeamConfig} registers a default that reports {@link
 * OutboxStats#EMPTY}: no outbox table exists, so no event is pending, failed or dead.
 */
@FunctionalInterface
public interface OutboxHealthContributor {

  /**
   * The current counts.
   *
   * @throws RuntimeException when the counts cannot be read (reported as {@code DOWN})
   */
  OutboxStats stats();
}
