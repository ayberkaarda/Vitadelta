package com.vitadelta.api.common.problem;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Security failure responses as problems: {@code 401} {@code unauthorized} (with {@code
 * WWW-Authenticate: Bearer}) when authentication is missing or invalid, {@code 403} {@code
 * forbidden} when an authenticated principal lacks the role. Used by {@code SecurityConfig} for the
 * filter chain and the bearer-token resource server.
 */
public final class ProblemSecurityHandlers {

  private ProblemSecurityHandlers() {}

  /** The {@code 401} entry point. */
  public static AuthenticationEntryPoint authenticationEntryPoint() {
    return ProblemSecurityHandlers::unauthorized;
  }

  /** The {@code 403} access-denied handler. */
  public static AccessDeniedHandler accessDeniedHandler() {
    return ProblemSecurityHandlers::forbidden;
  }

  private static void unauthorized(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
      throws IOException {
    if (response.isCommitted()) {
      return;
    }
    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    ProblemBodies.write(response, GlobalExceptionHandler.unauthorized(request));
  }

  private static void forbidden(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
      throws IOException {
    if (response.isCommitted()) {
      return;
    }
    ProblemBodies.write(response, GlobalExceptionHandler.forbidden(request));
  }
}
