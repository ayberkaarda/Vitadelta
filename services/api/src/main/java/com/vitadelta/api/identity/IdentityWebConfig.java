package com.vitadelta.api.identity;

import com.vitadelta.api.config.properties.WebProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the {@link AuthRequestGuard} for every handler (it acts on {@link CookieScoped} ones).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class IdentityWebConfig implements WebMvcConfigurer {

  private final WebProperties web;

  public IdentityWebConfig(WebProperties web) {
    this.web = web;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new AuthRequestGuard(web));
  }
}
