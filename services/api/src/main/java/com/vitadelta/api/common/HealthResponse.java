package com.vitadelta.api.common;

/**
 * Body of the public health endpoint.
 *
 * @param status overall service status
 */
public record HealthResponse(HealthStatus status) {}
