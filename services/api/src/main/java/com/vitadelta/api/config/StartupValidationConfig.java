package com.vitadelta.api.config;

import com.vitadelta.api.config.properties.MailSettingsProperties;
import com.vitadelta.api.config.properties.OperationsProperties;
import com.vitadelta.api.config.properties.ScoringProperties;
import com.vitadelta.api.config.properties.SecretsProperties;
import com.vitadelta.api.config.properties.WebProperties;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Validates the required configuration before the database is contacted.
 *
 * <p>The configuration records validate themselves when they are bound. Without an explicit order,
 * the Flyway migration (which opens the first database connection) may run before them, and an
 * unreachable database would then hide the message that names the missing variable. Every {@link
 * DataSource} bean therefore depends on the beans of {@link #VALIDATED_FIRST}, so a missing or
 * invalid {@code VITADELTA_JWT_SECRET}, {@code VITADELTA_COOKIE_SECRET}, {@code
 * VITADELTA_WEB_BASE_URL}, {@code VITADELTA_MAIL_FROM}, {@code VITADELTA_PRIVACY_CONTACT} or {@code
 * VITADELTA_SCORING_BASE_URL} always stops the startup first.
 */
@Configuration(proxyBeanMethods = false)
public class StartupValidationConfig {

  /** Configuration records that are bound and validated before any data source exists. */
  static final List<Class<?>> VALIDATED_FIRST =
      List.of(
          SecretsProperties.class,
          WebProperties.class,
          MailSettingsProperties.class,
          OperationsProperties.class,
          ScoringProperties.class);

  @Bean
  static BeanFactoryPostProcessor validateConfigurationBeforeDataSource() {
    return StartupValidationConfig::addDependencies;
  }

  static void addDependencies(ConfigurableListableBeanFactory beanFactory) {
    List<String> first = new ArrayList<>();
    for (Class<?> type : VALIDATED_FIRST) {
      first.addAll(Arrays.asList(beanFactory.getBeanNamesForType(type, true, false)));
    }
    for (String name : beanFactory.getBeanNamesForType(DataSource.class, true, false)) {
      if (!beanFactory.containsBeanDefinition(name)) {
        continue;
      }
      BeanDefinition definition = beanFactory.getBeanDefinition(name);
      Set<String> dependsOn = new LinkedHashSet<>();
      if (definition.getDependsOn() != null) {
        dependsOn.addAll(Arrays.asList(definition.getDependsOn()));
      }
      dependsOn.addAll(first);
      definition.setDependsOn(dependsOn.toArray(String[]::new));
    }
  }
}
