package com.vitadelta.api.common.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Hooks {@link LogRedactor} into Spring Boot's structured (JSON) console logging: every string
 * value of every log event is redacted before it is written. Registered with {@code
 * logging.structured.json.customizer} in {@code application.yml}; the console always logs JSON, so
 * there is no unredacted output path.
 */
public class RedactingJsonMembersCustomizer
    implements StructuredLoggingJsonMembersCustomizer<ILoggingEvent> {

  @Override
  public void customize(JsonWriter.Members<ILoggingEvent> members) {
    members.applyingValueProcessor(JsonWriter.ValueProcessor.of(String.class, LogRedactor::redact));
  }
}
