package com.vitadelta.api.identity;

import com.vitadelta.api.common.problem.ProblemCode;
import com.vitadelta.api.common.problem.ProblemException;
import com.vitadelta.api.config.properties.WebProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Request-forgery guard of the cookie-scoped auth endpoints (section 8.1), evaluated before the
 * request body is read:
 *
 * <ul>
 *   <li>{@code /auth/login}, {@code /auth/register}, {@code /auth/refresh}, {@code /auth/logout}:
 *       {@code Content-Type: application/json} and, when an {@code Origin} header is present, an
 *       origin of {@code VITADELTA_WEB_ORIGINS};
 *   <li>{@code /auth/refresh} and {@code /auth/logout} additionally {@code X-Requested-With:
 *       vitadelta}.
 * </ul>
 *
 * Any violation is {@code 403 forbidden}. This blocks login CSRF through cross-site form posts.
 */
public class AuthRequestGuard implements HandlerInterceptor {

  /** Required value of {@code X-Requested-With}. */
  public static final String REQUESTED_WITH = "vitadelta";

  /** Paths that require the JSON content type and an allowed origin. */
  public static final Set<String> COOKIE_SCOPED_PATHS =
      Set.of(
          AuthController.BASE + "/login",
          AuthController.BASE + "/register",
          AuthController.BASE + "/refresh",
          AuthController.BASE + "/logout");

  /** Paths that also require {@code X-Requested-With}. */
  public static final Set<String> REQUESTED_WITH_PATHS =
      Set.of(AuthController.BASE + "/refresh", AuthController.BASE + "/logout");

  private final WebProperties web;

  public AuthRequestGuard(WebProperties web) {
    this.web = web;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    String path = request.getRequestURI();
    if (!COOKIE_SCOPED_PATHS.contains(path)) {
      return true;
    }
    if (REQUESTED_WITH_PATHS.contains(path)
        && !REQUESTED_WITH.equals(request.getHeader("X-Requested-With"))) {
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
