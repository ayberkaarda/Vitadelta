package com.vitadelta.api.identity;

import com.vitadelta.api.VitadeltaApiApplication;
import com.vitadelta.api.common.jobs.JobLock;
import com.vitadelta.api.common.jobs.JobLockKeys;
import com.vitadelta.api.common.problem.ProblemException;
import com.vitadelta.api.config.properties.BootstrapAdminProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Admin bootstrap at startup (section 8.9), under the {@code ADMIN_BOOTSTRAP} advisory lock so that
 * two starting instances cannot both insert:
 *
 * <ul>
 *   <li>an ADMIN already exists: nothing happens;
 *   <li>no ADMIN and {@code VITADELTA_BOOTSTRAP_ADMIN_EMAIL}/{@code _PASSWORD} unset: a warning is
 *       logged; admin routes then answer 403 for everyone;
 *   <li>no ADMIN and the variables set: a verified ADMIN with a FREE subscription is created; the
 *       password must satisfy the password rules;
 *   <li>the e-mail belongs to an existing USER: startup fails (an existing account is never
 *       promoted).
 * </ul>
 */
@Component
@Profile("!" + VitadeltaApiApplication.SEED_PROFILE)
public class AdminBootstrap implements ApplicationRunner {

  private static final Logger LOG = LoggerFactory.getLogger(AdminBootstrap.class);

  private final BootstrapAdminProperties properties;
  private final UserRepository users;
  private final PasswordHasher hasher;
  private final PasswordPolicy passwordPolicy;
  private final SubscriptionProvisioning subscriptions;
  private final JobLock jobLock;
  private final Clock clock;
  private final TransactionTemplate transactions;

  public AdminBootstrap(
      BootstrapAdminProperties properties,
      UserRepository users,
      PasswordHasher hasher,
      PasswordPolicy passwordPolicy,
      SubscriptionProvisioning subscriptions,
      JobLock jobLock,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.properties = properties;
    this.users = users;
    this.hasher = hasher;
    this.passwordPolicy = passwordPolicy;
    this.subscriptions = subscriptions;
    this.jobLock = jobLock;
    this.clock = clock;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /** Outcome of a bootstrap run. */
  public enum Outcome {
    /** Another instance held the lock. */
    SKIPPED,
    /** An administrator already existed. */
    ADMIN_EXISTS,
    /** No administrator and no bootstrap variables; a warning was logged. */
    NOT_CONFIGURED,
    /** The bootstrap administrator was created. */
    CREATED
  }

  @Override
  public void run(ApplicationArguments args) {
    bootstrap();
  }

  /**
   * Runs the bootstrap.
   *
   * @throws IllegalStateException when the bootstrap e-mail belongs to an existing USER account or
   *     the bootstrap password violates the password rules
   */
  public Outcome bootstrap() {
    Outcome[] outcome = {Outcome.SKIPPED};
    jobLock.runExclusive(
        "AdminBootstrap", JobLockKeys.ADMIN_BOOTSTRAP, () -> outcome[0] = bootstrapLocked());
    return outcome[0];
  }

  private Outcome bootstrapLocked() {
    if (users.adminExists()) {
      return Outcome.ADMIN_EXISTS;
    }
    if (!properties.configured()) {
      LOG.warn(
          "No administrator exists and VITADELTA_BOOTSTRAP_ADMIN_EMAIL and"
              + " VITADELTA_BOOTSTRAP_ADMIN_PASSWORD are not set; admin routes answer 403 for"
              + " everyone");
      return Outcome.NOT_CONFIGURED;
    }
    String email = Emails.normalize(properties.email());
    if (users.findByEmail(email).isPresent()) {
      throw new IllegalStateException(
          "VITADELTA_BOOTSTRAP_ADMIN_EMAIL belongs to an existing USER account; existing accounts"
              + " are never promoted. Choose another address or resolve the account manually.");
    }
    try {
      passwordPolicy.check(properties.password(), "password");
    } catch (ProblemException _) {
      throw new IllegalStateException(
          "VITADELTA_BOOTSTRAP_ADMIN_PASSWORD violates the password rules (at least "
              + PasswordPolicy.MIN_LENGTH
              + " characters, not in the breached-password list)");
    }
    String passwordHash = hasher.hash(properties.password());
    Optional<UUID> created =
        transactions.execute(status -> {
          Instant now = clock.instant();
          UUID id = UUID.randomUUID();
          UserAccount admin =
              new UserAccount(
                  id,
                  email,
                  passwordHash,
                  now,
                  Role.ADMIN,
                  UserStatus.ACTIVE,
                  null,
                  null,
                  "en",
                  true,
                  null,
                  now,
                  now);
          if (!users.insertIfAbsent(admin)) {
            return Optional.<UUID>empty();
          }
          subscriptions.provisionFreeSubscription(id, now);
          return Optional.of(id);
        });
    if (created == null || created.isEmpty()) {
      throw new IllegalStateException(
          "VITADELTA_BOOTSTRAP_ADMIN_EMAIL belongs to an existing account; existing accounts are"
              + " never promoted. Choose another address or resolve the account manually.");
    }
    LOG.info("Bootstrap administrator created (userId={})", created.get());
    return Outcome.CREATED;
  }
}
