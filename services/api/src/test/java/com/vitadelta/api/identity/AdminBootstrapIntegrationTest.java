package com.vitadelta.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitadelta.api.VitadeltaApiApplication;
import com.vitadelta.api.common.jobs.JobLock;
import com.vitadelta.api.common.jobs.JobLockKeys;
import com.vitadelta.api.config.properties.BootstrapAdminProperties;
import com.vitadelta.api.support.TestData;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.PlatformTransactionManager;

/** Admin bootstrap from the environment, the collision failure and the advisory lock (8.9). */
@ExtendWith(OutputCaptureExtension.class)
class AdminBootstrapIntegrationTest extends IdentityIntegrationTest {

  @Autowired UserRepository users;
  @Autowired PasswordHasher hasher;
  @Autowired PasswordPolicy passwordPolicy;
  @Autowired SubscriptionProvisioning subscriptions;
  @Autowired JobLock jobLock;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired DataSource dataSource;

  @BeforeEach
  void removeAdministrators() {
    jdbc.sql("DELETE FROM app_user WHERE role = 'ADMIN'").update();
  }

  private AdminBootstrap bootstrap(String email, String password) {
    return new AdminBootstrap(
        new BootstrapAdminProperties(email, password),
        users,
        hasher,
        passwordPolicy,
        subscriptions,
        jobLock,
        clock,
        transactionManager);
  }

  @Test
  void createsAVerifiedAdminWithAFreeSubscriptionOnce(CapturedOutput output) {
    String email = "boot." + uniqueEmail();
    assertThat(bootstrap(email, PASSWORD).bootstrap()).isEqualTo(AdminBootstrap.Outcome.CREATED);
    UserAccount admin = users.findByEmail(email).orElseThrow();
    assertThat(admin.role()).isEqualTo(Role.ADMIN);
    assertThat(admin.emailVerified()).isTrue();
    assertThat(
            jdbc.sql("SELECT plan_code FROM subscription WHERE user_id = ? AND status = 'ACTIVE'")
                .param(admin.id())
                .query(String.class)
                .single())
        .isEqualTo("FREE");
    assertThat(output.getAll())
        .contains("Bootstrap administrator created (userId=" + admin.id() + ")")
        .doesNotContain(email)
        .doesNotContain(PASSWORD);

    assertThat(bootstrap("other." + uniqueEmail(), PASSWORD).bootstrap())
        .isEqualTo(AdminBootstrap.Outcome.ADMIN_EXISTS);
    Login session = login(email, PASSWORD);
    assertThat(get("/api/v1/admin/users", session.accessToken()).getStatus().value())
        .isEqualTo(200);
  }

  @Test
  void warnsWhenNoAdministratorAndNoVariables(CapturedOutput output) {
    assertThat(bootstrap(null, null).bootstrap()).isEqualTo(AdminBootstrap.Outcome.NOT_CONFIGURED);
    assertThat(output.getAll()).contains("No administrator exists");
  }

  @Test
  void anExistingUserIsNeverPromoted() {
    String email = uniqueEmail();
    registerVerified(email);
    assertThatThrownBy(
            () -> bootstrap(email.toUpperCase(java.util.Locale.ROOT), PASSWORD).bootstrap())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("never promoted")
        .hasMessageNotContaining(email);
    assertThat(users.findByEmail(email).orElseThrow().role()).isEqualTo(Role.USER);
  }

  @Test
  void weakBootstrapPasswordsAreRejected() {
    assertThatThrownBy(() -> bootstrap("weak." + uniqueEmail(), "1234567890").bootstrap())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("VITADELTA_BOOTSTRAP_ADMIN_PASSWORD")
        .hasMessageNotContaining("1234567890");
    assertThatThrownBy(() -> bootstrap("short." + uniqueEmail(), "short").bootstrap())
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aConcurrentHolderOfTheLockSkipsTheRun() throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement lock = connection.prepareStatement("SELECT pg_advisory_lock(?)")) {
      lock.setLong(1, JobLockKeys.ADMIN_BOOTSTRAP);
      lock.executeQuery().close();
      try {
        assertThat(bootstrap("locked." + uniqueEmail(), PASSWORD).bootstrap())
            .isEqualTo(AdminBootstrap.Outcome.SKIPPED);
      } finally {
        try (PreparedStatement unlock =
            connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
          unlock.setLong(1, JobLockKeys.ADMIN_BOOTSTRAP);
          unlock.executeQuery().close();
        }
      }
    }
  }

  @Test
  void startupFailsWhenTheBootstrapEmailBelongsToAUser() {
    String email = uniqueEmail();
    registerVerified(email);
    List<String> properties = new ArrayList<>(List.of(applicationProperties()));
    properties.addAll(
        List.of(
            "VITADELTA_BOOTSTRAP_ADMIN_EMAIL=" + email,
            "VITADELTA_BOOTSTRAP_ADMIN_PASSWORD=" + PASSWORD,
            "vitadelta.identity.weak-passwords-file="
                + TestData.dataDir().resolve(PasswordPolicy.LIST_PATH),
            "VITADELTA_JOBS_ENABLED=false"));
    assertThatThrownBy(() ->
            new SpringApplicationBuilder(VitadeltaApiApplication.class)
                .web(WebApplicationType.NONE)
                .properties(properties.toArray(String[]::new))
                .run()
                .close())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("VITADELTA_BOOTSTRAP_ADMIN_EMAIL")
        .hasMessageContaining("never promoted");
    assertThat(users.findByEmail(email).orElseThrow().role()).isEqualTo(Role.USER);
  }
}
