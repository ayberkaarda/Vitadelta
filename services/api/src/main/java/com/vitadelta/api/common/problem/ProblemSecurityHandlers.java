package com.vitadelta.api.common.problem;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsProcessor;
import org.springframework.web.cors.DefaultCorsProcessor;

/**
 * Security failure responses as problems: {@code 401} {@code unauthorized} (with {@code
 * WWW-Authenticate: Bearer}) when authentication is missing or invalid, {@code 403} {@code
 * forbidden} when an authenticated principal lacks the role or a cross-origin request is rejected
 * by the CORS policy. Used by {@code SecurityConfig} for the filter chain and the bearer-token
 * resource server.
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

  /**
   * The CORS processor of the filter chain: a cross-origin request that the CORS policy rejects
   * (foreign origin, method or header) gets the {@code 403} {@code forbidden} problem instead of
   * the plain-text default body.
   */
  public static CorsProcessor corsProcessor() {
    return new ProblemCorsProcessor();
  }

  private static final class ProblemCorsProcessor extends DefaultCorsProcessor {

    @Override
    public boolean processRequest(
        CorsConfiguration configuration, HttpServletRequest request, HttpServletResponse response)
        throws IOException {
      if (super.processRequest(configuration, request, response)) {
        return true;
      }
      if (!response.isCommitted()) {
        ProblemBodies.write(response, GlobalExceptionHandler.forbidden(request));
      }
      return false;
    }

    /** Sets only the status; {@link #processRequest} writes the problem body. */
    @Override
    protected void rejectRequest(ServerHttpResponse response) {
      response.setStatusCode(HttpStatus.FORBIDDEN);
    }
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
