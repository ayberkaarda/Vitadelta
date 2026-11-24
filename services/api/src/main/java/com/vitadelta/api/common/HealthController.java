package com.vitadelta.api.common;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public liveness endpoint. It reports the overall status only and never any detail. */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

  private static final HealthResponse UP = new HealthResponse(HealthStatus.UP);

  @GetMapping(path = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
  public HealthResponse health() {
    return UP;
  }
}
