package com.vitadelta.api.config.properties;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Web-facing settings.
 *
 * @param origins allowed browser origins ({@code VITADELTA_WEB_ORIGINS}, comma separated), used by
 *     CORS and the Origin check of the cookie-scoped auth endpoints; each is {@code
 *     scheme://host[:port]}
 * @param baseUrl public base URL of the web app ({@code VITADELTA_WEB_BASE_URL}); the only source
 *     of links in e-mails; required; absolute and {@code https} unless {@code requireHttps} is
 *     false
 * @param requireHttps whether {@code baseUrl} must use https; false only in the {@code dev} profile
 * @param secureCookies whether cookies carry the {@code Secure} flag; false only in the {@code dev}
 *     profile
 */
@ConfigurationProperties(prefix = "vitadelta.web")
public record WebProperties(
    List<String> origins,
    String baseUrl,
    @DefaultValue("true") boolean requireHttps,
    @DefaultValue("true") boolean secureCookies) {

  public WebProperties {
    origins =
        origins == null
            ? List.of()
            : origins.stream().map(String::strip).filter(o -> !o.isEmpty()).toList();
    origins.forEach(WebProperties::checkOrigin);
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException(
          "VITADELTA_WEB_BASE_URL is not set; it must be the absolute public URL of the web app");
    }
    baseUrl = stripTrailingSlash(baseUrl.strip());
    URI uri = parse(baseUrl, "VITADELTA_WEB_BASE_URL");
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!uri.isAbsolute()
        || uri.getHost() == null
        || !(scheme.equals("https") || scheme.equals("http"))) {
      throw new IllegalArgumentException(
          "VITADELTA_WEB_BASE_URL must be an absolute http(s) URL with a host: " + baseUrl);
    }
    if (requireHttps && !scheme.equals("https")) {
      throw new IllegalArgumentException(
          "VITADELTA_WEB_BASE_URL must use https outside the dev profile: " + baseUrl);
    }
    if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
      throw new IllegalArgumentException(
          "VITADELTA_WEB_BASE_URL must not contain a query or fragment: " + baseUrl);
    }
  }

  /** {@code baseUrl} followed by {@code path} (which must start with {@code /}). */
  public String link(String path) {
    if (!path.startsWith("/")) {
      throw new IllegalArgumentException("path must start with /: " + path);
    }
    return baseUrl + path;
  }

  /** Whether {@code origin} (the value of an {@code Origin} header) is an allowed web origin. */
  public boolean isAllowedOrigin(String origin) {
    return origin != null && origins.stream().anyMatch(o -> o.equalsIgnoreCase(origin.strip()));
  }

  private static void checkOrigin(String origin) {
    URI uri = parse(origin, "VITADELTA_WEB_ORIGINS");
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    boolean bare =
        (uri.getRawPath() == null || uri.getRawPath().isEmpty())
            && uri.getRawQuery() == null
            && uri.getRawFragment() == null
            && uri.getRawUserInfo() == null;
    if (!(scheme.equals("https") || scheme.equals("http")) || uri.getHost() == null || !bare) {
      throw new IllegalArgumentException(
          "VITADELTA_WEB_ORIGINS entries must be origins (scheme://host[:port]): " + origin);
    }
  }

  private static URI parse(String value, String variable) {
    try {
      return new URI(value);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException(variable + " is not a valid URL: " + value, e);
    }
  }

  private static String stripTrailingSlash(String value) {
    String result = value;
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }
}
