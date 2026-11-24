package com.vitadelta.api.common.problem;

import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpStatus;

/**
 * Problem codes of the public error model (project specification section 9.3). The problem {@code
 * type} is {@code https://vitadelta.dev/problems/<code>}.
 */
public enum ProblemCode {
  VALIDATION_FAILED(HttpStatus.UNPROCESSABLE_CONTENT, "Validation failed"),
  COUNTRY_NOT_FOUND(HttpStatus.UNPROCESSABLE_CONTENT, "Country not found"),
  PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "Product not found"),
  PRODUCT_NOT_AVAILABLE_IN_COUNTRY(HttpStatus.NOT_FOUND, "Product not available in country"),
  CURRENCY_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT, "Currency mismatch"),
  SCORING_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Scoring unavailable"),
  UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Unauthorized"),
  FORBIDDEN(HttpStatus.FORBIDDEN, "Forbidden"),
  INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid credentials"),
  ACCOUNT_DISABLED(HttpStatus.FORBIDDEN, "Account disabled"),
  EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "E-mail not verified"),
  TOKEN_INVALID(HttpStatus.BAD_REQUEST, "Token invalid"),
  TOKEN_EXPIRED(HttpStatus.BAD_REQUEST, "Token expired"),
  REFRESH_REUSE_DETECTED(HttpStatus.UNAUTHORIZED, "Refresh token reuse detected"),
  QUOTA_EXCEEDED(HttpStatus.FORBIDDEN, "Quota exceeded"),
  ENTITLEMENT_EXCEEDED(HttpStatus.FORBIDDEN, "Entitlement exceeded"),
  PROMO_INVALID(HttpStatus.BAD_REQUEST, "Promo code invalid"),
  CONFLICT_RETRY(HttpStatus.CONFLICT, "Conflict, retry"),
  FX_UNSUPPORTED_CURRENCY(HttpStatus.UNPROCESSABLE_CONTENT, "Unsupported display currency"),
  RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Rate limited"),
  UNSUPPORTED_IU_FORM(HttpStatus.UNPROCESSABLE_CONTENT, "Unsupported IU form"),
  INVALID_OFFER_URL(HttpStatus.UNPROCESSABLE_CONTENT, "Invalid offer URL"),
  IMPORT_FAILED(HttpStatus.UNPROCESSABLE_CONTENT, "Import failed"),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");

  /** Prefix of every problem type URI. */
  public static final String TYPE_PREFIX = "https://vitadelta.dev/problems/";

  private final HttpStatus defaultStatus;
  private final String title;

  ProblemCode(HttpStatus defaultStatus, String title) {
    this.defaultStatus = defaultStatus;
    this.title = title;
  }

  /** The lower-case code, for example {@code quota_exceeded}. */
  public String code() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** The problem type URI. */
  public URI type() {
    return URI.create(TYPE_PREFIX + code());
  }

  /** The status used when the thrower does not choose another one. */
  public HttpStatus defaultStatus() {
    return defaultStatus;
  }

  public String title() {
    return title;
  }
}
