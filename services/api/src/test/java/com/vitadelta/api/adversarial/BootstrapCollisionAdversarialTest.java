package com.vitadelta.api.adversarial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitadelta.api.VitadeltaApiApplication;
import com.vitadelta.api.support.TestData;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Section 15.7 case 19: starting the API with a bootstrap e-mail that already belongs to a USER (in
 * any letter case, with surrounding white space) fails startup and promotes nobody; the attacker's
 * password never becomes an administrator password.
 */
class BootstrapCollisionAdversarialTest extends AdversarialIntegrationTest {

  private void startWithBootstrap(String email, String password) {
    List<String> properties = new ArrayList<>(List.of(applicationProperties()));
    properties.addAll(
        List.of(
            "VITADELTA_BOOTSTRAP_ADMIN_EMAIL=" + email,
            "VITADELTA_BOOTSTRAP_ADMIN_PASSWORD=" + password,
            "vitadelta.identity.weak-passwords-file="
                + TestData.dataDir().resolve("reference/weak_passwords.txt"),
            "VITADELTA_JOBS_ENABLED=false"));
    new SpringApplicationBuilder(VitadeltaApiApplication.class)
        .web(WebApplicationType.NONE)
        .properties(properties.toArray(String[]::new))
        .run()
        .close();
  }

  @Test
  void bootstrapCollisionWithAnExistingUserFailsAndPromotesNobody() {
    jdbc.sql("DELETE FROM app_user WHERE role = 'ADMIN'").update();
    String email = uniqueEmail();
    UUID id = registerVerified(email);
    String hashBefore =
        jdbc.sql("SELECT password_hash FROM app_user WHERE id = ?")
            .param(id)
            .query(String.class)
            .single();
    String attackerPassword = "attacker-chosen-passphrase-9";

    for (String spelling : List.of(email, email.toUpperCase(Locale.ROOT), "  " + email + "  ")) {
      assertThatThrownBy(() -> startWithBootstrap(spelling, attackerPassword))
          .as(spelling)
          .hasStackTraceContaining("never promoted")
          .hasMessageNotContaining(email)
          .hasMessageNotContaining(attackerPassword);
      assertThat(
              jdbc.sql("SELECT role FROM app_user WHERE id = ?")
                  .param(id)
                  .query(String.class)
                  .single())
          .isEqualTo("USER");
    }
    assertThat(count("SELECT count(*) FROM app_user WHERE role = 'ADMIN'")).isZero();
    assertThat(count("SELECT count(*) FROM app_user WHERE lower(email::text) = lower(?)", email))
        .isEqualTo(1);
    assertThat(
            jdbc.sql("SELECT password_hash FROM app_user WHERE id = ?")
                .param(id)
                .query(String.class)
                .single())
        .isEqualTo(hashBefore);
    ip = randomIp();
    assertThat(
            postJson(
                    API + "/auth/login",
                    java.util.Map.of("email", email, "password", attackerPassword))
                .status())
        .isEqualTo(401);
  }
}
