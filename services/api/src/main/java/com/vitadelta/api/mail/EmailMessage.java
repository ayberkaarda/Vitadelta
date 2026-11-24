package com.vitadelta.api.mail;

import com.vitadelta.api.common.logging.EmailMasking;
import java.util.Locale;
import java.util.Objects;

/**
 * A fully rendered e-mail, ready for an {@link EmailSender}.
 *
 * @param to recipient address
 * @param subject single-line subject
 * @param textBody plain-text part
 * @param htmlBody HTML part
 * @param locale language of the content
 */
public record EmailMessage(
    String to, String subject, String textBody, String htmlBody, Locale locale) {

  public EmailMessage {
    if (to == null || to.isBlank() || containsLineBreak(to)) {
      throw new IllegalArgumentException("recipient must be a single non-blank address");
    }
    if (subject == null || subject.isBlank() || containsLineBreak(subject)) {
      throw new IllegalArgumentException("subject must be a single non-blank line");
    }
    Objects.requireNonNull(textBody, "textBody");
    Objects.requireNonNull(htmlBody, "htmlBody");
    Objects.requireNonNull(locale, "locale");
    to = to.strip();
  }

  private static boolean containsLineBreak(String value) {
    return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
  }

  /** Never prints the bodies (they may carry tokens) and masks the recipient. */
  @Override
  public String toString() {
    return "EmailMessage[to="
        + EmailMasking.mask(to)
        + ", subject="
        + subject
        + ", locale="
        + locale.toLanguageTag()
        + "]";
  }
}
