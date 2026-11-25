package com.vitadelta.api.alerts;

import com.vitadelta.api.common.health.OutboxHealthContributor;
import com.vitadelta.api.common.health.OutboxStats;
import org.springframework.stereotype.Component;

/**
 * Outbox backlog of {@code GET /admin/outbox/stats} and {@code GET /admin/health}: one read-only
 * statement that never claims or changes events.
 */
@Component
public class JdbcOutboxHealthContributor implements OutboxHealthContributor {

  private final OutboxRepository outbox;

  public JdbcOutboxHealthContributor(OutboxRepository outbox) {
    this.outbox = outbox;
  }

  @Override
  public OutboxStats stats() {
    return outbox.stats();
  }
}
