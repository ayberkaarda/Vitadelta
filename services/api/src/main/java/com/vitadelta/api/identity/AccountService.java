package com.vitadelta.api.identity;

import com.vitadelta.api.common.problem.FieldViolation;
import com.vitadelta.api.common.problem.ProblemCode;
import com.vitadelta.api.common.problem.ProblemException;
import com.vitadelta.api.identity.IdentityApi.Me;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** {@code GET|PATCH|DELETE /me}: profile, settings and account deletion. */
@Service
public class AccountService {

  private static final Logger LOG = LoggerFactory.getLogger(AccountService.class);

  static final String DISPLAY_CURRENCY = "displayCurrency";
  static final String LOCALE = "locale";
  static final String DEFAULT_COUNTRY_CODE = "defaultCountryCode";
  static final String EMAIL_ALERTS_ENABLED = "emailAlertsEnabled";
  private static final Set<String> SETTINGS =
      Set.of(DISPLAY_CURRENCY, LOCALE, DEFAULT_COUNTRY_CODE, EMAIL_ALERTS_ENABLED);
  private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
  private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");
  private static final Set<String> LOCALES = Set.of("tr", "en");

  private final UserRepository users;
  private final AccountUsageProvider usage;
  private final DisplayCurrencyPolicy currencies;
  private final PasswordHasher hasher;
  private final LoginThrottle throttle;
  private final ApplicationEventPublisher events;
  private final Clock clock;
  private final TransactionTemplate transactions;

  public AccountService(
      UserRepository users,
      AccountUsageProvider usage,
      DisplayCurrencyPolicy currencies,
      PasswordHasher hasher,
      LoginThrottle throttle,
      ApplicationEventPublisher events,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.users = users;
    this.usage = usage;
    this.currencies = currencies;
    this.hasher = hasher;
    this.throttle = throttle;
    this.events = events;
    this.clock = clock;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /** The signed-in user; {@code 401} when the account no longer exists. */
  UserAccount user(UUID userId) {
    return users.findById(userId).orElseThrow(CurrentUser::unauthorized);
  }

  /** Profile with plan, subscription and usage meters. */
  public Me me(UUID userId) {
    UserAccount user = user(userId);
    return Me.of(user, usage.summary(userId));
  }

  /** The plan summary of the user (for {@code GET /session/quota}). */
  public AccountPlanSummary summary(UUID userId) {
    user(userId);
    return usage.summary(userId);
  }

  /** Applies the settings present in {@code body} ({@code UpdateMeRequest}). */
  public Me update(UUID userId, JsonNode body) {
    transactions.executeWithoutResult(status -> applySettings(userId, body));
    return me(userId);
  }

  /** Read-merge-write under the user-row lock, so concurrent updates never lose a field. */
  private void applySettings(UUID userId, JsonNode body) {
    UserAccount current = users.findByIdForUpdate(userId).orElseThrow(CurrentUser::unauthorized);
    if (body == null || !body.isObject() || SETTINGS.stream().noneMatch(body::has)) {
      throw ProblemException.validation(
          "The request contains invalid fields.",
          List.of(new FieldViolation("body", "required", "At least one setting must be present.")));
    }
    List<FieldViolation> errors = new ArrayList<>();
    String displayCurrency = current.displayCurrency();
    if (body.has(DISPLAY_CURRENCY)) {
      JsonNode value = body.get(DISPLAY_CURRENCY);
      if (value.isNull()) {
        displayCurrency = null;
      } else if (value.isString() && CURRENCY.matcher(value.asString()).matches()) {
        displayCurrency = value.asString();
      } else {
        errors.add(invalid(DISPLAY_CURRENCY, "Expected an ISO 4217 code or null."));
      }
    }
    String locale = current.locale();
    if (body.has(LOCALE)) {
      JsonNode value = body.get(LOCALE);
      if (value.isString() && LOCALES.contains(value.asString())) {
        locale = value.asString();
      } else {
        errors.add(invalid(LOCALE, "Expected tr or en."));
      }
    }
    String country = current.defaultCountryCode();
    if (body.has(DEFAULT_COUNTRY_CODE)) {
      JsonNode value = body.get(DEFAULT_COUNTRY_CODE);
      if (value.isString() && COUNTRY.matcher(value.asString()).matches()) {
        country = value.asString();
      } else {
        errors.add(invalid(DEFAULT_COUNTRY_CODE, "Expected an ISO 3166-1 alpha-2 code."));
      }
    }
    boolean alerts = current.emailAlertsEnabled();
    if (body.has(EMAIL_ALERTS_ENABLED)) {
      JsonNode value = body.get(EMAIL_ALERTS_ENABLED);
      if (value.isBoolean()) {
        alerts = value.booleanValue();
      } else {
        errors.add(invalid(EMAIL_ALERTS_ENABLED, "Expected true or false."));
      }
    }
    if (!errors.isEmpty()) {
      throw ProblemException.validation("The request contains invalid fields.", errors);
    }
    if (body.has(DEFAULT_COUNTRY_CODE) && !users.countryExists(country)) {
      throw ProblemException.of(ProblemCode.COUNTRY_NOT_FOUND, "The country does not exist.");
    }
    if (body.has(DISPLAY_CURRENCY)
        && displayCurrency != null
        && !currencies.isSupported(displayCurrency)) {
      throw ProblemException.of(
          ProblemCode.FX_UNSUPPORTED_CURRENCY, "The display currency is not supported.");
    }
    UserAccount updated =
        new UserAccount(
            current.id(),
            current.email(),
            current.passwordHash(),
            current.emailVerifiedAt(),
            current.role(),
            current.status(),
            country,
            displayCurrency,
            locale,
            alerts,
            current.lastLoginAt(),
            current.createdAt(),
            clock.instant());
    users.updateSettings(updated, updated.updatedAt());
  }

  private static FieldViolation invalid(String field, String message) {
    return new FieldViolation(field, "invalid_format", message);
  }

  /**
   * Verifies the password (outside any transaction, throttled per account), then locks the user
   * row, confirms the stored hash is unchanged and hard-deletes the account with the cascade of
   * section 5.9. {@link UserDeletingEvent} is published inside the deletion transaction.
   */
  public void delete(UUID userId, String password) {
    UserAccount current = user(userId);
    // Attempts share the login throttle of the account: failures lock deletion and login alike,
    // and the expensive password check only runs for admitted attempts.
    LoginThrottle.Attempt attempt = throttle.begin(Emails.normalize(current.email()));
    try {
      // The memory-hard hash runs outside the transaction: no pooled connection or row lock is
      // held while it computes.
      if (!hasher.matches(password, current.passwordHash())) {
        attempt.failed();
        throw AuthService.invalidCredentials();
      }
      boolean deleted =
          Boolean.TRUE.equals(
              transactions.execute(status -> {
                Optional<UserAccount> locked = users.findByIdForUpdate(userId);
                if (locked.isEmpty()) {
                  return false;
                }
                if (!locked.get().passwordHash().equals(current.passwordHash())) {
                  // The password changed after it was verified.
                  attempt.failed();
                  throw AuthService.invalidCredentials();
                }
                events.publishEvent(new UserDeletingEvent(userId));
                return users.delete(userId);
              }));
      if (!deleted) {
        throw CurrentUser.unauthorized();
      }
      attempt.succeeded();
    } finally {
      attempt.release();
    }
    LOG.info("Account deleted (userId={})", userId);
  }
}
