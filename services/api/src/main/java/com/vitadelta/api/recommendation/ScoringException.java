package com.vitadelta.api.recommendation;

import java.util.List;

/** A failed scoring call, classified by how the public API answers it (section 7.6). */
public sealed class ScoringException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  ScoringException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * The service could not answer: connection error, timeout, {@code 5xx} or an open circuit. The
   * public API answers {@code 503 scoring_unavailable}.
   */
  public static final class Unavailable extends ScoringException {

    private static final long serialVersionUID = 1L;

    Unavailable(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * The service rejected the request with {@code 422 validation_failed}.
   *
   * @see #errors()
   */
  public static final class Rejected extends ScoringException {

    private static final long serialVersionUID = 1L;

    private final transient List<FieldError> errors;

    Rejected(List<FieldError> errors, Throwable cause) {
      super("scoring service rejected the request: " + errors, cause);
      this.errors = List.copyOf(errors);
    }

    /** The field errors of the scoring problem, in the order returned. */
    public List<FieldError> errors() {
      return errors;
    }
  }

  /**
   * Any other failure: another {@code 4xx} status or an unreadable answer. The public API answers
   * {@code 500 internal_error}.
   */
  public static final class Failed extends ScoringException {

    private static final long serialVersionUID = 1L;

    Failed(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** One {@code errors[]} entry of a scoring {@code validation_failed} problem. */
  public record FieldError(String field, String code, String message) {}
}
