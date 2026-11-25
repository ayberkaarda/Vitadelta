package com.vitadelta.api.alerts;

import com.vitadelta.api.config.properties.MailSettingsProperties;
import com.vitadelta.api.mail.EmailDeliveryException;
import com.vitadelta.api.mail.EmailMessage;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * {@link AlertMailTransport} over the application's SMTP connection ({@code VITADELTA_SMTP_*},
 * sender {@code VITADELTA_MAIL_FROM}): the same UTF-8 multipart message as the identity mails plus
 * the {@code List-Unsubscribe} header. The {@code EmailMessage} port of the mail package has no
 * header support, so the alerts module sends through the shared {@link JavaMailSender} itself.
 */
@Component
public class SmtpAlertMailTransport implements AlertMailTransport {

  /** Header of the preference link (RFC 2369). */
  public static final String LIST_UNSUBSCRIBE = "List-Unsubscribe";

  private final JavaMailSender mailSender;
  private final String from;

  public SmtpAlertMailTransport(JavaMailSender mailSender, MailSettingsProperties settings) {
    this.mailSender = mailSender;
    this.from = settings.from();
  }

  @Override
  public void send(EmailMessage message, String unsubscribeUrl) {
    if (unsubscribeUrl == null
        || unsubscribeUrl.indexOf('\r') >= 0
        || unsubscribeUrl.indexOf('\n') >= 0
        || unsubscribeUrl.indexOf('>') >= 0) {
      throw new IllegalArgumentException("unsubscribe link must be a single-line URL");
    }
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
      mime.setHeader(LIST_UNSUBSCRIBE, "<" + unsubscribeUrl + ">");
      mailSender.send(mime);
    } catch (MailException | MessagingException e) {
      throw new EmailDeliveryException("SMTP delivery failed: " + e.getClass().getSimpleName(), e);
    }
  }
}
