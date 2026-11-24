package com.vitadelta.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitadelta.api.common.problem.ProblemException;
import com.vitadelta.api.support.TestData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Argon2id parameters (ADR-0006) and the password rules with the breached-password list. */
class PasswordRulesTest {

  private static final Path LIST = TestData.dataDir().resolve(PasswordPolicy.LIST_PATH);

  @Test
  void hashesUseArgon2idWithTheExplicitParameters() {
    PasswordHasher hasher = new PasswordHasher();
    String hash = hasher.hash("correct-horse-battery-staple");
    assertThat(hash).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
    String[] parts = hash.split("\\$");
    assertThat(Base64.getDecoder().decode(pad(parts[4]))).hasSize(16);
    assertThat(Base64.getDecoder().decode(pad(parts[5]))).hasSize(32);
    assertThat(hasher.matches("correct-horse-battery-staple", hash)).isTrue();
    assertThat(hasher.matches("wrong-horse-battery-staple", hash)).isFalse();
    assertThat(hasher.hash("correct-horse-battery-staple")).isNotEqualTo(hash);
    assertThat(hasher.verifyAgainstDummy("correct-horse-battery-staple")).isFalse();
  }

  private static String pad(String base64) {
    return base64 + "=".repeat((4 - base64.length() % 4) % 4);
  }

  @Test
  void theDummyVerificationCostsAboutAsMuchAsARealOne() {
    PasswordHasher hasher = new PasswordHasher();
    String hash = hasher.hash("correct-horse-battery-staple");
    hasher.matches("warm-up-password", hash);
    hasher.verifyAgainstDummy("warm-up-password");
    long real = time(() -> hasher.matches("some-password-value", hash));
    long dummy = time(() -> hasher.verifyAgainstDummy("some-password-value"));
    assertThat(dummy).isGreaterThan(real / 4);
    assertThat(real).isGreaterThan(dummy / 4);
  }

  private static long time(Runnable work) {
    long best = Long.MAX_VALUE;
    for (int i = 0; i < 3; i++) {
      long start = System.nanoTime();
      work.run();
      best = Math.min(best, System.nanoTime() - start);
    }
    return Duration.ofNanos(best).toMillis() + 1;
  }

  @Test
  void theBreachedListIsLoadedAndChecked() {
    PasswordPolicy policy = new PasswordPolicy(LIST);
    assertThat(policy.breachedListSize()).isGreaterThan(9000);
    assertThat(policy.isBreached("password")).isTrue();
    assertThat(policy.isBreached("PASSWORD")).isTrue();
    assertThat(policy.isBreached("correct-horse-battery-staple")).isFalse();

    policy.check("correct-horse-battery-staple", "password");
    assertThatThrownBy(() -> policy.check("short", "password"))
        .isInstanceOfSatisfying(
            ProblemException.class,
            e -> {
              assertThat(e.errors().get(0).code()).isEqualTo("too_short");
              assertThat(e.errors().get(0).field()).isEqualTo("password");
              assertThat(e.getMessage()).doesNotContain("short:");
            });
    assertThatThrownBy(() -> policy.check(null, "newPassword"))
        .isInstanceOf(ProblemException.class);
    assertThatThrownBy(() -> policy.check("1234567890", "newPassword"))
        .isInstanceOfSatisfying(
            ProblemException.class,
            e -> assertThat(e.errors().get(0).code()).isEqualTo("breached_password"));
  }

  @Test
  void startupFailsClearlyWithoutTheList(@TempDir Path directory) throws IOException {
    IdentityProperties defaults =
        new IdentityProperties(
            null, Duration.ofDays(180), Duration.ofDays(7), Duration.ofDays(30), 1000);
    assertThatThrownBy(() -> new PasswordPolicy(defaults, directory.toString()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("weak_passwords.txt")
        .hasMessageContaining("does not exist");
    assertThatThrownBy(() -> new PasswordPolicy(defaults, ""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("vitadelta.seed.data-dir");

    Path empty = Files.createDirectories(directory.resolve("reference"));
    Files.writeString(empty.resolve("weak_passwords.txt"), "\n \n");
    assertThatThrownBy(() -> new PasswordPolicy(defaults, directory.toString()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("is empty");

    Files.writeString(empty.resolve("weak_passwords.txt"), "letmein\n");
    assertThat(new PasswordPolicy(defaults, directory.toString()).isBreached("LetMeIn")).isTrue();
    IdentityProperties explicit =
        new IdentityProperties(
            LIST, Duration.ofDays(180), Duration.ofDays(7), Duration.ofDays(30), 1000);
    assertThat(new PasswordPolicy(explicit, "").breachedListSize()).isGreaterThan(9000);
  }

  @Test
  void identityPropertiesRejectInvalidValues() {
    assertThatThrownBy(() ->
            new IdentityProperties(null, Duration.ZERO, Duration.ofDays(7), Duration.ofDays(1), 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            new IdentityProperties(
                null, Duration.ofDays(1), Duration.ofDays(7), Duration.ofDays(1), 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new IdentityProperties(null, Duration.ofDays(1), null, Duration.ofDays(1), 1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
