package com.vitadelta.api.identity;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Default implementations of the seams to other modules. Each is registered only when no other bean
 * of its type exists, so the entitlement, billing and FX modules replace them by declaring a
 * {@code @Component} (or {@code @Bean}) of the interface:
 *
 * <ul>
 *   <li>{@link SubscriptionProvisioning}: the FREE subscription of a new account;
 *   <li>{@link AccountUsageProvider}: plan, subscription and usage meters of {@code GET /me};
 *   <li>{@link DisplayCurrencyPolicy}: supported display currencies of {@code PATCH /me}.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class IdentitySeamConfig {

  @Bean
  @ConditionalOnMissingBean(SubscriptionProvisioning.class)
  SubscriptionProvisioning jdbcSubscriptionProvisioning(JdbcClient jdbc) {
    return new JdbcSubscriptionProvisioning(jdbc);
  }

  @Bean
  @ConditionalOnMissingBean(AccountUsageProvider.class)
  AccountUsageProvider jdbcAccountUsageProvider(JdbcClient jdbc, Clock clock) {
    return new JdbcAccountUsageProvider(jdbc, clock);
  }

  @Bean
  @ConditionalOnMissingBean(DisplayCurrencyPolicy.class)
  DisplayCurrencyPolicy countryCurrencyPolicy(JdbcClient jdbc) {
    return new CountryCurrencyPolicy(jdbc);
  }
}
