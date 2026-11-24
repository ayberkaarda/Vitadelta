package com.vitadelta.api.mail;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of {@link AuthMailDispatcher}.
 *
 * @param poolSize number of sender threads (at least 1)
 * @param queueCapacity number of mails waiting for a sender thread; further mails are dropped (at
 *     least 1)
 * @param shutdownTimeout how long shutdown waits for queued mails to be sent
 */
@ConfigurationProperties(prefix = "vitadelta.mail.dispatch")
public record MailDispatchProperties(
    @DefaultValue("2") int poolSize,
    @DefaultValue("500") int queueCapacity,
    @DefaultValue("10s") Duration shutdownTimeout) {

  public MailDispatchProperties {
    if (poolSize < 1) {
      throw new IllegalArgumentException("vitadelta.mail.dispatch.pool-size must be at least 1");
    }
    if (queueCapacity < 1) {
      throw new IllegalArgumentException(
          "vitadelta.mail.dispatch.queue-capacity must be at least 1");
    }
    if (shutdownTimeout == null || shutdownTimeout.isNegative()) {
      throw new IllegalArgumentException(
          "vitadelta.mail.dispatch.shutdown-timeout must not be negative");
    }
  }
}
