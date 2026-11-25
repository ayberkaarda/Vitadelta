package com.vitadelta.api.common.web;

import jakarta.servlet.http.HttpServletResponse;

/**
 * The security headers of every API response (section 15.2), for responses written outside the
 * Spring Security filter chain's header writer: requests rejected by the HTTP firewall or by the
 * servlet container before any filter ran. The values equal those the filter chain writes.
 */
public final class ApiHeaders {

  /** Content security policy of every API response. */
  public static final String CONTENT_SECURITY_POLICY = "default-src 'none'; frame-ancestors 'none'";

  private ApiHeaders() {}

  /** Sets the API security headers and {@code Cache-Control: no-store} on {@code response}. */
  public static void apply(HttpServletResponse response) {
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Referrer-Policy", "no-referrer");
    response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
    response.setHeader("X-Frame-Options", "DENY");
    response.setHeader("Cache-Control", "no-store");
  }
}
