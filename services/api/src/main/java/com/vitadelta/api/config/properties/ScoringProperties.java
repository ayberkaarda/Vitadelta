package com.vitadelta.api.config.properties;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Connection to the scoring service (project specification sections 7.6 and 9.2). Retry and circuit
 * breaker settings live under {@code resilience4j.*.instances.scoring} in {@code application.yml}.
 *
 * @param baseUrl base URL of the scoring service without the {@code /internal/v1} base path ({@code
 *     VITADELTA_SCORING_BASE_URL}); required, absolute {@code http} or {@code https}
 * @param connectTimeout TCP connect timeout (500 ms)
 * @param readTimeout time allowed for the response once the request was sent (2 s)
 * @param maxConcurrentCalls scoring calls that may be in flight at once per API instance (16); a
 *     call beyond it fails at once with {@code 503 scoring_unavailable}
 */
@ConfigurationProperties(prefix = "vitadelta.scoring")
public record ScoringProperties(
    String baseUrl,
    @DefaultValue("500ms") Duration connectTimeout,
    @DefaultValue("2s") Duration readTimeout,
    @DefaultValue("16") int maxConcurrentCalls) {

  /** Base path of the scoring API. */
  public static final String BASE_PATH = "/internal/v1";

  public ScoringProperties {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException(
          "VITADELTA_SCORING_BASE_URL is not set; it must be the URL of the scoring service");
    }
    baseUrl = baseUrl.strip();
    while (baseUrl.endsWith("/")) {
      baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
    }
    URI uri;
    try {
      uri = new URI(baseUrl);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException(
          "VITADELTA_SCORING_BASE_URL is not a valid URL: " + baseUrl, e);
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!uri.isAbsolute()
        || uri.getHost() == null
        || !(scheme.equals("http") || scheme.equals("https"))
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null) {
      throw new IllegalArgumentException(
          "VITADELTA_SCORING_BASE_URL must be an absolute http(s) URL with a host and without"
              + " query or fragment: "
              + baseUrl);
    }
    if (connectTimeout.isNegative()
        || connectTimeout.isZero()
        || readTimeout.isNegative()
        || readTimeout.isZero()) {
      throw new IllegalArgumentException("vitadelta.scoring timeouts must be positive");
    }
    if (maxConcurrentCalls < 1) {
      throw new IllegalArgumentException(
          "vitadelta.scoring.max-concurrent-calls must be >= 1: " + maxConcurrentCalls);
    }
  }

  /** The base URL of the scoring API including {@link #BASE_PATH}. */
  public String apiUrl() {
    return baseUrl + BASE_PATH;
  }
}
