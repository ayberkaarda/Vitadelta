package com.vitadelta.api.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/** Request ids (project specification section 9.3). */
public final class RequestIds {

  /** Header that carries the request id in requests and responses. */
  public static final String HEADER = "X-Request-Id";

  /** MDC key and request attribute name of the request id. */
  public static final String KEY = "requestId";

  /** Accepted format of a client-supplied request id. */
  public static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

  private RequestIds() {}

  /** The client value when it matches {@link #FORMAT}, otherwise a newly generated id. */
  public static String acceptOrGenerate(String clientValue) {
    if (clientValue != null && FORMAT.matcher(clientValue).matches()) {
      return clientValue;
    }
    return generate();
  }

  /** A new random request id (a UUID, which matches {@link #FORMAT}). */
  public static String generate() {
    return UUID.randomUUID().toString();
  }

  /** The id of the request being served, from the request attribute or the logging context. */
  public static Optional<String> current(HttpServletRequest request) {
    Object value = request == null ? null : request.getAttribute(KEY);
    if (value instanceof String id) {
      return Optional.of(id);
    }
    return Optional.ofNullable(MDC.get(KEY));
  }
}
