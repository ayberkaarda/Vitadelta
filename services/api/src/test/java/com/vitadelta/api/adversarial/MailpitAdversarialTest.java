package com.vitadelta.api.adversarial;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.adversarial.AttackClient.Resp;
import com.vitadelta.api.support.PostgresIntegrationTest;
import com.vitadelta.api.support.TestData;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;

/**
 * Section 15.7 cases 6 (recipient limit, observed in Mailpit) and 14 (e-mail tokens never appear in
 * the API log) over real SMTP to the Mailpit image of {@code infra/docker-compose.yml}.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "vitadelta.proxy.trust-proxy=true",
      "vitadelta.proxy.trusted-proxies=127.0.0.1/32,::1/128"
    })
@ExtendWith(OutputCaptureExtension.class)
class MailpitAdversarialTest extends PostgresIntegrationTest {

  static final String MAILPIT_IMAGE = "axllent/mailpit:v1.34.0";
  static final String PASSWORD = "plum-orbit-lantern-quartz";

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
        () -> TestData.dataDir().resolve("reference/weak_passwords.txt").toString());
  }

  @LocalServerPort int port;

  private final HttpClient mailpitClient = HttpClient.newHttpClient();

  private static String randomIp() {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    return "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(1, 255);
  }

  private static String uniqueEmail() {
    return "mp." + UUID.randomUUID().toString().substring(0, 12) + "@example.org";
  }

  private Resp post(String path, Map<String, String> body) {
    return new AttackClient(port).req("POST", path).ip(randomIp()).json(body).send();
  }

  private JsonNode mailpit(String path) {
    try {
      HttpResponse<String> response =
          mailpitClient.send(
              HttpRequest.newBuilder(
                      URI.create(
                          "http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025) + path))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return AttackClient.JSON.readTree(response.body());
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** Full messages (text bodies) delivered to {@code to}, oldest first. */
  private List<String> texts(String to) {
    JsonNode list =
        mailpit(
            "/api/v1/search?query="
                + URLEncoder.encode("to:\"" + to + "\"", StandardCharsets.UTF_8));
    List<String> texts = new ArrayList<>();
    for (JsonNode summary : list.path("messages")) {
      texts.add(
          0, mailpit("/api/v1/message/" + summary.path("ID").asString()).path("Text").asString());
    }
    return texts;
  }

  private List<String> awaitTexts(String to, int count) {
    Awaitility.await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(250))
        .until(() -> texts(to).size() >= count);
    return texts(to);
  }

  private static boolean isReset(String text) {
    return text.contains("/auth/reset#token=");
  }

  @Test
  void repeatedForgotPasswordDeliversAtMostOneMailPerTenMinutes() {
    String email = uniqueEmail();
    assertThat(post("/api/v1/auth/register", Map.of("email", email, "password", PASSWORD)).status())
        .isEqualTo(202);
    awaitTexts(email, 1);
    for (int i = 0; i < 6; i++) {
      assertThat(post("/api/v1/auth/forgot-password", Map.of("email", email)).status())
          .isEqualTo(202);
    }
    Awaitility.await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(250))
        .until(() -> texts(email).stream().anyMatch(MailpitAdversarialTest::isReset));
    Awaitility.await()
        .during(Duration.ofSeconds(4))
        .atMost(Duration.ofSeconds(10))
        .until(() -> texts(email).stream().filter(MailpitAdversarialTest::isReset).count() == 1);
    assertThat(texts(email).stream().filter(MailpitAdversarialTest::isReset).count()).isEqualTo(1);
  }

  @Test
  void eMailTokensPasswordsAndSessionsNeverReachTheLog(CapturedOutput output) {
    String email = uniqueEmail();
    assertThat(post("/api/v1/auth/register", Map.of("email", email, "password", PASSWORD)).status())
        .isEqualTo(202);
    String verifyToken = RecordingMailSender.token(awaitTexts(email, 1).get(0));
    assertThat(post("/api/v1/auth/verify-email", Map.of("token", verifyToken)).status())
        .isEqualTo(204);
    assertThat(post("/api/v1/auth/verify-email", Map.of("token", verifyToken)).status())
        .isEqualTo(400);

    assertThat(post("/api/v1/auth/forgot-password", Map.of("email", email)).status())
        .isEqualTo(202);
    String resetToken =
        RecordingMailSender.token(
            awaitTexts(email, 2).stream()
                .filter(MailpitAdversarialTest::isReset)
                .findFirst()
                .orElseThrow());
    String newPassword = "violet-harbor-compass-7";
    assertThat(
            post(
                    "/api/v1/auth/reset-password",
                    Map.of("token", resetToken, "newPassword", newPassword))
                .status())
        .isEqualTo(204);
    assertThat(
            post(
                    "/api/v1/auth/reset-password",
                    Map.of("token", resetToken, "newPassword", "another-strong-passphrase"))
                .status())
        .isEqualTo(400);

    Resp login = post("/api/v1/auth/login", Map.of("email", email, "password", newPassword));
    assertThat(login.status()).isEqualTo(200);
    String refresh = login.cookieValue("vd_refresh");
    String access = login.json().get("accessToken").asString();

    String logs = output.getAll();
    assertThat(logs)
        .doesNotContain(verifyToken)
        .doesNotContain(resetToken)
        .doesNotContain(PASSWORD)
        .doesNotContain(newPassword)
        .doesNotContain(refresh)
        .doesNotContain(access)
        .doesNotContain(email);
  }
}
