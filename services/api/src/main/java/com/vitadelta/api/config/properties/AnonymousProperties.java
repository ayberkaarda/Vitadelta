package com.vitadelta.api.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Anonymous trial settings.
 *
 * @param queryLimit recommendation queries an anonymous visitor may run ({@code
 *     VITADELTA_ANON_QUERY_LIMIT}, default 3)
 * @param visibleResults result rows shown unlocked to anonymous visitors ({@code
 *     VITADELTA_ANON_VISIBLE_RESULTS}, default 3)
 */
@ConfigurationProperties(prefix = "vitadelta.anonymous")
public record AnonymousProperties(
    @DefaultValue("3") int queryLimit, @DefaultValue("3") int visibleResults) {

  public AnonymousProperties {
    if (queryLimit < 0) {
      throw new IllegalArgumentException("VITADELTA_ANON_QUERY_LIMIT must be >= 0: " + queryLimit);
    }
    if (visibleResults < 0) {
      throw new IllegalArgumentException(
          "VITADELTA_ANON_VISIBLE_RESULTS must be >= 0: " + visibleResults);
    }
  }
}
