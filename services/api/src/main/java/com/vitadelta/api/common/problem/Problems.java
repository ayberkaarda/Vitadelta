package com.vitadelta.api.common.problem;

import com.vitadelta.api.common.web.RequestIds;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * Factory of RFC 9457 problem bodies (project specification section 9.3): {@code type}, {@code
 * title}, {@code status}, {@code detail}, {@code instance} (request path without query string),
 * {@code requestId} and, for validation errors, {@code errors[]}.
 */
public final class Problems {

  /** Media type of problem responses. */
  public static final String MEDIA_TYPE = "application/problem+json";

  /** Member names that extension properties may not use. */
  public static final Set<String> RESERVED_MEMBERS =
      Set.of("type", "title", "status", "detail", "instance", "requestId", "errors");

  /** Generic detail of {@code internal_error}; never contains exception information. */
  public static final String INTERNAL_ERROR_DETAIL = "An unexpected error occurred.";

  private Problems() {}

  /** A problem of one of the public codes. */
  public static ProblemDetail of(
      ProblemCode code, HttpStatusCode status, String detail, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(code.type());
    problem.setTitle(code.title());
    return finish(problem, request);
  }

  /** A problem from a {@link ProblemException}. */
  public static ProblemDetail of(ProblemException exception, HttpServletRequest request) {
    ProblemDetail problem = of(exception.code(), exception.status(), exception.detail(), request);
    for (Map.Entry<String, Object> property : exception.properties().entrySet()) {
      problem.setProperty(property.getKey(), property.getValue());
    }
    if (!exception.errors().isEmpty()) {
      problem.setProperty("errors", exception.errors());
    }
    return problem;
  }

  /** A {@code validation_failed} problem with field errors. */
  public static ProblemDetail validation(
      HttpStatusCode status,
      String detail,
      List<FieldViolation> errors,
      HttpServletRequest request) {
    ProblemDetail problem = of(ProblemCode.VALIDATION_FAILED, status, detail, request);
    problem.setProperty("errors", List.copyOf(errors));
    return problem;
  }

  /**
   * A generic HTTP problem without additional semantics (type {@code about:blank}), for example an
   * unknown path or an unsupported method.
   */
  public static ProblemDetail generic(
      HttpStatusCode status, String detail, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(URI.create("about:blank"));
    return finish(problem, request);
  }

  /** The {@code internal_error} problem with the generic detail. */
  public static ProblemDetail internalError(HttpServletRequest request) {
    return of(
        ProblemCode.INTERNAL_ERROR,
        ProblemCode.INTERNAL_ERROR.defaultStatus(),
        INTERNAL_ERROR_DETAIL,
        request);
  }

  /** Sets {@code instance} and {@code requestId} from the request. */
  public static ProblemDetail finish(ProblemDetail problem, HttpServletRequest request) {
    if (request != null) {
      problem.setInstance(URI.create(safePath(request)));
    }
    problem.setProperty(
        RequestIds.KEY, RequestIds.current(request).orElseGet(RequestIds::generate));
    return problem;
  }

  private static String safePath(HttpServletRequest request) {
    String uri = request.getRequestURI();
    if (uri == null || uri.isEmpty()) {
      return "/";
    }
    try {
      return URI.create(uri).getRawPath();
    } catch (IllegalArgumentException e) {
      return "/";
    }
  }
}
