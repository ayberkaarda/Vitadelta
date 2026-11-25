package com.vitadelta.api.billing;

import java.util.Locale;

/**
 * A failed call to the Stripe API. The message names the operation, the reason, the HTTP status and
 * Stripe's error type and code when present; it never contains the secret key, a request parameter
 * or the response body.
 */
public class StripeApiException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Why the call failed. */
  public enum Reason {
    CONNECTION,
    TIMEOUT,
    HTTP_STATUS,
    MALFORMED,
    TOO_LARGE;

    /** Metric tag. */
    public String tag() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  private final Reason reason;
  private final int status;

  StripeApiException(String operation, Reason reason, int status, String detail, Throwable cause) {
    super(
        operation
            + " failed: "
            + reason.tag()
            + (status > 0 ? " (status " + status + ")" : "")
            + (detail == null || detail.isEmpty() ? "" : ", " + detail),
        cause);
    this.reason = reason;
    this.status = status;
  }

  public Reason reason() {
    return reason;
  }

  /** The HTTP status of an {@link Reason#HTTP_STATUS} failure, otherwise 0. */
  public int status() {
    return status;
  }

  /** Whether Stripe answered that the requested object does not exist. */
  public boolean notFound() {
    return reason == Reason.HTTP_STATUS && status == 404;
  }
}
