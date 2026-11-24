package com.vitadelta.api.mail;

import java.util.Map;

/**
 * The {@code plan-expired} template.
 *
 * @param planName display name of the plan that expired
 */
public record PlanExpiredMail(String planName) implements MailModel {

  public static final String TEMPLATE = "plan-expired";

  public PlanExpiredMail {
    if (planName == null || planName.isBlank()) {
      throw new IllegalArgumentException("planName must not be blank");
    }
  }

  @Override
  public String templateName() {
    return TEMPLATE;
  }

  @Override
  public Map<String, Object> variables(MailLinks links, MailLocale locale) {
    return Map.of("planName", planName, "accountUrl", links.account());
  }
}
