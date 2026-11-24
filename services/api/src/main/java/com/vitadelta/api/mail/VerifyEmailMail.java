package com.vitadelta.api.mail;

import java.util.Map;

/**
 * The {@code verify-email} template: link to the verification page with the token in the fragment.
 *
 * @param token the raw VERIFY_EMAIL token (base64url)
 */
public record VerifyEmailMail(String token) implements MailModel {

  public static final String TEMPLATE = "verify-email";

  public VerifyEmailMail {
    MailLinks.checkToken(token);
  }

  @Override
  public String templateName() {
    return TEMPLATE;
  }

  @Override
  public Map<String, Object> variables(MailLinks links, MailLocale locale) {
    return Map.of("verifyUrl", links.verifyEmail(token));
  }

  /** Never prints the token. */
  @Override
  public String toString() {
    return "VerifyEmailMail[token=***]";
  }
}
