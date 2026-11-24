package com.vitadelta.api.identity;

import com.vitadelta.api.common.paging.PageMeta;
import com.vitadelta.api.identity.AccountPlanSummary.PlanLimits;
import com.vitadelta.api.identity.AccountPlanSummary.SubscriptionView;
import com.vitadelta.api.identity.AccountPlanSummary.Usage;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Request and response bodies of the identity endpoints (contract {@code
 * contracts/public-api.openapi.yaml}). Records that carry passwords or tokens redact them in {@code
 * toString()}.
 */
public final class IdentityApi {

  private IdentityApi() {}

  static final String REDACTED = "<redacted>";

  /** {@code RegisterRequest}. */
  public record RegisterRequest(
      @NotBlank @Email @Size(max = 254) String email,
      @NotNull String password,
      @Pattern(regexp = "^(tr|en)$") String locale,
      @Pattern(regexp = "^[A-Z]{2}$") String countryCode) {

    @Override
    public String toString() {
      return "RegisterRequest[email=" + REDACTED + ", password=" + REDACTED + "]";
    }
  }

  /** {@code LoginRequest}. */
  public record LoginRequest(
      @NotBlank @Email @Size(max = 254) String email, @NotNull @Size(min = 1) String password) {

    @Override
    public String toString() {
      return "LoginRequest[email=" + REDACTED + ", password=" + REDACTED + "]";
    }
  }

  /** {@code EmailRequest}. */
  public record EmailRequest(@NotBlank @Email @Size(max = 254) String email) {

    @Override
    public String toString() {
      return "EmailRequest[email=" + REDACTED + "]";
    }
  }

  /** {@code TokenRequest}. */
  public record TokenRequest(
      @NotBlank @Size(max = 256) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String token) {

    @Override
    public String toString() {
      return "TokenRequest[token=" + REDACTED + "]";
    }
  }

  /** {@code ResetPasswordRequest}. */
  public record ResetPasswordRequest(
      @NotBlank @Size(max = 256) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String token,
      @NotNull String newPassword) {

    @Override
    public String toString() {
      return "ResetPasswordRequest[token=" + REDACTED + ", newPassword=" + REDACTED + "]";
    }
  }

  /** {@code DeleteAccountRequest}. */
  public record DeleteAccountRequest(@NotNull @Size(min = 1) String password) {

    @Override
    public String toString() {
      return "DeleteAccountRequest[password=" + REDACTED + "]";
    }
  }

  /** {@code SetUserStatusRequest}. */
  public record SetUserStatusRequest(@NotNull UserStatus status) {}

  /** {@code AcceptedMessage}: identical for known and unknown e-mail addresses. */
  public record AcceptedMessage(String messageKey) {}

  /** {@code UserProfile}. */
  public record UserProfile(
      UUID id,
      String email,
      boolean emailVerified,
      Role role,
      UserStatus status,
      String locale,
      String defaultCountryCode,
      String displayCurrency,
      boolean emailAlertsEnabled,
      Instant createdAt) {

    static UserProfile of(UserAccount user) {
      return new UserProfile(
          user.id(),
          user.email(),
          user.emailVerified(),
          user.role(),
          user.status(),
          user.locale(),
          user.defaultCountryCode(),
          user.displayCurrency(),
          user.emailAlertsEnabled(),
          user.createdAt());
    }
  }

  /** {@code AuthTokenResponse}. */
  public record AuthTokenResponse(
      String accessToken, String tokenType, long expiresIn, UserProfile user) {

    @Override
    public String toString() {
      return "AuthTokenResponse[accessToken=" + REDACTED + ", user=" + user.id() + "]";
    }
  }

  /** {@code Me}: the profile with plan, subscription and usage meters. */
  public record Me(
      UUID id,
      String email,
      boolean emailVerified,
      Role role,
      UserStatus status,
      String locale,
      String defaultCountryCode,
      String displayCurrency,
      boolean emailAlertsEnabled,
      Instant createdAt,
      PlanLimits plan,
      SubscriptionView subscription,
      Usage usage) {

    static Me of(UserAccount user, AccountPlanSummary summary) {
      return new Me(
          user.id(),
          user.email(),
          user.emailVerified(),
          user.role(),
          user.status(),
          user.locale(),
          user.defaultCountryCode(),
          user.displayCurrency(),
          user.emailAlertsEnabled(),
          user.createdAt(),
          summary.plan(),
          summary.subscription(),
          summary.usage());
    }
  }

  /** {@code AnonymousQuota}. */
  public record AnonymousQuota(
      String principal, int queriesUsed, int queriesLimit, int visibleResults) {}

  /** {@code UserQuota}. */
  public record UserQuota(String principal, String plan, Usage usage) {}

  /** {@code AdminUser}: never contains the password hash. */
  public record AdminUser(
      UUID id,
      String email,
      Role role,
      UserStatus status,
      String plan,
      boolean emailVerified,
      Instant createdAt) {}

  /** {@code AdminUserPage}. */
  public record AdminUserPage(PageMeta meta, List<AdminUser> items) {}
}
