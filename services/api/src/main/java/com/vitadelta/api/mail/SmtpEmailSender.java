package com.vitadelta.api.mail;

import com.vitadelta.api.config.properties.MailSettingsProperties;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * {@link EmailSender} over SMTP. The connection (host, port, optional username and password,
 * STARTTLS) comes from the {@code VITADELTA_SMTP_*} variables through the auto-configured {@link
 * JavaMailSender}; the sender address is {@code VITADELTA_MAIL_FROM}. Every message is a UTF-8
 * multipart message with a plain-text and an HTML alternative.
 */
@Component
public class SmtpEmailSender implements EmailSender {

  private final JavaMailSender mailSender;
  private final String from;

  public SmtpEmailSender(JavaMailSender mailSender, MailSettingsProperties settings) {
    this.mailSender = mailSender;
    this.from = settings.from();
  }

  @Override
  public void send(EmailMessage message) {
    try {
      MimeMessage mime = mailSender.createMimeMessage();
      MimeMessageHelper helper =
          new MimeMessageHelper(
              mime, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, StandardCharsets.UTF_8.name());
      helper.setFrom(from);
      helper.setTo(message.to());
      helper.setSubject(message.subject());
      helper.setText(message.textBody(), message.htmlBody());
      mime.setHeader("Content-Language", message.locale().toLanguageTag());
      mailSender.send(mime);
    } catch (MailException | MessagingException e) {
      throw new EmailDeliveryException("SMTP delivery failed: " + e.getClass().getSimpleName(), e);
    }
  }
}
