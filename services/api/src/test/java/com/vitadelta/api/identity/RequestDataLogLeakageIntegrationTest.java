package com.vitadelta.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

/**
 * Request data in framework log lines (section 15.4, "any level"): with the application, request
 * pipeline (MVC, message converters) and security loggers raised to {@code TRACE}, malformed and
 * mistyped JSON bodies carrying a password or a promo code as bare tokens, and user searches with
 * the e-mail address URL-encoded ({@code %40}, double-encoded {@code %2540}, upper case), leave no
 * password, promo code or full e-mail address in any log line. The parser excerpt of a JSON error
 * never reaches a log message, and the redactor masks encoded addresses.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(
    properties = {
      "logging.level.com.vitadelta=TRACE",
      "logging.level.org.springframework.web=TRACE",
      "logging.level.org.springframework.http=TRACE",
      "logging.level.org.springframework.security=TRACE",
      // The test's own HTTP client logs the requests it sends; that is client-side output.
      "logging.level.org.springframework.web.client=INFO",
      "logging.level.org.springframework.http.client=INFO",
      "logging.level.org.springframework.test=INFO"
    })
class RequestDataLogLeakageIntegrationTest extends IdentityIntegrationTest {

  private EntityExchangeResult<String> postRaw(String path, String json, String bearer) {
    return client
        .post()
        .uri(path)
        .contentType(MediaType.APPLICATION_JSON)
        .header("X-Forwarded-For", ip)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
        .body(json)
        .exchange()
        .returnResult(String.class);
  }

  @Test
  void malformedBodiesAndEncodedAddressesLeaveNoSecretInAnyLogLineAtTrace(CapturedOutput output) {
    List<String> secrets = new ArrayList<>();
    String email = "req.leak." + UUID.randomUUID().toString().substring(0, 8) + "@example.org";
    String bare = "bareSecret" + UUID.randomUUID().toString().replace("-", "");
    String promo = "PROMO" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
    String quoted = "quotedSecret" + UUID.randomUUID().toString().replace("-", "");
    secrets.add(email);
    secrets.add(email.toUpperCase(Locale.ROOT));
    secrets.add(URLEncoder.encode(email, StandardCharsets.UTF_8));
    secrets.add(email.replace("@", "%2540"));
    secrets.add(email.replace("@", "%40").toUpperCase(Locale.ROOT));
    secrets.add(bare);
    secrets.add(promo);
    secrets.add(quoted);
    secrets.add(PASSWORD);

    UUID userId = registerVerified(email);
    assertThat(userId).isNotNull();
    Login session = login(email, PASSWORD);

    // A bare (unquoted) token, a truncated string and mistyped members.
    for (String json :
        List.of(
            "{\"email\":\"" + email + "\",\"password\":" + bare + "}",
            "{\"email\":\"" + email + "\",\"password\":\"" + quoted,
            "{\"email\":[\"" + email + "\"],\"password\":{\"v\":\"" + quoted + "\"}}",
            "{\"email\":\"" + email + "\",\"password\":" + bare + quoted + "}")) {
      EntityExchangeResult<String> rejected = post("/api/v1/auth/login", json);
      assertThat(rejected.getStatus().value()).as(json).isBetween(400, 422);
    }
    for (String json :
        List.of(
            "{\"code\":" + promo + "}",
            "{\"code\":\"" + promo + "\",\"code\":" + promo + "}",
            "{\"code\":[" + promo + "]}")) {
      EntityExchangeResult<String> rejected =
          postRaw("/api/v1/me/subscription/promo", json, session.accessToken());
      assertThat(rejected.getStatus().value()).as(json).isBetween(400, 422);
    }

    String admin = adminToken();
    for (String query :
        List.of(
            URLEncoder.encode(email, StandardCharsets.UTF_8),
            email.replace("@", "%2540"),
            email.replace("@", "%40").toUpperCase(Locale.ROOT))) {
      EntityExchangeResult<String> search = get("/api/v1/admin/users?q=" + query, admin);
      assertThat(search.getStatus().value()).as(search.getResponseBody()).isEqualTo(200);
    }
    assertThat(
            post("/api/v1/auth/login", body(Map.of("email", email, "password", PASSWORD)))
                .getStatus()
                .value())
        .isEqualTo(200);

    String logs = output.getAll();
    assertThat(logs).as("the request pipeline was logged at trace level").contains("Securing");
    List<String> leaked = new ArrayList<>();
    for (String secret : secrets) {
      for (String line : logs.split("\\R")) {
        if (line.contains(secret)) {
          leaked.add(secret + " in: " + (line.length() > 300 ? line.substring(0, 300) : line));
          break;
        }
      }
    }
    assertThat(leaked).as("request data in the log").isEmpty();
    assertThat(logs).doesNotContain("Unrecognized token '");
  }
}
