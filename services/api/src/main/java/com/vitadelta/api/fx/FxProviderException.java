package com.vitadelta.api.fx;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

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
  private final int status;
  private final transient Set<String> unsupportedQuotes;
  private final boolean baseRejected;

  public FxProviderException(Reason reason, String message) {
    this(reason, message, 0, Set.of(), false);
  }

  public FxProviderException(Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = reason;
    this.status = 0;
    this.unsupportedQuotes = Set.of();
    this.baseRejected = false;
  }

  /**
   * A failure that names the requested currencies the provider does not publish.
   *
   * @param status HTTP status of the answer, 0 when there was none
   * @param unsupportedQuotes requested quote currencies the answer names as invalid or leaves out
   * @param baseRejected whether the answer is the verified 422 "invalid currency" answer for the
   *     requested base
   */
  public FxProviderException(
      Reason reason,
      String message,
      int status,
      Set<String> unsupportedQuotes,
      boolean baseRejected) {
    super(message);
    this.reason = reason;
    this.status = status;
    this.unsupportedQuotes = Collections.unmodifiableSortedSet(new TreeSet<>(unsupportedQuotes));
    this.baseRejected = baseRejected;
  }

  public Reason reason() {
    return reason;
  }

  /** HTTP status of the answer; 0 when no answer was received or it was a 2xx. */
  public int status() {
    return status;
  }

  /**
   * Requested quote currencies the provider named as invalid or left out of its answer; the same
   * base can be requested again without them. Empty when the failure names none.
   */
  public Set<String> unsupportedQuotes() {
    return unsupportedQuotes;
  }

  /**
   * Whether the provider rejected the base currency itself: a 422 answer whose message is exactly
   * the verified {@code invalid currency: <base>} for the requested base.
   */
  public boolean baseRejected() {
    return baseRejected;
  }

  /** Whether the provider rejected the request as invalid (HTTP 400 or 422). */
  public boolean requestRejected() {
    return status == 400 || status == 422;
  }
}
