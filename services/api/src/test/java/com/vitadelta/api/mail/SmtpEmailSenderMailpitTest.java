package com.vitadelta.api.mail;

import static com.vitadelta.api.mail.MailTestSupport.TOKEN;
import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.config.properties.MailSettingsProperties;
import com.vitadelta.api.support.TestEnvironment;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real SMTP delivery to Mailpit (the image of {@code infra/docker-compose.yml}); the delivered
 * message is read back through Mailpit's HTTP API.
 */
@Testcontainers
class SmtpEmailSenderMailpitTest {

  /** Must match the mailpit image of {@code infra/docker-compose.yml}. */
  static final String MAILPIT_IMAGE = "axllent/mailpit:v1.34.0";

  private static final int SMTP_PORT = 1025;
  private static final int HTTP_PORT = 8025;

  @Container
  static final GenericContainer<?> MAILPIT =
      new GenericContainer<>(DockerImageName.parse(MAILPIT_IMAGE))
          .withExposedPorts(SMTP_PORT, HTTP_PORT)
          .waitingFor(Wait.forHttp("/readyz").forPort(HTTP_PORT));

  private final HttpClient http = HttpClient.newHttpClient();
  private final JsonMapper json = JsonMapper.builder().build();
  private final MailTemplateRenderer renderer = MailTestSupport.renderer();

  @Test
  void deliversUtf8MultipartMessageReadableThroughMailpit() throws Exception {
    String to = "smtp.tr." + TestEnvironment.randomHex(4) + "@example.org";
    EmailMessage message =
        renderer.render(OutgoingMail.verifyEmail(to, Locale.forLanguageTag("tr"), TOKEN));

    sender().send(message);

    JsonNode stored = messageTo(to);
    assertThat(stored.path("Subject").asString())
        .isEqualTo("VitaDelta e-posta adresinizi doğrulayın");
    assertThat(stored.path("From").path("Address").asString()).isEqualTo(TestEnvironment.MAIL_FROM);
    assertThat(stored.path("To")).hasSize(1);
    assertThat(stored.path("To").get(0).path("Address").asString()).isEqualTo(to);
    assertThat(stored.path("Text").asString())
        .contains("Gizlilik bildirimi")
        .contains("yalnızca bir kez")
        .contains(TestEnvironment.WEB_BASE_URL + "/auth/verify#token=" + TOKEN);
    assertThat(stored.path("HTML").asString())
        .contains("Düğme çalışmazsa")
        .contains("href=\"" + TestEnvironment.WEB_BASE_URL + "/privacy\"");

    String raw = get("/api/v1/message/" + stored.path("ID").asString() + "/raw");
    assertThat(raw)
        .contains("multipart/")
        .containsPattern("text/plain; ?charset=UTF-8")
        .containsPattern("text/html; ?charset=UTF-8")
        .contains("Content-Language: tr");
  }

  @Test
  void dispatcherDeliversAsynchronouslyToMailpit() throws Exception {
    String to = "smtp.en." + TestEnvironment.randomHex(4) + "@example.org";
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    AuthMailDispatcher dispatcher =
        new AuthMailDispatcher(
            renderer,
            sender(),
            registry,
            new MailDispatchProperties(1, 10, Duration.ofSeconds(30)));
    try {
      assertThat(
              dispatcher.dispatch(
                  OutgoingMail.planActivated(
                      to, Locale.ENGLISH, "PRO", Instant.parse("2027-02-01T00:00:00Z"))))
          .isTrue();
    } finally {
      dispatcher.destroy();
    }

    assertThat(MailTestSupport.count(registry, AuthMailDispatcher.SENT, PlanActivatedMail.TEMPLATE))
        .isEqualTo(1);
    JsonNode stored = messageTo(to);
    assertThat(stored.path("Subject").asString()).isEqualTo("Your VitaDelta PRO plan is active");
    assertThat(stored.path("Text").asString()).contains("February 1, 2027");
  }

  private static SmtpEmailSender sender() {
    JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
    mailSender.setHost(MAILPIT.getHost());
    mailSender.setPort(MAILPIT.getMappedPort(SMTP_PORT));
    mailSender.setDefaultEncoding(StandardCharsets.UTF_8.name());
    Properties properties = new Properties();
    properties.setProperty("mail.smtp.starttls.enable", "false");
    properties.setProperty("mail.smtp.connectiontimeout", "5000");
    properties.setProperty("mail.smtp.timeout", "10000");
    mailSender.setJavaMailProperties(properties);
    return new SmtpEmailSender(mailSender, new MailSettingsProperties(TestEnvironment.MAIL_FROM));
  }

  private JsonNode messageTo(String address) throws IOException, InterruptedException {
    String query = URLEncoder.encode("to:\"" + address + "\"", StandardCharsets.UTF_8);
    JsonNode search = json.readTree(get("/api/v1/search?query=" + query));
    assertThat(search.path("messages")).hasSize(1);
    String id = search.path("messages").get(0).path("ID").asString();
    return json.readTree(get("/api/v1/message/" + id));
  }

  private String get(String path) throws IOException, InterruptedException {
    URI uri =
        URI.create("http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(HTTP_PORT) + path);
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(uri).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(response.statusCode()).isEqualTo(200);
    return response.body();
  }
}
