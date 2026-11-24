package com.vitadelta.api.common.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitadelta.api.common.web.RequestIds;
import jakarta.servlet.RequestDispatcher;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;

class ProblemsTest {

  private static MockHttpServletRequest request(String uri) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
    request.setAttribute(RequestIds.KEY, "rid-7");
    return request;
  }

  @Test
  void everySpecifiedCodeHasItsTypeUri() {
    assertThat(ProblemCode.values()).hasSize(24);
    for (ProblemCode code : ProblemCode.values()) {
      assertThat(code.type().toString()).isEqualTo("https://vitadelta.dev/problems/" + code.code());
      assertThat(code.code()).matches("^[a-z_]+$");
      assertThat(code.title()).isNotBlank();
      assertThat(code.defaultStatus().value()).isBetween(400, 599);
    }
  }

  @Test
  void bodiesHaveTheContractShapeAndOrder() {
    ProblemException exception =
        ProblemException.of(ProblemCode.ENTITLEMENT_EXCEEDED, "Limit reached.")
            .with("limit", 5)
            .with("used", 5);
    ProblemDetail problem = Problems.of(exception, request("/api/v1/me/saved-products?x=1"));
    Map<String, Object> body = ProblemBodies.toBody(problem);
    assertThat(body.keySet())
        .containsExactly(
            "type", "title", "status", "detail", "instance", "requestId", "limit", "used");
    assertThat(body.get("instance")).isEqualTo("/api/v1/me/saved-products");
    assertThat(body.get("requestId")).isEqualTo("rid-7");
    assertThat(body.get("status")).isEqualTo(403);

    ProblemDetail validation =
        Problems.validation(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "Invalid.",
            List.of(new FieldViolation("a", "required", "x")),
            null);
    Map<String, Object> validationBody = ProblemBodies.toBody(validation);
    assertThat(validationBody.keySet())
        .containsExactly("type", "title", "status", "detail", "requestId", "errors");
    assertThat(validationBody.get("requestId").toString()).matches(RequestIds.FORMAT);
  }

  @Test
  void extensionMembersKeepTheirInsertionOrder() {
    List<String> names =
        List.of("used", "limit", "action", "upgradeHint", "plan", "window", "z", "a");
    ProblemException exception = ProblemException.of(ProblemCode.QUOTA_EXCEEDED, "Used up.");
    for (String name : names) {
      exception.with(name, name.length());
    }
    assertThat(exception.properties().keySet()).containsExactlyElementsOf(names);
    assertThat(ProblemBodies.toBody(Problems.of(exception, request("/x"))).keySet())
        .endsWith(names.toArray(String[]::new));
    assertThatThrownBy(() -> exception.properties().put("other", 1))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectedCorsRequestsGetTheForbiddenProblem() throws Exception {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(List.of("https://web.example"));
    configuration.setAllowedMethods(List.of("GET"));

    MockHttpServletRequest foreign = request("/api/v1/products");
    foreign.addHeader("Origin", "https://evil.example");
    MockHttpServletResponse rejected = new MockHttpServletResponse();
    assertThat(
            ProblemSecurityHandlers.corsProcessor()
                .processRequest(configuration, foreign, rejected))
        .isFalse();
    assertThat(rejected.getStatus()).isEqualTo(403);
    assertThat(rejected.getContentType()).startsWith(Problems.MEDIA_TYPE);
    assertThat(rejected.getContentAsString())
        .contains("\"type\":\"https://vitadelta.dev/problems/forbidden\"")
        .contains("rid-7")
        .doesNotContain("Invalid CORS request");

    MockHttpServletRequest allowed = request("/api/v1/products");
    allowed.addHeader("Origin", "https://web.example");
    MockHttpServletResponse accepted = new MockHttpServletResponse();
    assertThat(
            ProblemSecurityHandlers.corsProcessor()
                .processRequest(configuration, allowed, accepted))
        .isTrue();
    assertThat(accepted.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://web.example");

    MockHttpServletResponse committed = new MockHttpServletResponse();
    committed.setCommitted(true);
    assertThat(
            ProblemSecurityHandlers.corsProcessor()
                .processRequest(configuration, foreign, committed))
        .isFalse();
    assertThat(committed.getContentAsString()).isEmpty();
  }

  @Test
  void reservedMembersAndBadFieldCodesAreRejected() {
    ProblemException exception = ProblemException.of(ProblemCode.FORBIDDEN, "x");
    assertThatThrownBy(() -> exception.with("status", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FieldViolation("f", "Bad-Code", "m"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FieldViolation("f", null, "m"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(exception.getMessage()).isEqualTo("forbidden: x");
  }

  @ParameterizedTest
  @CsvSource({
    "NotNull, required",
    "NotBlank, required",
    "Size, out_of_range",
    "Pattern, invalid_format",
    "typeMismatch, invalid_format",
    "AssertTrue, assert_true",
    "WeightsAllZero, weights_all_zero",
    "'', invalid",
    "123, invalid"
  })
  void constraintCodesBecomeFieldErrorCodes(String constraint, String expected) {
    assertThat(GlobalExceptionHandler.codeFor(constraint)).isEqualTo(expected);
    assertThat(GlobalExceptionHandler.fieldPath(" ")).isEqualTo("request");
  }

  @ParameterizedTest
  @CsvSource({
    "500, https://vitadelta.dev/problems/internal_error",
    "503, https://vitadelta.dev/problems/internal_error",
    "413, https://vitadelta.dev/problems/validation_failed",
    "404, about:blank",
    "0, https://vitadelta.dev/problems/internal_error"
  })
  void errorDispatchesBecomeProblems(int status, String type) {
    MockHttpServletRequest request = request("/error");
    if (status > 0) {
      request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
    }
    request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/v1/original");
    request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("hidden"));
    ResponseEntity<Object> response = new ProblemErrorController().error(request);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body.get("type")).isEqualTo(type);
    assertThat(body.get("instance")).isEqualTo("/api/v1/original");
    assertThat(body.toString()).doesNotContain("hidden");
  }

  @Test
  void securityHandlersWriteProblems() throws Exception {
    MockHttpServletResponse unauthorized = new MockHttpServletResponse();
    ProblemSecurityHandlers.authenticationEntryPoint()
        .commence(request("/api/v1/me"), unauthorized, null);
    assertThat(unauthorized.getStatus()).isEqualTo(401);
    assertThat(unauthorized.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
    assertThat(unauthorized.getContentAsString())
        .contains("\"type\":\"https://vitadelta.dev/problems/unauthorized\"");

    MockHttpServletResponse forbidden = new MockHttpServletResponse();
    ProblemSecurityHandlers.accessDeniedHandler()
        .handle(request("/api/v1/admin/x"), forbidden, null);
    assertThat(forbidden.getStatus()).isEqualTo(403);
    assertThat(forbidden.getContentAsString()).contains("forbidden").contains("rid-7");

    MockHttpServletResponse committed = new MockHttpServletResponse();
    committed.setCommitted(true);
    ProblemSecurityHandlers.authenticationEntryPoint().commence(request("/x"), committed, null);
    ProblemSecurityHandlers.accessDeniedHandler().handle(request("/x"), committed, null);
    assertThat(committed.getContentAsString()).isEmpty();
  }
}
