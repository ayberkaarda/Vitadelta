package com.vitadelta.api.common.logging;

import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * The request context written into every structured log line (project specification section 11.2)
 * through the logging context (MDC): {@code requestId} (set by the request-id filter), {@code
 * principalType}, {@code country} and {@code catalogVersion}. Every value is low-cardinality or
 * opaque; user ids, e-mail addresses and tokens are never put into the context. The keys are
 * cleared at the end of every request ({@link #clearRequestScope()}).
 */
public final class LogContext {

  /** {@code ANONYMOUS}, {@code USER} or {@code ADMIN}. */
  public static final String PRINCIPAL_TYPE = "principalType";

  /** ISO country code of the request (query parameter or validated body field). */
  public static final String COUNTRY = "country";

  /** Catalog version the request read (recommendations and comparisons). */
  public static final String CATALOG_VERSION = "catalogVersion";

  /** Name of the scheduled job a log line belongs to. */
  public static final String JOB = "job";

  /** Keys owned by the request scope, cleared after every request. */
  static final List<String> REQUEST_KEYS = List.of(PRINCIPAL_TYPE, COUNTRY, CATALOG_VERSION);

  private static final Pattern COUNTRY_CODE = Pattern.compile("^[A-Z]{2}$");

  private LogContext() {}

  /** Sets the principal type of the current request. */
  public static void principalType(String type) {
    MDC.put(PRINCIPAL_TYPE, type);
  }

  /** Sets the country of the current request; anything but two capital letters is ignored. */
  public static void country(String code) {
    if (code != null && COUNTRY_CODE.matcher(code).matches()) {
      MDC.put(COUNTRY, code);
    }
  }

  /** Sets the catalog version the current request read. */
  public static void catalogVersion(long version) {
    MDC.put(CATALOG_VERSION, Long.toString(version));
  }

  /** Removes the request-scoped keys ({@link #REQUEST_KEYS}). */
  public static void clearRequestScope() {
    REQUEST_KEYS.forEach(MDC::remove);
  }
}
