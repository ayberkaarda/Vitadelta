package com.vitadelta.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitadelta.api.VitadeltaApiApplication;
import com.vitadelta.api.support.PostgresIntegrationTest;
import java.util.Arrays;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

/**
 * The application refuses to start without {@code VITADELTA_JWT_SECRET}, {@code
 * VITADELTA_COOKIE_SECRET} and {@code VITADELTA_WEB_BASE_URL}, and with an http base URL outside
 * the {@code dev} profile; the failure names the variable.
 */
class StartupFailFastTest extends PostgresIntegrationTest {

  private static ConfigurableApplicationContext start(
      Predicate<String> keep, String[] profiles, String... extra) {
    String[] properties =
        java.util.stream.Stream.concat(
                Arrays.stream(applicationProperties()).filter(keep), Arrays.stream(extra))
            .toArray(String[]::new);
    return new SpringApplicationBuilder(VitadeltaApiApplication.class)
        .web(WebApplicationType.NONE)
        .profiles(profiles)
        .properties(properties)
        .run();
  }

  @ParameterizedTest(name = "without {0}")
  @CsvSource({
    "VITADELTA_JWT_SECRET, VITADELTA_JWT_SECRET is not set",
    "VITADELTA_COOKIE_SECRET, VITADELTA_COOKIE_SECRET is not set",
    "VITADELTA_WEB_BASE_URL, VITADELTA_WEB_BASE_URL is not set",
    "VITADELTA_MAIL_FROM, VITADELTA_MAIL_FROM is not set",
    "VITADELTA_PRIVACY_CONTACT, VITADELTA_PRIVACY_CONTACT is not set"
  })
  void refusesToStartWithoutARequiredVariable(String variable, String message) {
    assertThatThrownBy(() -> start(p -> !p.startsWith(variable + "="), new String[0]))
        .hasStackTraceContaining(message);
  }

  /**
   * The configuration is validated before the first database connection: with an unreachable
   * database the failure still names the missing variable, and no connection was attempted.
   */
  @ParameterizedTest(name = "without {0}, database unreachable")
  @CsvSource({
    "VITADELTA_JWT_SECRET, VITADELTA_JWT_SECRET is not set",
    "VITADELTA_COOKIE_SECRET, VITADELTA_COOKIE_SECRET is not set",
    "VITADELTA_WEB_BASE_URL, VITADELTA_WEB_BASE_URL is not set",
    "VITADELTA_PRIVACY_CONTACT, VITADELTA_PRIVACY_CONTACT is not set"
  })
  void theMissingVariableIsNamedBeforeTheDatabaseIsContacted(String variable, String message) {
    Predicate<String> keep =
        p -> !p.startsWith(variable + "=") && !p.startsWith("VITADELTA_DB_URL=");
    assertThatThrownBy(
            () -> start(keep, new String[0], "VITADELTA_DB_URL=jdbc:postgresql://127.0.0.1:9/none"))
        .hasStackTraceContaining(message)
        .satisfies(e -> {
          java.io.StringWriter trace = new java.io.StringWriter();
          e.printStackTrace(new java.io.PrintWriter(trace));
          assertThat(trace.toString())
              .doesNotContain("Connection refused")
              .doesNotContain("FlywaySqlException")
              .doesNotContain("PSQLException");
        });
  }

  @Test
  void thePrivacyContactIsOptionalInTheDevProfileOnly() {
    Predicate<String> keep =
        p ->
            !p.startsWith("VITADELTA_PRIVACY_CONTACT=") && !p.startsWith("VITADELTA_WEB_BASE_URL=");
    try (ConfigurableApplicationContext dev =
        start(
            keep,
            new String[] {"dev"},
            "VITADELTA_WEB_BASE_URL=http://localhost:8080",
            "VITADELTA_JOBS_ENABLED=false")) {
      assertThat(
              dev.getBean(com.vitadelta.api.config.properties.OperationsProperties.class)
                  .privacyContact())
          .isNull();
    }
  }

  @Test
  void refusesAShortJwtSecret() {
    assertThatThrownBy(() ->
            start(
                p -> !p.startsWith("VITADELTA_JWT_SECRET="),
                new String[0],
                "VITADELTA_JWT_SECRET=too-short"))
        .hasStackTraceContaining("VITADELTA_JWT_SECRET is too short");
  }

  @Test
  void refusesAnHttpBaseUrlOutsideTheDevProfile() {
    Predicate<String> withoutBaseUrl = p -> !p.startsWith("VITADELTA_WEB_BASE_URL=");
    assertThatThrownBy(
            () -> start(withoutBaseUrl, new String[0], "VITADELTA_WEB_BASE_URL=http://localhost"))
        .hasStackTraceContaining("must use https outside the dev profile");
    try (ConfigurableApplicationContext dev =
        start(
            withoutBaseUrl,
            new String[] {"dev"},
            "VITADELTA_WEB_BASE_URL=http://localhost:8080",
            "VITADELTA_JOBS_ENABLED=false")) {
      assertThat(
              dev.getBean(com.vitadelta.api.config.properties.WebProperties.class).secureCookies())
          .isFalse();
      assertThat(dev.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
      JavaMailSenderImpl mail = dev.getBean(JavaMailSenderImpl.class);
      assertThat(mail.getHost()).isEqualTo("localhost");
      assertThat(mail.getPort()).isEqualTo(2525);
    }
  }

  @Test
  void schedulingIsEnabledByDefault() {
    try (ConfigurableApplicationContext context = start(p -> true, new String[0])) {
      assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isNotEmpty();
      assertThat(
              context
                  .getBean(com.vitadelta.api.config.properties.WebProperties.class)
                  .secureCookies())
          .isTrue();
    }
  }
}
