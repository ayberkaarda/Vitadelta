package com.vitadelta.api.entitlement;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Default implementation of the entitlement package's seam to the FX module, registered only when
 * no other {@link DisplayPriceConverter} bean exists.
 */
@Configuration(proxyBeanMethods = false)
public class EntitlementSeamConfig {

  @Bean
  @ConditionalOnMissingBean(DisplayPriceConverter.class)
  DisplayPriceConverter nativePriceConverter() {
    return new NativePriceConverter();
  }
}
