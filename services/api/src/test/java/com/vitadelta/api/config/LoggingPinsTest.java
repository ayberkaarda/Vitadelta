package com.vitadelta.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * The framework loggers that print request data (section 15.4) are pinned in {@code
 * application.yml}, so that raising the root level cannot enable them: only an explicit entry for
 * the logger itself does.
 */
class LoggingPinsTest {

  /** Loggers that print request lines, arguments, bodies, raw bytes or SQL bind values. */
  static final List<String> PINNED =
      List.of(
          "org.springframework.web",
          "org.springframework.http",
          "org.springframework.security",
          "org.springframework.jdbc.core",
          "org.apache.coyote",
          "org.apache.tomcat.util.net",
          "org.apache.tomcat.util.http",
          "org.hibernate.orm.jdbc.bind",
          "org.hibernate.orm.jdbc.extract",
          "org.hibernate.SQL");

  /** Loggers below the pins that printed request data at DEBUG or TRACE. */
  static final Map<String, String> LEAKING_CHILDREN =
      Map.of(
          "org.springframework.security.web.FilterChainProxy", "org.springframework.security",
          "org.springframework.web.method.HandlerMethod", "org.springframework.web",
          "org.springframework.web.method.support.InvocableHandlerMethod",
              "org.springframework.web",
          "org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor",
              "org.springframework.web",
          "org.springframework.http.converter.json.JacksonJsonHttpMessageConverter",
              "org.springframework.http",
          "org.apache.coyote.http11.Http11InputBuffer", "org.apache.coyote",
          "org.springframework.jdbc.core.StatementCreatorUtils", "org.springframework.jdbc.core");

  private static PropertySource<?> applicationYml() throws IOException {
    List<PropertySource<?>> sources =
        new YamlPropertySourceLoader()
            .load("application", new ClassPathResource("application.yml"));
    assertThat(sources).hasSize(1);
    return sources.get(0);
  }

  @Test
  void everyRequestDataLoggerIsPinnedToInfo() throws IOException {
    PropertySource<?> yml = applicationYml();
    for (String logger : PINNED) {
      assertThat(String.valueOf(yml.getProperty("logging.level[" + logger + "]")))
          .as(logger)
          .isEqualTo("INFO");
    }
  }

  @Test
  void aRaisedRootLevelLeavesThePinnedLoggersAtInfoUntilOneIsRaisedExplicitly() throws IOException {
    PropertySource<?> yml = applicationYml();
    LoggerContext context = new LoggerContext();
    context.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(Level.TRACE);
    for (String logger : PINNED) {
      Level level = Level.toLevel(String.valueOf(yml.getProperty("logging.level[" + logger + "]")));
      context.getLogger(logger).setLevel(level);
    }
    LEAKING_CHILDREN.forEach((child, pin) -> {
      assertThat(context.getLogger(child).isDebugEnabled()).as(child).isFalse();
      assertThat(context.getLogger(child).isInfoEnabled()).as(child).isTrue();
    });
    assertThat(context.getLogger("com.vitadelta.api.Probe").isTraceEnabled()).isTrue();

    // The documented override: an explicit level for the logger itself.
    context.getLogger("org.springframework.web").setLevel(Level.DEBUG);
    assertThat(context.getLogger("org.springframework.web.method.HandlerMethod").isDebugEnabled())
        .isTrue();
    context.stop();
  }
}
