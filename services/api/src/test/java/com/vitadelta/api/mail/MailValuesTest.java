package com.vitadelta.api.mail;

import static com.vitadelta.api.mail.MailTestSupport.TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** Validation and log-safe string forms of the mail value types. */
class MailValuesTest {

  @Test
  void emailMessageRejectsHeaderInjectionAndMasksItsRecipient() {
    Locale en = Locale.ENGLISH;
    assertThatThrownBy(() -> new EmailMessage("a@example.org\r\nBcc: x@y", "s", "t", "h", en))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EmailMessage(" ", "s", "t", "h", en))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EmailMessage(null, "s", "t", "h", en))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EmailMessage("a@example.org", "s\nBcc: x", "t", "h", en))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EmailMessage("a@example.org", null, "t", "h", en))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EmailMessage("a@example.org", "s", null, "h", en))
        .isInstanceOf(NullPointerException.class);

    EmailMessage message =
        new EmailMessage(" alice@example.org ", "Subject", "token " + TOKEN, "<p>x</p>", en);
    assertThat(message.to()).isEqualTo("alice@example.org");
    assertThat(message.toString())
        .contains("a***@example.org")
        .doesNotContain("alice")
        .doesNotContain(TOKEN);
  }

  @Test
  void outgoingMailNormalizesLocaleAndMasksItsRecipient() {
    OutgoingMail mail = OutgoingMail.verifyEmail(" bob@example.org", Locale.of("tr", "TR"), TOKEN);
    assertThat(mail.to()).isEqualTo("bob@example.org");
    assertThat(mail.locale()).isEqualTo(Locale.forLanguageTag("tr"));
    assertThat(mail.toString())
        .contains("b***@example.org")
        .contains(VerifyEmailMail.TEMPLATE)
        .doesNotContain("bob@")
        .doesNotContain(TOKEN);
    assertThat(mail.model().toString()).doesNotContain(TOKEN);
    assertThat(OutgoingMail.resetPassword("b@example.org", null, TOKEN).model().toString())
        .doesNotContain(TOKEN);
    assertThatThrownBy(() -> OutgoingMail.alreadyRegistered("", Locale.ENGLISH))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void tokensMustBeBase64Url() {
    assertThatThrownBy(() -> new VerifyEmailMail("short"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ResetPasswordMail(TOKEN + "&next=https://evil.example"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining(TOKEN);
    assertThatThrownBy(() -> new VerifyEmailMail(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void planMailsRequireTheirData() {
    assertThatThrownBy(() -> new PlanActivatedMail(" ", Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PlanActivatedMail("PRO", null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new PlanExpiredMail(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void mailLocaleFallsBackToEnglish() {
    assertThat(MailLocale.of(Locale.forLanguageTag("tr-TR"))).isEqualTo(MailLocale.TR);
    assertThat(MailLocale.of(Locale.ENGLISH)).isEqualTo(MailLocale.EN);
    assertThat(MailLocale.of(Locale.FRENCH)).isEqualTo(MailLocale.EN);
    assertThat(MailLocale.of(null)).isEqualTo(MailLocale.EN);
  }

  @Test
  void dispatchPropertiesAreValidated() {
    assertThatThrownBy(() -> new MailDispatchProperties(0, 10, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MailDispatchProperties(1, 0, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MailDispatchProperties(1, 1, Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MailDispatchProperties(1, 1, null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
