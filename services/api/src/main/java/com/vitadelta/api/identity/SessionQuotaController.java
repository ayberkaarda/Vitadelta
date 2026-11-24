package com.vitadelta.api.identity;

import com.vitadelta.api.identity.AnonymousIdentityService.AnonymousIdentity;
import com.vitadelta.api.identity.IdentityApi.AnonymousQuota;
import com.vitadelta.api.identity.IdentityApi.UserQuota;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/session/quota} (section 8.4): the anonymous trial counters (creating the
 * {@code vd_anon} identity when missing) or, for signed-in users, the plan usage summary.
 */
@RestController
public class SessionQuotaController {

  static final String ANONYMOUS = "ANONYMOUS";

  private final AnonymousIdentityService anonymous;
  private final AnonymousQuotaService quota;
  private final AccountService accounts;

  public SessionQuotaController(
      AnonymousIdentityService anonymous, AnonymousQuotaService quota, AccountService accounts) {
    this.anonymous = anonymous;
    this.quota = quota;
    this.accounts = accounts;
  }

  @GetMapping("/api/v1/session/quota")
  Object quota(
      Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
    Optional<UUID> userId = CurrentUser.id(authentication);
    if (userId.isPresent()) {
      AccountPlanSummary summary = accounts.summary(userId.get());
      String role =
          String.valueOf(
              ((JwtAuthenticationToken) authentication)
                  .getToken()
                  .getClaimAsString(AccessTokenService.ROLE));
      return new UserQuota(
          Role.ADMIN.name().equals(role) ? Role.ADMIN.name() : Role.USER.name(),
          summary.plan().code(),
          summary.usage());
    }
    AnonymousIdentity identity = anonymous.resolve(request, response);
    return new AnonymousQuota(
        ANONYMOUS,
        quota.queriesUsed(identity.idHash()),
        quota.queryLimit(),
        quota.visibleResults());
  }
}
