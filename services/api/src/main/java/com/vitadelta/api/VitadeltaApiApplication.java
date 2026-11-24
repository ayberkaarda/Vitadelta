package com.vitadelta.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;

/** Entry point of the Vitadelta core API. */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class VitadeltaApiApplication {

  /** Profile of the one-shot seed run (see {@code scripts/seed.sh}). */
  public static final String SEED_PROFILE = "seed";

  public static void main(String[] args) {
    ConfigurableApplicationContext context =
        SpringApplication.run(VitadeltaApiApplication.class, args);
    if (context.getEnvironment().matchesProfiles(SEED_PROFILE)) {
      System.exit(SpringApplication.exit(context));
    }
  }
}
