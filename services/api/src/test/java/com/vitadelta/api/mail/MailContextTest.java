package com.vitadelta.api.mail;

import static com.vitadelta.api.mail.MailTestSupport.TOKEN;
import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.support.PostgresIntegrationTest;
import com.vitadelta.api.support.TestEnvironment;
import java.time.Duration;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/** Wiring of the mail package in the application context. */
@SpringBootTest
class MailContextTest extends PostgresIntegrationTest {

  @Autowired private AuthMailDispatcher dispatcher;
  @Autowired private MailTemplateRenderer renderer;
  @Autowired private EmailSender emailSender;
  @Autowired private JavaMailSender javaMailSender;
  @Autowired private MailDispatchProperties dispatchProperties;

  @Test
  void smtpSenderIsConfiguredFromTheSmtpVariables() {
    assertThat(emailSender).isInstanceOf(SmtpEmailSender.class);
    assertThat(javaMailSender).isInstanceOf(JavaMailSenderImpl.class);
    JavaMailSenderImpl impl = (JavaMailSenderImpl) javaMailSender;
    assertThat(impl.getHost()).isEqualTo("localhost");
    assertThat(impl.getPort()).isEqualTo(2525);
    assertThat(impl.getDefaultEncoding()).isEqualTo("UTF-8");
    assertThat(impl.getJavaMailProperties())
        .containsEntry("mail.smtp.starttls.enable", "false")
        .containsKey("mail.smtp.timeout");
  }

  @Test
  void dispatcherDefaultsApplyWithoutExplicitSettings() {
    assertThat(dispatchProperties.poolSize()).isPositive();
    assertThat(dispatchProperties.queueCapacity()).isPositive();
    assertThat(dispatchProperties.shutdownTimeout()).isPositive().isLessThan(Duration.ofMinutes(1));
    assertThat(dispatcher).isNotNull();
  }

  @Test
  void linksComeFromTheConfiguredWebBaseUrl() {
    EmailMessage message =
        renderer.render(OutgoingMail.resetPassword("ctx@example.org", Locale.ENGLISH, TOKEN));
    assertThat(message.textBody())
        .contains(TestEnvironment.WEB_BASE_URL + "/auth/reset#token=" + TOKEN)
        .contains(TestEnvironment.WEB_BASE_URL + "/privacy");
  }
}
