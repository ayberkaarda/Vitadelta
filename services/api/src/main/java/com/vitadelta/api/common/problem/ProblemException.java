package com.vitadelta.api.common.problem;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * An expected failure that becomes an RFC 9457 problem response. Throw it from any layer; the
 * global exception handler renders it with the problem {@code type}, {@code title}, {@code status},
 * the given {@code detail}, the request path as {@code instance}, the {@code requestId}, field
 * {@code errors} and any extension members (for example {@code limit}, {@code used}, {@code
 * upgradeHint}). Extension values must be JSON-serializable and must never contain secrets.
 *
 * <pre>{@code
 * throw ProblemException.of(ProblemCode.QUOTA_EXCEEDED, "The trial quota is used up.")
 *     .with("limit", 3).with("used", 3).with("action", "SIGN_UP");
 * }</pre>
 */
public class ProblemException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final ProblemCode code;
  private final HttpStatus status;
  private final String detail;
  private final transient Map<String, Object> properties = new LinkedHashMap<>();
  private final transient List<FieldViolation> errors;
  private final HttpHeaders headers = new HttpHeaders();

  public ProblemException(
      ProblemCode code, HttpStatus status, String detail, List<FieldViolation> errors) {
    super(code.code() + ": " + detail);
    this.code = Objects.requireNonNull(code, "code");
    this.status = Objects.requireNonNull(status, "status");
    this.detail = Objects.requireNonNull(detail, "detail");
    this.errors = List.copyOf(errors);
  }

  /** A problem with the code's default status. */
  public static ProblemException of(ProblemCode code, String detail) {
    return new ProblemException(code, code.defaultStatus(), detail, List.of());
  }

  /** A {@code validation_failed} problem with field errors. */
  public static ProblemException validation(String detail, List<FieldViolation> errors) {
    return new ProblemException(
        ProblemCode.VALIDATION_FAILED,
        ProblemCode.VALIDATION_FAILED.defaultStatus(),
        detail,
        errors);
  }

  /** Adds an extension member; reserved member names are rejected. */
  public ProblemException with(String name, Object value) {
    if (Problems.RESERVED_MEMBERS.contains(name)) {
      throw new IllegalArgumentException("reserved problem member: " + name);
    }
    properties.put(name, value);
    return this;
  }

  /** Adds a response header, for example {@code Retry-After}. */
  public ProblemException withHeader(String name, String value) {
    headers.add(name, value);
    return this;
  }

  public ProblemCode code() {
    return code;
  }

  public HttpStatus status() {
    return status;
  }

  public String detail() {
    return detail;
  }

  public Map<String, Object> properties() {
    return Map.copyOf(properties);
  }

  public List<FieldViolation> errors() {
    return errors;
  }

  public HttpHeaders headers() {
    return HttpHeaders.readOnlyHttpHeaders(headers);
  }
}
