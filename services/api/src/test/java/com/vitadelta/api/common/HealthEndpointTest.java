package com.vitadelta.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthEndpointTest extends PostgresIntegrationTest {

  @LocalServerPort private int port;

  private RestTestClient client;

  @BeforeEach
  void setUp() {
    client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  @Test
  void healthReturnsOnlyTheOverallStatus() {
    // No scoring service and no stored FX rate in this context: degraded, still 200.
    client
        .get()
        .uri("/api/v1/health")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
        .expectBody(String.class)
        .isEqualTo("{\"status\":\"DEGRADED\"}");
  }

  @Test
  void healthDoesNotIssueSessionCookie() {
    client
        .get()
        .uri("/api/v1/health")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .doesNotExist(HttpHeaders.SET_COOKIE);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"/", "/api/v1/unknown", "/api/v1/health/extra", "/actuator/health", "/error"})
  void otherPathsAreDenied(String path) {
    client
        .get()
        .uri(path)
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED)
        .expectHeader()
        .doesNotExist(HttpHeaders.SET_COOKIE);
  }

  @ParameterizedTest
  @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
  void nonGetMethodsOnHealthAreDenied(String method) {
    client
        .method(HttpMethod.valueOf(method))
        .uri("/api/v1/health")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED)
        .expectHeader()
        .doesNotExist(HttpHeaders.SET_COOKIE);
  }

  @Test
  void noGeneratedDefaultPasswordIsLogged(CapturedOutput output) {
    assertThat(output.getAll()).doesNotContain("generated security password");
  }
}
