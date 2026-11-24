package com.vitadelta.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.support.PostgresIntegrationTest;
import com.vitadelta.api.support.TestData;
import com.vitadelta.api.support.TestEnvironment;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End to end over real SMTP: registration and password-reset e-mails are delivered to Mailpit (the
 * image of {@code infra/docker-compose.yml}), the token is taken from the delivered link fragment
 * and completes verification and reset.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MailpitAuthDeliveryTest extends PostgresIntegrationTest {

  static final String MAILPIT_IMAGE = "axllent/mailpit:v1.34.0";
  private static final Pattern TOKEN = Pattern.compile("#token=([A-Za-z0-9_-]{43})");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  static final GenericContainer<?> MAILPIT =
      new GenericContainer<>(DockerImageName.parse(MAILPIT_IMAGE))
          .withExposedPorts(1025, 8025)
          .waitingFor(Wait.forHttp("/readyz").forPort(8025));

  static {
    MAILPIT.start();
  }

  @DynamicPropertySource
  static void mailProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.mail.host", MAILPIT::getHost);
    registry.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
    registry.add(
        "vitadelta.identity.weak-passwords-file",
        () -> TestData.dataDir().resolve(PasswordPolicy.LIST_PATH).toString());
  }

  @LocalServerPort int port;

  private final HttpClient http = HttpClient.newHttpClient();

  private RestTestClient client() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  private EntityExchangeResult<String> post(String path, Map<String, String> body) {
    return client()
        .post()
        .uri(path)
        .contentType(MediaType.APPLICATION_JSON)
        .body(JSON.writeValueAsString(body))
        .exchange()
        .returnResult(String.class);
  }

  private JsonNode mailpit(String path) throws Exception {
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025) + path))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    return JSON.readTree(response.body());
  }

  private JsonNode awaitMessage(String to, String subjectPart) {
    JsonNode[] found = new JsonNode[1];
    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .pollInterval(Duration.ofMillis(200))
        .until(() -> {
          JsonNode list =
              mailpit(
                  "/api/v1/search?query="
                      + URLEncoder.encode("to:\"" + to + "\"", StandardCharsets.UTF_8));
          for (JsonNode summary : list.path("messages")) {
            if (summary.path("Subject").asString().contains(subjectPart)) {
              found[0] = mailpit("/api/v1/message/" + summary.path("ID").asString());
              return true;
            }
          }
          return false;
        });
    return found[0];
  }

  private static String token(JsonNode message) {
    Matcher matcher = TOKEN.matcher(message.path("Text").asString());
    assertThat(matcher.find()).isTrue();
    return matcher.group(1);
  }

  @Test
  void verificationAndResetMailsAreDeliveredAndTheirLinksWork() {
    String email = "mailpit." + UUID.randomUUID().toString().substring(0, 8) + "@example.org";
    String password = "correct-horse-battery-staple";
    assertThat(
            post(
                    "/api/v1/auth/register",
                    Map.of("email", email, "password", password, "locale", "en"))
                .getStatus()
                .value())
        .isEqualTo(202);

    JsonNode verification = awaitMessage(email, "");
    assertThat(verification.path("From").path("Address").asString())
        .isEqualTo(TestEnvironment.MAIL_FROM);
    assertThat(verification.path("Text").asString())
        .contains(TestEnvironment.WEB_BASE_URL + "/auth/verify#token=");
    assertThat(
            post("/api/v1/auth/verify-email", Map.of("token", token(verification)))
                .getStatus()
                .value())
        .isEqualTo(204);

    String subject = verification.path("Subject").asString();
    assertThat(post("/api/v1/auth/forgot-password", Map.of("email", email)).getStatus().value())
        .isEqualTo(202);
    JsonNode reset = awaitResetMessage(email, subject);
    assertThat(reset.path("Text").asString())
        .contains(TestEnvironment.WEB_BASE_URL + "/auth/reset#token=");
    assertThat(
            post(
                    "/api/v1/auth/reset-password",
                    Map.of("token", token(reset), "newPassword", "a-brand-new-passphrase"))
                .getStatus()
                .value())
        .isEqualTo(204);
    assertThat(
            post("/api/v1/auth/login", Map.of("email", email, "password", "a-brand-new-passphrase"))
                .getStatus()
                .value())
        .isEqualTo(200);
  }

  private JsonNode awaitResetMessage(String to, String verificationSubject) {
    JsonNode[] found = new JsonNode[1];
    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .pollInterval(Duration.ofMillis(200))
        .until(() -> {
          JsonNode list =
              mailpit(
                  "/api/v1/search?query="
                      + URLEncoder.encode("to:\"" + to + "\"", StandardCharsets.UTF_8));
          for (JsonNode summary : list.path("messages")) {
            if (!summary.path("Subject").asString().equals(verificationSubject)) {
              found[0] = mailpit("/api/v1/message/" + summary.path("ID").asString());
              return true;
            }
          }
          return false;
        });
    return found[0];
  }
}
