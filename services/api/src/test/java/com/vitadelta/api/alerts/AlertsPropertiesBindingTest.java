package com.vitadelta.api.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** The documented {@code vitadelta.alerts} block of application.yml binds to the code defaults. */
class AlertsPropertiesBindingTest {

  @Test
  void applicationYamlDocumentsExactlyTheCodeDefaults() throws IOException {
    List<PropertySource<?>> sources =
        new YamlPropertySourceLoader()
            .load("application", new ClassPathResource("application.yml"));
    Binder documented = new Binder(ConfigurationPropertySources.from(sources));
    AlertsProperties fromYaml =
        documented.bind("vitadelta.alerts", Bindable.of(AlertsProperties.class)).get();

    AlertsProperties defaults =
        new Binder(ConfigurationPropertySources.from(new MapPropertySource("empty", Map.of())))
            .bindOrCreate("vitadelta.alerts", Bindable.of(AlertsProperties.class));

    assertThat(fromYaml).isEqualTo(defaults);
    assertThat(fromYaml)
        .isEqualTo(
            new AlertsProperties(
                Duration.ofSeconds(10),
                100,
                Duration.ofMinutes(2),
                5,
                Duration.ofDays(30),
                Duration.ofDays(90),
                1000));
  }

  @Test
  void everyDocumentedKeyIsAPropertyOfTheRecord() throws IOException {
    List<PropertySource<?>> sources =
        new YamlPropertySourceLoader()
            .load("application", new ClassPathResource("application.yml"));
    List<String> keys =
        sources.stream()
            .map(source -> (Map<?, ?>) source.getSource())
            .flatMap(map -> map.keySet().stream())
            .map(Object::toString)
            .filter(key -> key.startsWith("vitadelta.alerts."))
            .toList();
    assertThat(keys)
        .containsExactlyInAnyOrder(
            "vitadelta.alerts.poll-interval",
            "vitadelta.alerts.claim-batch-size",
            "vitadelta.alerts.lease",
            "vitadelta.alerts.max-attempts",
            "vitadelta.alerts.processed-retention",
            "vitadelta.alerts.dead-letter-retention",
            "vitadelta.alerts.housekeeping-batch-size");
  }
}
