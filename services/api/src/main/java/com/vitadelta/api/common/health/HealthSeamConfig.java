package com.vitadelta.api.common.health;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Default of the outbox health seam, registered only when no other {@link OutboxHealthContributor}
 * bean exists (before the outbox module is part of the application).
 */
@Configuration(proxyBeanMethods = false)
public class HealthSeamConfig {

  @Bean
  @ConditionalOnMissingBean(OutboxHealthContributor.class)
  OutboxHealthContributor emptyOutboxHealthContributor() {
    return () -> OutboxStats.EMPTY;
  }
}
