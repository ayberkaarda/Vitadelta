package com.vitadelta.api.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bootstrap administrator ({@code VITADELTA_BOOTSTRAP_ADMIN_EMAIL}, {@code
 * VITADELTA_BOOTSTRAP_ADMIN_PASSWORD}). Both are set or both are empty; with both empty no
 * administrator is created. {@link #toString()} never reveals the password.
 */
@ConfigurationProperties(prefix = "vitadelta.bootstrap-admin")
public record BootstrapAdminProperties(String email, String password) {

  public BootstrapAdminProperties {
    email = email == null || email.isBlank() ? null : email.strip();
    password = password == null || password.isEmpty() ? null : password;
    if ((email == null) != (password == null)) {
      throw new IllegalArgumentException(
          "VITADELTA_BOOTSTRAP_ADMIN_EMAIL and VITADELTA_BOOTSTRAP_ADMIN_PASSWORD must be set"
              + " together or both left empty");
    }
    if (email != null && (email.indexOf('@') < 1 || email.indexOf('@') == email.length() - 1)) {
      throw new IllegalArgumentException(
          "VITADELTA_BOOTSTRAP_ADMIN_EMAIL is not an e-mail address");
    }
  }

  /** Whether a bootstrap administrator is configured. */
  public boolean configured() {
    return email != null;
  }

  @Override
  public String toString() {
    return "BootstrapAdminProperties[configured=" + configured() + "]";
  }
}
