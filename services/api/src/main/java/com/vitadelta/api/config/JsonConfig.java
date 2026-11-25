package com.vitadelta.api.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamWriteFeature;

/**
 * JSON output of every response body. Decimal values ({@link java.math.BigDecimal}: money, scores,
 * ratios, FX rates, engine parameters) are written in plain notation with their scale, never in
 * scientific notation: a stored rate of {@code 0.00000001} is written as {@code 0.00000001}, not
 * {@code 1E-8} (contract {@code FxRate.rate}, section 7.7).
 */
@Configuration(proxyBeanMethods = false)
public class JsonConfig {

  @Bean
  JsonMapperBuilderCustomizer plainDecimals() {
    return builder -> builder.enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN);
  }
}
