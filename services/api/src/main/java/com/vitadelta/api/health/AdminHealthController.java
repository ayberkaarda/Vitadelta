package com.vitadelta.api.health;

import com.vitadelta.api.common.health.OutboxStats;
import com.vitadelta.api.health.HealthViews.AdminHealth;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator status ({@code GET /admin/health}, always 200 with the body, evaluated per call) and the
 * outbox backlog ({@code GET /admin/outbox/stats}, one read-only query). Role ADMIN.
 */
@RestController
@RequestMapping(path = "/api/v1/admin", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasRole('ADMIN')")
public class AdminHealthController {

  private final HealthService health;

  public AdminHealthController(HealthService health) {
    this.health = health;
  }

  @GetMapping("/health")
  public AdminHealth health() {
    return health.adminHealth();
  }

  @GetMapping("/outbox/stats")
  public OutboxStats outboxStats() {
    return health.outboxStats();
  }
}
