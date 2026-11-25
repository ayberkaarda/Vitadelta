package com.vitadelta.api.identity;

import com.vitadelta.api.common.problem.ProblemCode;
import com.vitadelta.api.common.problem.ProblemException;
import com.vitadelta.api.identity.EmailTokenService.Consumed;
import com.vitadelta.api.identity.EmailTokenService.Consumption;
import com.vitadelta.api.identity.EmailTokenService.Expired;
import com.vitadelta.api.identity.EmailTokenService.Invalid;
import com.vitadelta.api.identity.EmailTokenService.Kind;
import com.vitadelta.api.identity.IdentityApi.LoginRequest;
import com.vitadelta.api.identity.IdentityApi.RegisterRequest;
import com.vitadelta.api.identity.RecipientMailLimiter.MailKind;
import com.vitadelta.api.identity.RefreshTokenService.Rejected;
import com.vitadelta.api.identity.RefreshTokenService.Rotated;
import com.vitadelta.api.identity.RefreshTokenService.Rotation;
import com.vitadelta.api.mail.AuthMailDispatcher;
import com.vitadelta.api.mail.OutgoingMail;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The authentication flows of section 8.2. Enumeration and timing safety:
 *
 * <ul>
 *   <li>register, resend and forgot do the same synchronous work for known and unknown addresses
 *       (one Argon2 hash on both register paths), never send e-mail on the request thread (only
 *       through {@link AuthMailDispatcher} after commit) and answer identically;
 *   <li>login verifies the password against a dummy Argon2id hash for unknown addresses and answers
 *       {@code invalid_credentials} for unknown address and wrong password alike; {@code
 *       account_disabled} only after a correct password.
 * </ul>
 */
@Service
public class AuthService {

  private final UserRepository users;
  private final PasswordHasher hasher;
  private final PasswordPolicy passwordPolicy;
  private final EmailTokenService emailTokens;
  private final RefreshTokenService refreshTokens;
  private final AccessTokenService accessTokens;
  private final SubscriptionProvisioning subscriptions;
  private final AnonymousIdentityService anonymous;
  private final AuthMailDispatcher mail;
  private final RecipientMailLimiter recipients;
  private final LoginThrottle throttle;
  private final Clock clock;
  private final TransactionTemplate transactions;

