package com.vitadelta.api.mail;

import java.util.Map;

/**
 * The {@code already-registered} template, sent when someone registers with an address that already
 * has an account: links to the login and forgotten-password pages.
 */
public record AlreadyRegisteredMail() implements MailModel {

  public static final String TEMPLATE = "already-registered";

  @Override
  public String templateName() {
    return TEMPLATE;
  }

  @Override
  public Map<String, Object> variables(MailLinks links, MailLocale locale) {
    return Map.of("loginUrl", links.login(), "forgotUrl", links.forgotPassword());
  }
}
