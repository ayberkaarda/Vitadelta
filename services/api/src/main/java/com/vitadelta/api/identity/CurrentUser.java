package com.vitadelta.api.identity;

import com.vitadelta.api.common.problem.ProblemCode;
import com.vitadelta.api.common.problem.ProblemException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The signed-in user of a request: the {@code sub} claim of a validated access token. For other
 * modules (saved products, alerts, promo redemption) that need the caller's user id.
 */
public final class CurrentUser {

  private CurrentUser() {}

  /** The user id of a bearer-token principal; empty for anonymous callers. */
  public static Optional<UUID> id(Authentication authentication) {
    if (!(authentication instanceof JwtAuthenticationToken token)) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(token.getName()));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /**
   * The user id of a bearer-token principal.
   *
   * @throws ProblemException {@code 401 unauthorized} for any other principal
   */
  public static UUID require(Authentication authentication) {
    return id(authentication).orElseThrow(CurrentUser::unauthorized);
  }

  static ProblemException unauthorized() {
    return ProblemException.of(ProblemCode.UNAUTHORIZED, "Authentication is required.");
  }
}
