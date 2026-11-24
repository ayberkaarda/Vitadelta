package com.vitadelta.api.mail;

import java.util.Map;

/**
 * The {@code reset-password} template: link to the reset page with the token in the fragment.
 *
 * @param token the raw RESET_PASSWORD token (base64url)
 */
public record ResetPasswordMail(String token) implements MailModel {

  public static final String TEMPLATE = "reset-password";

  public ResetPasswordMail {
    MailLinks.checkToken(token);
  }

  @Override
  public String templateName() {
    return TEMPLATE;
  }

  @Override
  public Map<String, Object> variables(MailLinks links, MailLocale locale) {
    return Map.of("resetUrl", links.resetPassword(token));
  }

  /** Never prints the token. */
  @Override
  public String toString() {
    return "ResetPasswordMail[token=***]";
  }
}
