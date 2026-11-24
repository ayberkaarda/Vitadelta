package com.vitadelta.api.mail;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Map;
import java.util.Objects;

/**
 * The {@code plan-activated} template.
 *
 * @param planName display name of the activated plan
 * @param periodEnd end of the paid period; shown as a localized UTC date
 */
public record PlanActivatedMail(String planName, Instant periodEnd) implements MailModel {

  public static final String TEMPLATE = "plan-activated";

  public PlanActivatedMail {
    if (planName == null || planName.isBlank()) {
      throw new IllegalArgumentException("planName must not be blank");
    }
    Objects.requireNonNull(periodEnd, "periodEnd");
  }

  @Override
  public String templateName() {
    return TEMPLATE;
  }

  @Override
  public Map<String, Object> variables(MailLinks links, MailLocale locale) {
    String date =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
            .withLocale(locale.locale())
            .format(periodEnd.atZone(ZoneOffset.UTC).toLocalDate());
    return Map.of("planName", planName, "periodEnd", date, "accountUrl", links.account());
  }
}
