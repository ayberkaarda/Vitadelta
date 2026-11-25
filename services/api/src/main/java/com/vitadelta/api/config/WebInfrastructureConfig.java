package com.vitadelta.api.config;

import com.vitadelta.api.common.web.LogContextFilter;
import com.vitadelta.api.common.web.RequestBodyLimitFilter;
import com.vitadelta.api.common.web.RequestIdFilter;
import com.vitadelta.api.common.web.RequestIds;
import com.vitadelta.api.config.properties.HttpLimitsProperties;
import com.vitadelta.api.config.properties.WebProperties;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Servlet infrastructure shared by every route: the request-id filter (first filter of the chain),
 * the logging-context filter, the request body size limit and the CORS policy.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebInfrastructureConfig {

  /** Order of the request-id filter: before every other filter, including Spring Security. */
  public static final int REQUEST_ID_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE;

  /**
   * Order of the body limit filter: inside the Spring Security filter chain's scope (after it), so
   * a {@code 413} written for a declared oversized body passes through the security header writer
   * like every other response (section 15.2).
   */
  public static final int BODY_LIMIT_FILTER_ORDER =
      SecurityFilterProperties.DEFAULT_FILTER_ORDER + 10;

  @Bean
  FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
    FilterRegistrationBean<RequestIdFilter> registration =
        new FilterRegistrationBean<>(new RequestIdFilter());
    registration.setOrder(REQUEST_ID_FILTER_ORDER);
    registration.addUrlPatterns("/*");
    return registration;
  }

  /**
   * Order of the logging-context filter: inside the Spring Security filter chain's scope, so the
   * authenticated principal is known, and before the body limit filter.
   */
  public static final int LOG_CONTEXT_FILTER_ORDER =
      SecurityFilterProperties.DEFAULT_FILTER_ORDER + 5;

  @Bean
  FilterRegistrationBean<LogContextFilter> logContextFilter() {
    FilterRegistrationBean<LogContextFilter> registration =
        new FilterRegistrationBean<>(new LogContextFilter());
    registration.setOrder(LOG_CONTEXT_FILTER_ORDER);
    registration.addUrlPatterns("/*");
    return registration;
  }

  @Bean
  FilterRegistrationBean<RequestBodyLimitFilter> requestBodyLimitFilter(
      HttpLimitsProperties limits) {
    FilterRegistrationBean<RequestBodyLimitFilter> registration =
        new FilterRegistrationBean<>(new RequestBodyLimitFilter(limits));
    registration.setOrder(BODY_LIMIT_FILTER_ORDER);
    registration.addUrlPatterns("/api/*");
    return registration;
  }

  /**
   * CORS for {@code /api/v1/**}: only the configured web origins, never with credentials (the web
   * app is served from the same origin as the API, §8.1; cross-origin calls get no cookies).
   */
  @Bean
  CorsConfigurationSource corsConfigurationSource(WebProperties web) {
    CorsConfiguration cors = new CorsConfiguration();
    cors.setAllowedOrigins(web.origins());
    cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
    cors.setAllowedHeaders(
        List.of(
            HttpHeaders.AUTHORIZATION,
            HttpHeaders.CONTENT_TYPE,
            HttpHeaders.ACCEPT,
            HttpHeaders.ACCEPT_LANGUAGE,
            "X-Requested-With",
            RequestIds.HEADER));
    cors.setExposedHeaders(List.of(RequestIds.HEADER, HttpHeaders.RETRY_AFTER));
    cors.setAllowCredentials(false);
    cors.setMaxAge(Duration.ofHours(1));
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/v1/**", cors);
    return source;
  }
}
