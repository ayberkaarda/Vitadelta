package com.vitadelta.api.identity;

import com.vitadelta.api.common.problem.ProblemCode;
import com.vitadelta.api.common.problem.ProblemException;
import com.vitadelta.api.config.properties.WebProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Request-forgery guard of the cookie-scoped auth endpoints (section 8.1), evaluated after the
 * handler is resolved and before the request body is read. It is bound to the handler method (the
 * {@link CookieScoped} annotation), not to the request path, so every path spelling the dispatcher
 * maps to a guarded handler (percent-encoded characters, trailing slashes, matrix parameters) is
 * guarded:
 *
 * <ul>
 *   <li>every {@link CookieScoped} handler (login, register, refresh, logout): {@code Content-Type:
 *       application/json} and, when an {@code Origin} header is present, an origin of {@code
 *       VITADELTA_WEB_ORIGINS};
 *   <li>{@link CookieScoped#requestedWith()} handlers (refresh, logout) additionally {@code
 *       X-Requested-With: vitadelta}.
 * </ul>
 *
 * Any violation is {@code 403 forbidden}. This blocks login CSRF through cross-site form posts.
 */
public class AuthRequestGuard implements HandlerInterceptor {

  /** Required value of {@code X-Requested-With}. */
  public static final String REQUESTED_WITH = "vitadelta";

  private final WebProperties web;

  public AuthRequestGuard(WebProperties web) {
    this.web = web;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod method)) {
      return true;
    }
    CookieScoped scope = method.getMethodAnnotation(CookieScoped.class);
    if (scope == null) {
      return true;
    }
    if (scope.requestedWith() && !REQUESTED_WITH.equals(request.getHeader("X-Requested-With"))) {
      throw forbidden("The X-Requested-With header is missing or invalid.");
    }
    if (!isJson(request.getContentType())) {
      throw forbidden("The request must use Content-Type application/json.");
    }
    String origin = request.getHeader(HttpHeaders.ORIGIN);
    if (origin != null && !web.isAllowedOrigin(origin)) {
      throw forbidden("The request origin is not allowed.");
    }
    return true;
  }

  private static boolean isJson(String contentType) {
    if (contentType == null || contentType.isBlank()) {
      return false;
    }
    try {
      return MediaType.parseMediaType(contentType).equalsTypeAndSubtype(MediaType.APPLICATION_JSON);
    } catch (InvalidMediaTypeException e) {
      return false;
    }
  }

  private static ProblemException forbidden(String detail) {
    return ProblemException.of(ProblemCode.FORBIDDEN, detail);
  }
}
