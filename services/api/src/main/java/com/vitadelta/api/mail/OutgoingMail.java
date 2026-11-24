package com.vitadelta.api.mail;

import com.vitadelta.api.common.logging.EmailMasking;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * An e-mail to render and send: recipient, preferred language and typed template content. Hand it
 * to {@link AuthMailDispatcher}; rendering and delivery happen on the dispatcher's threads.
 *
 * @param to recipient address
 * @param locale preferred language of the recipient ({@code null} means English)
 * @param model template content
 */
public record OutgoingMail(String to, Locale locale, MailModel model) {

  public OutgoingMail {
    if (to == null || to.isBlank()) {
      throw new IllegalArgumentException("recipient must not be blank");
    }
    Objects.requireNonNull(model, "model");
    to = to.strip();
    locale = MailLocale.of(locale).locale();
  }

  /** The {@code verify-email} mail with the raw verification token. */
  public static OutgoingMail verifyEmail(String to, Locale locale, String token) {
    return new OutgoingMail(to, locale, new VerifyEmailMail(token));
  }

  /** The {@code already-registered} mail. */
  public static OutgoingMail alreadyRegistered(String to, Locale locale) {
    return new OutgoingMail(to, locale, new AlreadyRegisteredMail());
  }

  /** The {@code reset-password} mail with the raw reset token. */
  public static OutgoingMail resetPassword(String to, Locale locale, String token) {
    return new OutgoingMail(to, locale, new ResetPasswordMail(token));
  }

  /** The {@code plan-activated} mail. */
  public static OutgoingMail planActivated(
      String to, Locale locale, String planName, Instant periodEnd) {
    return new OutgoingMail(to, locale, new PlanActivatedMail(planName, periodEnd));
  }

  /** The {@code plan-expired} mail. */
  public static OutgoingMail planExpired(String to, Locale locale, String planName) {
    return new OutgoingMail(to, locale, new PlanExpiredMail(planName));
  }

  /** Masks the recipient and names only the template. */
  @Override
  public String toString() {
    return "OutgoingMail[to="
        + EmailMasking.mask(to)
        + ", locale="
        + locale.toLanguageTag()
        + ", template="
        + model.templateName()
        + "]";
  }
}
