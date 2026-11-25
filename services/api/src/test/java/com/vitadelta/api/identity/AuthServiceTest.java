package com.vitadelta.api.identity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vitadelta.api.common.problem.ProblemCode;
import com.vitadelta.api.common.problem.ProblemException;
import com.vitadelta.api.identity.IdentityApi.LoginRequest;
import com.vitadelta.api.mail.AuthMailDispatcher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Timing safety of login (the dummy Argon2 verification for unknown e-mail addresses) and the login
 * failure metric.
 */
class AuthServiceTest {

  private final UserRepository users = mock(UserRepository.class);
  private final PasswordHasher hasher = mock(PasswordHasher.class);
  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-24T10:00:00Z"));
  private final LoginThrottle throttle = new LoginThrottle(clock);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  private double loginFailures(String reason) {
    var counter = meters.find(AuthService.LOGIN_FAILED_METRIC).tag("reason", reason).counter();
    return counter == null ? 0 : counter.count();
  }

  private AuthService service() {
    return new AuthService(
        users,
        hasher,
        mock(PasswordPolicy.class),
        mock(EmailTokenService.class),
        mock(RefreshTokenService.class),
        mock(AccessTokenService.class),
        mock(SubscriptionProvisioning.class),
        mock(AnonymousIdentityService.class),
        mock(AuthMailDispatcher.class),
        new RecipientMailLimiter(clock),
        throttle,
        clock,
        mock(PlatformTransactionManager.class),
        meters);
  }

  private static void assertInvalidCredentials(Runnable login) {
    assertThatThrownBy(login::run)
        .isInstanceOfSatisfying(
            ProblemException.class,
            e ->
                org.assertj.core.api.Assertions.assertThat(e.code())
                    .isEqualTo(ProblemCode.INVALID_CREDENTIALS));
  }

  @Test
  void unknownEmailsAreVerifiedAgainstTheDummyHash() {
    when(users.findByEmail(anyString())).thenReturn(Optional.empty());
    AuthService auth = service();
    assertInvalidCredentials(
        () -> auth.login(new LoginRequest("Nobody@Example.org", "some-password"), null));
    verify(hasher).verifyAgainstDummy("some-password");
    verify(users).findByEmail("nobody@example.org");
    verify(hasher, never()).matches(any(), any());
    org.assertj.core.api.Assertions.assertThat(loginFailures("invalid_credentials")).isEqualTo(1);
    org.assertj.core.api.Assertions.assertThat(
            meters.find(AuthService.LOGIN_FAILED_METRIC).counters())
        .allSatisfy(
            c -> org.assertj.core.api.Assertions.assertThat(c.getId().getTags()).hasSize(1));
  }

  @Test
  void attemptsDuringTheAccountBackOffAreCountedAsThrottled() {
    when(users.findByEmail(anyString())).thenReturn(Optional.empty());
    AuthService auth = service();
    for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) {
      String guess = "guess-" + i;
      assertInvalidCredentials(
          () -> auth.login(new LoginRequest("target@example.org", guess), null));
    }
    assertThatThrownBy(() -> auth.login(new LoginRequest("target@example.org", "again"), null))
        .isInstanceOf(ProblemException.class);
    org.assertj.core.api.Assertions.assertThat(loginFailures("throttled")).isEqualTo(1);
    org.assertj.core.api.Assertions.assertThat(loginFailures("invalid_credentials"))
        .isEqualTo(LoginThrottle.MAX_FAILURES);
  }

  @Test
  void wrongPasswordsUseTheRealHashAndDisabledAccountsAnswerOnlyAfterACorrectPassword() {
    UserAccount user =
        new UserAccount(
            UUID.randomUUID(),
            "someone@example.org",
            "$argon2id$stored",
            null,
            Role.USER,
            UserStatus.DISABLED,
            null,
            null,
            "en",
            true,
            null,
            clock.instant(),
            clock.instant());
    when(users.findByEmail("someone@example.org")).thenReturn(Optional.of(user));
    when(users.findByIdForUpdate(user.id())).thenReturn(Optional.of(user));
    when(hasher.matches("wrong", "$argon2id$stored")).thenReturn(false);
    when(hasher.matches("right-password", "$argon2id$stored")).thenReturn(true);
    AuthService auth = service();
    assertInvalidCredentials(
        () -> auth.login(new LoginRequest("someone@example.org", "wrong"), null));
    verify(hasher, never()).verifyAgainstDummy(any());
    org.assertj.core.api.Assertions.assertThat(loginFailures("invalid_credentials")).isEqualTo(1);
    assertThatThrownBy(
            () -> auth.login(new LoginRequest("someone@example.org", "right-password"), null))
        .isInstanceOfSatisfying(
            ProblemException.class,
            e ->
                org.assertj.core.api.Assertions.assertThat(e.code())
                    .isEqualTo(ProblemCode.ACCOUNT_DISABLED));
    org.assertj.core.api.Assertions.assertThat(loginFailures("account_disabled")).isEqualTo(1);
  }
}
