package com.vitadelta.api.config.properties;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outgoing e-mail settings of the application. The SMTP connection itself ({@code
 * VITADELTA_SMTP_HOST}, {@code _PORT}, {@code _USERNAME}, {@code _PASSWORD}, {@code _STARTTLS}) is
 * bound to Spring Boot's {@code spring.mail.*} properties in {@code application.yml}, which
 * configure the auto-configured {@code JavaMailSender}.
 *
 * @param from sender address of every e-mail ({@code VITADELTA_MAIL_FROM}); required
 */
@ConfigurationProperties(prefix = "vitadelta.mail")
public record MailSettingsProperties(String from) {

  public MailSettingsProperties {
    if (from == null || from.isBlank()) {
      throw new IllegalArgumentException("VITADELTA_MAIL_FROM is not set");
    }
    from = from.strip();
    try {
      new InternetAddress(from, true);
    } catch (AddressException e) {
      throw new IllegalArgumentException("VITADELTA_MAIL_FROM is not a valid address: " + from, e);
    }
  }
}
