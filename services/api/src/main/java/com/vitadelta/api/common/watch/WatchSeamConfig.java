package com.vitadelta.api.common.watch;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Default of the watch-state seam, registered only when no other {@link WatchStateReader} bean
 * exists (before the saved-products and alerts module is part of the application).
 */
@Configuration(proxyBeanMethods = false)
public class WatchSeamConfig {

  @Bean
  @ConditionalOnMissingBean(WatchStateReader.class)
  WatchStateReader emptyWatchStateReader() {
    return (userId, countryCode, productIds) -> Map.of();
  }
}