  public AuthService(
      UserRepository users,
      PasswordHasher hasher,
      PasswordPolicy passwordPolicy,
      EmailTokenService emailTokens,
      RefreshTokenService refreshTokens,
      AccessTokenService accessTokens,
      SubscriptionProvisioning subscriptions,
      AnonymousIdentityService anonymous,
      AuthMailDispatcher mail,
      RecipientMailLimiter recipients,
      LoginThrottle throttle,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.users = users;
    this.hasher = hasher;
    this.passwordPolicy = passwordPolicy;
    this.emailTokens = emailTokens;
    this.refreshTokens = refreshTokens;
    this.accessTokens = accessTokens;
    this.subscriptions = subscriptions;
    this.anonymous = anonymous;
    this.mail = mail;
    this.recipients = recipients;
    this.throttle = throttle;
    this.clock = clock;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /**
   * A started or continued session.
   *
   * @param refreshToken the value of the {@code vd_refresh} cookie to set
   */
  public record Session(
      UserAccount user, AccessTokenService.IssuedAccessToken accessToken, String refreshToken) {

    @Override
    public String toString() {
      return "Session[user=" + user.id() + ", tokens=<redacted>]";
    }
  }

  /** Locale of an e-mail to the user. */
  static Locale mailLocale(String locale) {
    return Locale.forLanguageTag(locale == null ? "en" : locale);
  }

  /**
   * Registers an account or, for an existing e-mail, queues the "already registered" e-mail.
   *
   * @param anonymousIdHash the caller's anonymous identity, converted to the new account
   */
  public void register(RegisterRequest request, Optional<String> anonymousIdHash) {
    String email = Emails.normalize(request.email());
    passwordPolicy.check(request.password(), "password");
    String locale = registrationLocale(request.locale(), request.countryCode());
    String passwordHash = hasher.hash(request.password());
    transactions.executeWithoutResult(status -> {
      Instant now = clock.instant();
      UUID id = UUID.randomUUID();
      UserAccount user =
          new UserAccount(
              id,
              email,
              passwordHash,
              null,
              Role.USER,
              UserStatus.ACTIVE,
              request.countryCode(),
              null,
              locale,
              true,
              null,
              now,
              now);
      if (users.insertIfAbsent(user)) {
        subscriptions.provisionFreeSubscription(id, now);
        String token = emailTokens.issue(id, Kind.VERIFY_EMAIL, now);
        anonymousIdHash.ifPresent(idHash -> anonymous.convert(idHash, id));
        if (recipients.tryAcquire(MailKind.VERIFY_EMAIL, email)) {
          mail.dispatchAfterCommit(OutgoingMail.verifyEmail(email, mailLocale(locale), token));
        }
      } else {
        Optional<UserAccount> existing = users.findByEmail(email);
        if (existing.isPresent() && recipients.tryAcquire(MailKind.ALREADY_REGISTERED, email)) {
          mail.dispatchAfterCommit(
              OutgoingMail.alreadyRegistered(
                  existing.get().email(), mailLocale(existing.get().locale())));
        }
      }
    });
  }

  private String registrationLocale(String requested, String countryCode) {
    Optional<String> countryLocale = Optional.empty();
    if (countryCode != null) {
      countryLocale = users.countryDefaultLocale(countryCode);
      if (countryLocale.isEmpty()) {
        throw ProblemException.of(ProblemCode.COUNTRY_NOT_FOUND, "The country does not exist.");
      }
    }
    if (requested != null) {
      return requested;
    }
    return countryLocale
        .map(value -> value.substring(0, 2).toLowerCase(Locale.ROOT))
        .filter(value -> value.equals("tr") || value.equals("en"))
        .orElse("en");
  }

  /** Consumes a {@code VERIFY_EMAIL} token and marks the address verified. */
  public void verifyEmail(String token) {
    transactions.executeWithoutResult(status -> {
      Instant now = clock.instant();
      UUID userId = consumed(emailTokens.consume(token, Kind.VERIFY_EMAIL, now));
      users.markVerified(userId, now);
    });
  }

  /** Issues a new verification e-mail for an unverified, active account. */
  public void resendVerification(String rawEmail) {
    String email = Emails.normalize(rawEmail);
    boolean allowed = recipients.tryAcquire(MailKind.VERIFY_EMAIL, email);
    transactions.executeWithoutResult(status -> {
      Optional<UserAccount> user = users.findByEmail(email);
      if (allowed
          && user.isPresent()
          && !user.get().emailVerified()
          && user.get().status() == UserStatus.ACTIVE) {
        String token = emailTokens.issue(user.get().id(), Kind.VERIFY_EMAIL, clock.instant());
        mail.dispatchAfterCommit(
            OutgoingMail.verifyEmail(user.get().email(), mailLocale(user.get().locale()), token));
      }
    });
  }

  /** Issues a password-reset e-mail for an active account. */
  public void forgotPassword(String rawEmail) {
    String email = Emails.normalize(rawEmail);
    boolean allowed = recipients.tryAcquire(MailKind.RESET_PASSWORD, email);
    transactions.executeWithoutResult(status -> {
      Optional<UserAccount> user = users.findByEmail(email);
      if (allowed && user.isPresent() && user.get().status() == UserStatus.ACTIVE) {
        String token = emailTokens.issue(user.get().id(), Kind.RESET_PASSWORD, clock.instant());
        mail.dispatchAfterCommit(
            OutgoingMail.resetPassword(user.get().email(), mailLocale(user.get().locale()), token));
      }
    });
  }

  /**
   * Sets a new password with a {@code RESET_PASSWORD} token; invalidates every outstanding reset
   * token of the user and revokes all refresh-token families.
   */
  public void resetPassword(String token, String newPassword) {
    passwordPolicy.check(newPassword, "newPassword");
    String passwordHash = hasher.hash(newPassword);
    String email =
        transactions.execute(status -> {
          Instant now = clock.instant();
          UUID userId = consumed(emailTokens.consume(token, Kind.RESET_PASSWORD, now));
          Optional<UserAccount> locked = users.findByIdForUpdate(userId);
          users.updatePassword(userId, passwordHash, now);
          emailTokens.invalidateAll(userId, Kind.RESET_PASSWORD, now);
          refreshTokens.revokeAllForUser(userId);
          return locked.map(UserAccount::email).orElse(null);
        });
    if (email != null) {
      throttle.reset(Emails.normalize(email));
    }
  }

  private static UUID consumed(Consumption consumption) {
    return switch (consumption) {
      case Consumed(UUID userId) -> userId;
      case Expired _ ->
          throw ProblemException.of(
              ProblemCode.TOKEN_EXPIRED, "The link has expired. Request a new one.");
      case Invalid _ ->
          throw ProblemException.of(ProblemCode.TOKEN_INVALID, "The link is invalid or was used.");
    };
  }

  /** Logs in and starts a new refresh-token family. */
  public Session login(LoginRequest request, String userAgent) {
    String email = Emails.normalize(request.email());
    LoginThrottle.Attempt attempt = throttle.begin(email);
    try {
      Optional<UserAccount> found = users.findByEmail(email);
      if (found.isEmpty()) {
        hasher.verifyAgainstDummy(request.password());
        attempt.failed();
        throw invalidCredentials();
      }
      UserAccount verified = found.get();
      if (!hasher.matches(request.password(), verified.passwordHash())) {
        attempt.failed();
        throw invalidCredentials();
      }
      Session session =
          transactions.execute(status -> {
            UserAccount user =
                users
                    .findByIdForUpdate(verified.id())
                    .filter(current -> current.passwordHash().equals(verified.passwordHash()))
                    .orElse(null);
            if (user == null) {
              attempt.failed();
              throw invalidCredentials();
            }
            attempt.succeeded();
            if (user.status() == UserStatus.DISABLED) {
              throw ProblemException.of(ProblemCode.ACCOUNT_DISABLED, "The account is disabled.");
            }
            users.markLoggedIn(user.id(), clock.instant());
            String refreshToken = refreshTokens.issueNewFamily(user.id(), userAgent);
            return new Session(user, issueAccessToken(user), refreshToken);
          });
      return session;
    } finally {
      attempt.release();
    }
  }

  static ProblemException invalidCredentials() {
    return ProblemException.of(
        ProblemCode.INVALID_CREDENTIALS, "The e-mail address or password is incorrect.");
  }

  /**
   * Rotates the refresh token.
   *
   * @param clearCookie {@code Set-Cookie} value attached to every rejection
   */
  public Session refresh(String refreshToken, String userAgent, String clearCookie) {
    Rotation rotation = refreshTokens.rotate(refreshToken, userAgent);
    return switch (rotation) {
      case Rotated(UserAccount user, String token) ->
          new Session(user, issueAccessToken(user), token);
      case Rejected(RefreshTokenService.Reason reason) ->
          throw rejection(reason).withHeader(HttpHeaders.SET_COOKIE, clearCookie);
    };
  }

  private static ProblemException rejection(RefreshTokenService.Reason reason) {
    return switch (reason) {
      case REUSED ->
          ProblemException.of(
              ProblemCode.REFRESH_REUSE_DETECTED, "The session was revoked. Log in again.");
      case DISABLED ->
          ProblemException.of(ProblemCode.ACCOUNT_DISABLED, "The account is disabled.");
      case UNKNOWN, EXPIRED ->
          ProblemException.of(ProblemCode.UNAUTHORIZED, "The session is missing or has expired.");
    };
  }

  /** Revokes the family of the presented refresh token (if it is known). */
  public void logout(String refreshToken) {
    refreshTokens.revokeFamilyOf(refreshToken);
  }

  AccessTokenService.IssuedAccessToken issueAccessToken(UserAccount user) {
    return accessTokens.issue(user.id(), user.role(), user.emailVerified());
  }
}
