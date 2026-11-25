package com.vitadelta.api.health;

import com.vitadelta.api.common.HealthResponse;
import com.vitadelta.api.common.HealthStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public liveness endpoint: the overall status of {@code GET /admin/health} only (cached, see
 * {@link HealthService#publicStatus()}), never a component, count or version. {@code DOWN} is
 * answered with 503.
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

  private final HealthService health;

  public HealthController(HealthService health) {
    this.health = health;
  }

  @GetMapping(path = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<HealthResponse> health() {
    HealthStatus status = health.publicStatus();
    return ResponseEntity.status(
            status == HealthStatus.DOWN ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK)
        .body(new HealthResponse(status));
  }
}
