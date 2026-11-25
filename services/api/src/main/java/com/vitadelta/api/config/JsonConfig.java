package com.vitadelta.api.config;

import com.vitadelta.api.common.web.BodySafeJsonHttpMessageConverter;
import org.springframework.boot.http.converter.autoconfigure.ServerHttpMessageConvertersCustomizer;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON output of every response body. Decimal values ({@link java.math.BigDecimal}: money, scores,
 * ratios, FX rates, engine parameters) are written in plain notation with their scale, never in
 * scientific notation: a stored rate of {@code 0.00000001} is written as {@code 0.00000001}, not
 * {@code 1E-8} (contract {@code FxRate.rate}, section 7.7).
 *
 * <p>The JSON message converter is the {@link BodySafeJsonHttpMessageConverter} over the
 * application's {@link JsonMapper}: a body that cannot be read fails with an exception that quotes
 * no part of it (section 15.4). It takes the JSON slot of the server converters after Spring Boot's
 * own customizer, so the default order of the converters (strings and byte arrays before JSON) is
 * kept.
 */
@Configuration(proxyBeanMethods = false)
public class JsonConfig {

  @Bean
  JsonMapperBuilderCustomizer plainDecimals() {
    return builder -> builder.enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN);
  }

  @Bean
  @Order(Ordered.LOWEST_PRECEDENCE)
  ServerHttpMessageConvertersCustomizer bodySafeJsonConverter(JsonMapper jsonMapper) {
    return builder -> builder.withJsonConverter(new BodySafeJsonHttpMessageConverter(jsonMapper));
  }
}
