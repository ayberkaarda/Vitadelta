package com.vitadelta.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

/**
 * Register, resend-verification and forgot-password answer with identical status, headers and body
 * for known and unknown e-mail addresses, with SMTP up and with SMTP down (section 8.2).
 */
class EnumerationSafetyIntegrationTest extends IdentityIntegrationTest {

  private static final String REQUEST_ID = "enumeration-check";

  private record Response(int status, Map<String, List<String>> headers, String body) {}

  private Response call(String path, String json) {
    ip = randomIp();
    EntityExchangeResult<String> result = post(path, json, "X-Request-Id", REQUEST_ID);
    Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    result
        .getResponseHeaders()
        .forEach((name, values) -> {
          if (!name.equalsIgnoreCase(HttpHeaders.DATE)) {
            headers.put(name, values);
          }
        });
    return new Response(result.getStatus().value(), headers, result.getResponseBody());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void registerResendAndForgotDoNotRevealWhetherAnAccountExists(boolean smtpDown) {
    String known = uniqueEmail();
    registerVerified(known);
    String unverified = uniqueEmail();
    register(unverified, PASSWORD);
    mail().failing(smtpDown);

    Response registerKnown =
        call("/api/v1/auth/register", body(Map.of("email", known, "password", PASSWORD)));
    Response registerUnknown =
        call("/api/v1/auth/register", body(Map.of("email", uniqueEmail(), "password", PASSWORD)));
    assertThat(registerKnown).isEqualTo(registerUnknown);
    assertThat(registerKnown.status()).isEqualTo(202);
    assertThat(registerKnown.headers().get(HttpHeaders.SET_COOKIE)).hasSize(1);
    ContractSchemas.assertConforms("AcceptedMessage", registerKnown.body());

    for (String path :
        List.of("/api/v1/auth/resend-verification", "/api/v1/auth/forgot-password")) {
      Response knownResponse = call(path, body(Map.of("email", known)));
      Response unverifiedResponse = call(path, body(Map.of("email", unverified)));
      Response unknownResponse = call(path, body(Map.of("email", uniqueEmail())));
      assertThat(knownResponse).as(path).isEqualTo(unknownResponse);
      assertThat(unverifiedResponse).as(path).isEqualTo(unknownResponse);
      assertThat(knownResponse.status()).isEqualTo(202);
      ContractSchemas.assertConforms("AcceptedMessage", knownResponse.body());
    }

    mail().awaitMessages(known, 3);
    assertThat(mail().to(known))
        .extracting(message -> message.subject())
        .hasSize(3)
        .doesNotHaveDuplicates();
  }
}
