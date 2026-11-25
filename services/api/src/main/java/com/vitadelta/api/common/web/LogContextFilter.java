package com.vitadelta.api.common.web;

import com.vitadelta.api.common.logging.LogContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Fills the request part of the logging context ({@link LogContext}) once Spring Security has
 * authenticated the request: {@code principalType} ({@code ADMIN} for a principal with {@code
 * ROLE_ADMIN}, {@code USER} for any other authenticated principal, {@code ANONYMOUS} otherwise) and
 * {@code country} from the {@code country} query parameter. The query string is parsed directly, so
 * a request body is never read here. Services add {@code country} and {@code catalogVersion} from
 * validated bodies. Every request-scoped key is removed when the request ends.
 */
public class LogContextFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    try {
      LogContext.principalType(
          principalType(SecurityContextHolder.getContext().getAuthentication()));
      LogContext.country(queryParameter(request.getQueryString(), LogContext.COUNTRY));
      chain.doFilter(request, response);
    } finally {
      LogContext.clearRequestScope();
    }
  }

  static String principalType(Authentication authentication) {
    if (authentication == null
        || !authentication.isAuthenticated()
        || authentication instanceof AnonymousAuthenticationToken) {
      return "ANONYMOUS";
    }
    boolean admin =
        authentication.getAuthorities().stream()
            .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    return admin ? "ADMIN" : "USER";
  }

  /** The first value of {@code name} in a raw query string, {@code null} when absent or invalid. */
  static String queryParameter(String query, String name) {
    if (query == null || query.isEmpty()) {
      return null;
    }
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      String key = eq < 0 ? pair : pair.substring(0, eq);
      if (key.equals(name)) {
        try {
          return eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
          return null;
        }
      }
    }
    return null;
  }
}
