package com.vitadelta.api.identity;

import com.vitadelta.api.common.problem.FieldViolation;
import com.vitadelta.api.common.problem.ProblemException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Password rules of registration, password reset and the bootstrap administrator (section 8.2): at
 * least {@value #MIN_LENGTH} characters and not in the breached-password list shipped as {@code
 * data/reference/weak_passwords.txt}.
 *
 * <p>The list is read once at startup from {@link IdentityProperties#weakPasswordsFile()} or, when
 * that is unset, from {@code <vitadelta.seed.data-dir>/reference/weak_passwords.txt}. The
 * application refuses to start when the file is missing, unreadable or empty. Entries are compared
 * case-insensitively.
 */
@Component
public class PasswordPolicy {

  /** Minimum password length in characters. */
  public static final int MIN_LENGTH = 10;

  /** File name of the list below the data directory. */
  public static final String LIST_PATH = "reference/weak_passwords.txt";

  private final Set<String> breached;

  @Autowired
  public PasswordPolicy(
      IdentityProperties properties, @Value("${vitadelta.seed.data-dir:}") String dataDir) {
    this(listFile(properties.weakPasswordsFile(), dataDir));
  }

  PasswordPolicy(Path file) {
    this.breached = load(file);
  }

  private static Path listFile(Path configured, String dataDir) {
    if (configured != null) {
      return configured;
    }
    if (dataDir == null || dataDir.isBlank()) {
      throw new IllegalStateException(
          "The breached-password list location is not configured: set"
              + " vitadelta.identity.weak-passwords-file or vitadelta.seed.data-dir");
    }
    return Path.of(dataDir).resolve(LIST_PATH);
  }

  private static Set<String> load(Path file) {
    if (!Files.isRegularFile(file)) {
      throw new IllegalStateException(
          "The breached-password list "
              + file
              + " does not exist; set vitadelta.identity.weak-passwords-file or"
              + " vitadelta.seed.data-dir");
    }
    Set<String> entries = new HashSet<>();
    try {
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        String entry = line.strip();
        if (!entry.isEmpty()) {
          entries.add(entry.toLowerCase(Locale.ROOT));
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException("The breached-password list " + file + " is unreadable", e);
    }
    if (entries.isEmpty()) {
      throw new IllegalStateException("The breached-password list " + file + " is empty");
    }
    return Set.copyOf(entries);
  }

  /** Number of distinct entries of the breached-password list. */
  public int breachedListSize() {
    return breached.size();
  }

  /** Whether {@code password} is in the breached-password list (case-insensitive). */
  public boolean isBreached(String password) {
    return password != null && breached.contains(password.toLowerCase(Locale.ROOT));
  }

  /**
   * Checks the rules.
   *
   * @param field name of the request field, used in the error
   * @throws ProblemException {@code validation_failed} with code {@code too_short} or {@code
   *     breached_password}
   */
  public void check(String password, String field) {
    if (password == null || password.codePointCount(0, password.length()) < MIN_LENGTH) {
      throw ProblemException.validation(
          "The request contains invalid fields.",
          List.of(
              new FieldViolation(
                  field,
                  "too_short",
                  "The password must have at least " + MIN_LENGTH + " characters.")));
    }
    if (isBreached(password)) {
      throw ProblemException.validation(
          "The request contains invalid fields.",
          List.of(
              new FieldViolation(
                  field,
                  "breached_password",
                  "The password appears in a list of breached passwords.")));
    }
  }
}
