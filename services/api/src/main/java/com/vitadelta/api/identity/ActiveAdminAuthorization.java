package com.vitadelta.api.identity;

import java.util.function.Supplier;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/**
 * Authorization of {@code /admin/**}: the bearer token must carry the ADMIN role and the account
 * must still be an ACTIVE ADMIN in the database. The role and status are re-read on every admin
 * request (nothing is cached across requests), so disabling or demoting an administrator takes
 * effect immediately instead of after the access-token lifetime.
 */
@Component
public class ActiveAdminAuthorization implements AuthorizationManager<RequestAuthorizationContext> {

  private final UserRepository users;

  public ActiveAdminAuthorization(UserRepository users) {
    this.users = users;
  }

  @Override
  public AuthorizationDecision authorize(
      Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) {
    Authentication current = authentication.get();
    if (current == null
        || !current.isAuthenticated()
        || !AuthorityUtils.authorityListToSet(current.getAuthorities()).contains("ROLE_ADMIN")) {
      return new AuthorizationDecision(false);
    }
    return new AuthorizationDecision(
        CurrentUser.id(current)
            .flatMap(users::findById)
            .filter(user -> user.role() == Role.ADMIN && user.status() == UserStatus.ACTIVE)
            .isPresent());
  }
}
