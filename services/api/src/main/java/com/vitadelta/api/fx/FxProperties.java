package com.vitadelta.api.fx;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the FX module (section 7.7, ADR-0009).
 *
 * @param baseUrl Frankfurter v2 base URL ({@code VITADELTA_FX_BASE_URL}, default {@code
 *     https://api.frankfurter.dev/v2}, also when set but blank); absolute {@code http(s)} URL
 *     without credentials, query or fragment. It is the only host the adapter ever calls.
 * @param extraCurrencies additional ISO 4217 display currencies beyond the country currencies
 *     ({@code VITADELTA_FX_EXTRA_CURRENCIES}, comma-separated, empty by default)
 * @param connectTimeout TCP connect timeout of the provider call (2 s)
 * @param readTimeout time allowed for the provider response (5 s)
 * @param maxResponseBytes largest accepted provider response body (64 KiB); a larger body fails the
 *     fetch
 * @param refreshInterval interval of the scheduled refresh (6 h)
 * @param startupMaxAge at start-up the refresh runs only when the newest stored rate date is older
 *     than this many days before today (1 day), or when no rate is stored
 * @param staleAfterDays a rate whose date is older than this many days is stale ({@code
 *     fx_stale_after_days}, 5)
 * @param lookupCacheTtl how long a rate lookup is cached (60 s); a refresh clears the cache
 */
@ConfigurationProperties(prefix = "vitadelta.fx")
public record FxProperties(
    @DefaultValue(FxProperties.DEFAULT_BASE_URL) String baseUrl,
    @DefaultValue List<String> extraCurrencies,
    @DefaultValue("2s") Duration connectTimeout,
    @DefaultValue("5s") Duration readTimeout,
    @DefaultValue("65536") int maxResponseBytes,
    @DefaultValue("6h") Duration refreshInterval,
    @DefaultValue("1") int startupMaxAge,
    @DefaultValue("5") int staleAfterDays,
    @DefaultValue("60s") Duration lookupCacheTtl) {

  /** Default provider URL (ADR-0009). */
  public static final String DEFAULT_BASE_URL = "https://api.frankfurter.dev/v2";

  private static final Pattern CODE = Pattern.compile("^[A-Z]{3}$");

  public FxProperties {
    baseUrl = validBaseUrl(baseUrl);
    extraCurrencies = List.copyOf(validCurrencies(extraCurrencies));
    requirePositive(connectTimeout, "vitadelta.fx.connect-timeout");
    requirePositive(readTimeout, "vitadelta.fx.read-timeout");
    requirePositive(refreshInterval, "vitadelta.fx.refresh-interval");
    requirePositive(lookupCacheTtl, "vitadelta.fx.lookup-cache-ttl");
    if (maxResponseBytes < 1024) {
      throw new IllegalArgumentException(
          "vitadelta.fx.max-response-bytes must be >= 1024: " + maxResponseBytes);
    }
    if (startupMaxAge < 0) {
      throw new IllegalArgumentException(
          "vitadelta.fx.startup-max-age must be >= 0: " + startupMaxAge);
    }
    if (staleAfterDays < 0) {
      throw new IllegalArgumentException(
          "vitadelta.fx.stale-after-days must be >= 0: " + staleAfterDays);
    }
  }

  private static String validBaseUrl(String value) {
    if (value == null || value.isBlank()) {
      return DEFAULT_BASE_URL;
    }
    String url = value.strip();
    while (url.endsWith("/")) {
      url = url.substring(0, url.length() - 1);
    }
    URI uri;
    try {
      uri = new URI(url);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("VITADELTA_FX_BASE_URL is not a valid URL: " + url, e);
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!uri.isAbsolute()
        || uri.getHost() == null
        || !(scheme.equals("http") || scheme.equals("https"))
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null
        || uri.getRawUserInfo() != null) {
      throw new IllegalArgumentException(
          "VITADELTA_FX_BASE_URL must be an absolute http(s) URL with a host and without"
              + " credentials, query or fragment: "
              + url);
    }
    return url;
  }

  private static Set<String> validCurrencies(List<String> values) {
    Set<String> codes = new TreeSet<>();
    if (values == null) {
      return codes;
    }
    for (String raw : values) {
      if (raw == null || raw.isBlank()) {
        continue;
      }
      String code = raw.strip().toUpperCase(Locale.ROOT);
      if (!CODE.matcher(code).matches() || !isIso4217(code)) {
        throw new IllegalArgumentException(
            "VITADELTA_FX_EXTRA_CURRENCIES contains a value that is not an ISO 4217 code: " + raw);
      }
      codes.add(code);
    }
    return codes;
  }

  private static boolean isIso4217(String code) {
    try {
      Currency.getInstance(code);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static void requirePositive(Duration value, String name) {
    if (value == null || value.isNegative() || value.isZero()) {
      throw new IllegalArgumentException(name + " must be positive: " + value);
    }
  }
}
