package com.vitadelta.api.config.properties;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Request body size limits for {@code /api/v1/**}.
 *
 * @param maxBody limit for every API request body (default 64 kB, the query body limit)
 * @param importMaxBody limit for the import endpoints (default 20 MB)
 * @param importPaths path prefixes that use {@code importMaxBody}
 */
@ConfigurationProperties(prefix = "vitadelta.http")
public record HttpLimitsProperties(
    @DefaultValue("64KB") DataSize maxBody,
    @DefaultValue("20MB") DataSize importMaxBody,
    @DefaultValue("/api/v1/admin/imports/") List<String> importPaths) {

  public HttpLimitsProperties {
    if (maxBody.toBytes() <= 0 || importMaxBody.toBytes() <= 0) {
      throw new IllegalArgumentException("vitadelta.http body limits must be positive");
    }
  }

  /** The limit in bytes for a request path. */
  public long limitFor(String path) {
    return importPaths.stream().anyMatch(path::startsWith)
        ? importMaxBody.toBytes()
        : maxBody.toBytes();
  }
}
