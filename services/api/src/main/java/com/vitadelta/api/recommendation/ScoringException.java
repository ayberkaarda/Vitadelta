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
      super("scoring service rejected the request with " + errors.size() + " error(s)", cause);
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

  /**
   * One {@code errors[]} entry of a scoring {@code validation_failed} problem: only the field path
   * and the code are kept, each cut to {@link #MAX_TEXT} characters. The scoring service's {@code
   * message} (like its {@code title} and {@code detail}) is never read into the API, so no scoring
   * text can reach a client or a log line.
   */
  public record FieldError(String field, String code) {

    /** Longest field path or code kept from a scoring answer. */
    public static final int MAX_TEXT = 200;

    public FieldError {
      field = bounded(field);
      code = bounded(code);
    }

    private static String bounded(String text) {
      String value = text == null ? "" : text;
      return value.length() <= MAX_TEXT ? value : value.substring(0, MAX_TEXT);
    }
  }
}
