package com.vitadelta.api.identity;

import com.vitadelta.api.config.properties.WebProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Optional;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The two cookies of the identity package (section 8.1). Both are {@code HttpOnly} and {@code
 * Secure} outside the {@code dev} profile ({@link WebProperties#secureCookies()}).
 *
 * <ul>
 *   <li>{@value #REFRESH}: {@code SameSite=Strict}, {@code Path=/api/v1/auth}, 30 days;
 *   <li>{@value #ANONYMOUS}: {@code SameSite=Lax}, {@code Path=/}, 180 days.
 * </ul>
 */
@Component
public class AuthCookies {

  public static final String REFRESH = "vd_refresh";
  public static final String ANONYMOUS = "vd_anon";
  public static final String REFRESH_PATH = "/api/v1/auth";
  public static final Duration REFRESH_MAX_AGE = Duration.ofDays(30);
  public static final Duration ANONYMOUS_MAX_AGE = Duration.ofDays(180);

  private final boolean secure;

  public AuthCookies(WebProperties web) {
    this.secure = web.secureCookies();
  }

  /** {@code Set-Cookie} value that stores a refresh token. */
  public String refresh(String token) {
    return refreshCookie(token, REFRESH_MAX_AGE);
  }

  /** {@code Set-Cookie} value that clears the refresh cookie. */
  public String clearRefresh() {
    return refreshCookie("", Duration.ZERO);
  }

  /** {@code Set-Cookie} value that stores the anonymous identity. */
  public String anonymous(String value) {
    return anonymousCookie(value, ANONYMOUS_MAX_AGE);
  }

  /** {@code Set-Cookie} value that clears the anonymous identity. */
  public String clearAnonymous() {
    return anonymousCookie("", Duration.ZERO);
  }

  private String refreshCookie(String value, Duration maxAge) {
    return ResponseCookie.from(REFRESH, value)
        .httpOnly(true)
        .secure(secure)
        .sameSite("Strict")
        .path(REFRESH_PATH)
        .maxAge(maxAge)
        .build()
        .toString();
  }

  private String anonymousCookie(String value, Duration maxAge) {
    return ResponseCookie.from(ANONYMOUS, value)
        .httpOnly(true)
        .secure(secure)
        .sameSite("Lax")
        .path("/")
        .maxAge(maxAge)
        .build()
        .toString();
  }

  /** The value of the request cookie {@code name}, if present and not empty. */
  public static Optional<String> read(HttpServletRequest request, String name) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return Optional.empty();
    }
    for (Cookie cookie : cookies) {
      if (name.equals(cookie.getName()) && cookie.getValue() != null) {
        String value = cookie.getValue();
        if (!value.isEmpty()) {
          return Optional.of(value);
        }
      }
    }
    return Optional.empty();
  }
}
