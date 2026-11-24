package com.vitadelta.api.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.common.problem.GlobalExceptionHandler;
import com.vitadelta.api.common.web.RequestIds;
import com.vitadelta.api.support.PostgresIntegrationTest;
import com.vitadelta.api.support.TestEnvironment;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Log leakage guarantee (project specification section 15.4): a synthetic request carrying an
 * {@code Authorization} header, both cookies, a password and an e-mail token is driven through the
 * logging layer at every level and through the exception handler; the console output (structured
 * JSON, as in production) contains none of the values.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = "logging.level.com.vitadelta.redaction-probe=TRACE")
class LogRedactionIntegrationTest extends PostgresIntegrationTest {

  private static final Logger PROBE = LoggerFactory.getLogger("com.vitadelta.redaction-probe");

  @Autowired private GlobalExceptionHandler exceptionHandler;

  @Test
  void noSecretReachesTheLogSink(CapturedOutput output) {
    String accessToken =
        "eyJraWQiOiJrMSIsImFsZyI6IkhTMjU2In0.eyJzdWIiOiJ1c2VyLTEiLCJyb2xlIjoiVVNFUiJ9."
            + TestEnvironment.randomHex(16);
    String refresh = "refresh" + TestEnvironment.randomHex(16);
    String anon = "anon" + TestEnvironment.randomHex(16) + ".sig" + TestEnvironment.randomHex(8);
    String password = "Pw" + TestEnvironment.randomHex(12);
    String emailToken = "et" + TestEnvironment.randomHex(16);
    String email = "probe.user" + TestEnvironment.randomHex(4) + "@example.org";

    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
    request.addHeader("Authorization", "Bearer " + accessToken);
    request.setCookies(new Cookie("vd_refresh", refresh), new Cookie("vd_anon", anon));
    request.setAttribute(RequestIds.KEY, "probe-request");
    String body =
        "{\"email\":\""
            + email
            + "\",\"password\":\""
            + password
            + "\",\"token\":\""
            + emailToken
            + "\"}";
    request.setContent(body.getBytes(StandardCharsets.UTF_8));

    String headers =
        Collections.list(request.getHeaderNames()).stream()
            .map(name -> name + ": " + request.getHeader(name))
            .reduce("", (a, b) -> a + "; " + b);
    String cookies = "Cookie: vd_refresh=" + refresh + "; vd_anon=" + anon;
    MDC.put("probe", "Authorization=Bearer " + accessToken);
    try {
      PROBE.trace("request headers {}", headers);
      PROBE.debug("request cookies {}", cookies);
      PROBE.info("request body {}", body);
      PROBE.warn("verify link https://vitadelta.test/auth/verify#token={}", emailToken);
      PROBE.error(
          "failed login for {}",
          email,
          new IllegalStateException("bad credentials " + body + " " + cookies + " " + headers));
      ResponseEntity<Object> response =
          exceptionHandler.handleUnexpected(
              new RuntimeException(
                  "boom Authorization: Bearer "
                      + accessToken
                      + " password="
                      + password
                      + " token="
                      + emailToken),
              request);
      assertThat(String.valueOf(response.getBody()))
          .doesNotContain(accessToken)
          .doesNotContain(password)
          .doesNotContain("boom");
    } finally {
      MDC.remove("probe");
    }

    String all = output.getAll();
    assertThat(all)
        .contains("request headers")
        .contains("failed login")
        .contains("Unhandled exception");
    assertThat(all)
        .doesNotContain(accessToken)
        .doesNotContain(accessToken.substring(0, 40))
        .doesNotContain(refresh)
        .doesNotContain(anon)
        .doesNotContain(password)
        .doesNotContain(emailToken)
        .doesNotContain(email)
        .contains("p***@example.org")
        .contains("[REDACTED]");
  }
}
