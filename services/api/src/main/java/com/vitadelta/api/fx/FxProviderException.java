package com.vitadelta.api.fx;

/**
 * A failed provider fetch. The message is a bounded, sanitised summary that is safe to log; it
 * never contains the response body beyond {@link ResponseSummary}.
 */
public class FxProviderException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Why a fetch failed (also the {@code reason} tag of the failure metric). */
  public enum Reason {
    /** Connect or read timeout. */
    TIMEOUT,
    /** Connection refused, reset or another I/O error. */
    CONNECTION,
    /** A status other than 2xx. */
    HTTP_STATUS,
    /** The body is larger than the configured limit. */
    TOO_LARGE,
    /** The body is not JSON or not the verified array shape. */
    MALFORMED,
    /** The rows do not match the request (base, quotes, dates, rate values). */
    INVALID_ROWS
  }

  private final Reason reason;

  public FxProviderException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public FxProviderException(Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
