package com.vitadelta.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/** Entry point of the Vitadelta core API. */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class VitadeltaApiApplication {

  public static void main(String[] args) {
    SpringApplication.run(VitadeltaApiApplication.class, args);
  }
}
