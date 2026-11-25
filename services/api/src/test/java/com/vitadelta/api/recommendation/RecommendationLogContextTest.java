package com.vitadelta.api.recommendation;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.identity.Role;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Structured log fields of section 11.2: a log line written while a recommendation request is
 * served is one JSON object carrying the request id of the response, the principal type, the
 * country and the catalog version, and nothing that identifies the user.
 */
@ExtendWith(OutputCaptureExtension.class)
class RecommendationLogContextTest extends RecommendationApiTestBase {

  private static final JsonMapper LOG_JSON = JsonMapper.builder().build();

  @AfterEach
  void closeTheBreakerAgain() {
    circuitBreakers.circuitBreaker(ScoringGateway.INSTANCE).reset();
  }

  private static List<JsonNode> jsonLines(String output, String messagePart) {
    List<JsonNode> lines = new ArrayList<>();
    for (String line : output.split("\\R")) {
      String trimmed = line.trim();
      if (trimmed.startsWith("{") && trimmed.contains(messagePart)) {
        lines.add(LOG_JSON.readTree(trimmed));
      }
    }
    return lines;
  }

  @Test
  void aLogLineOfARequestCarriesTheRequestContext(CapturedOutput output) {
    scoring()
        .stubFor(
            post(urlEqualTo("/internal/v1/score"))
                .willReturn(
                    aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"synthetic outage\"}")));
    TestUser user = user(Role.USER);

    EntityExchangeResult<String> result =
        query("{\"country\":\"TR\",\"page\":1,\"pageSize\":7}", user.token(), null);

    assertStatus(result, 503);
    String requestId = result.getResponseHeaders().getFirst("X-Request-Id");
    List<JsonNode> lines = jsonLines(output.getAll(), "Scoring score answered 500");
    assertThat(lines).as(output.getAll()).isNotEmpty();
    JsonNode line = lines.getLast();
    assertThat(line.get("requestId").asString()).isEqualTo(requestId);
    assertThat(line.get("principalType").asString()).isEqualTo("USER");
    assertThat(line.get("country").asString()).isEqualTo("TR");
    assertThat(line.get("catalogVersion").asString()).isEqualTo(Long.toString(catalogVersion()));
    assertThat(line.get("level").asString()).isEqualTo("WARN");
    assertThat(line.has("@timestamp")).isTrue();
    assertThat(line.has("logger_name")).isTrue();
    assertThat(line.toString()).doesNotContain(user.id().toString()).doesNotContain(user.token());
  }
}
