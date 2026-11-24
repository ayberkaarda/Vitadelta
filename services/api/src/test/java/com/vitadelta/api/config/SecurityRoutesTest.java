package com.vitadelta.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.common.problem.FieldViolation;
import com.vitadelta.api.common.problem.ProblemCode;
import com.vitadelta.api.common.problem.ProblemException;
import com.vitadelta.api.support.PostgresIntegrationTest;
import com.vitadelta.api.support.TestEnvironment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The filter chain over a running server with a bearer-token decoder (as the identity package will
 * provide it): route matrix, problem responses, headers, CORS, request ids, body limits and the
 * management port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({SecurityRoutesTest.TestDecoderConfig.class, SecurityRoutesTest.TestRoutes.class})
class SecurityRoutesTest extends PostgresIntegrationTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final SecretKey KEY =
      new SecretKeySpec(TestEnvironment.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");

  @LocalServerPort private int port;
  @LocalManagementPort private int managementPort;

  private RestTestClient client;

  @TestConfiguration(proxyBeanMethods = false)
  static class TestDecoderConfig {
    @Bean
    JwtDecoder jwtDecoder() {
      return NimbusJwtDecoder.withSecretKey(KEY).macAlgorithm(MacAlgorithm.HS256).build();
    }
  }

  /** Validated request body of the test route. */
  record Sample(@NotBlank String name, @Max(10) int count) {}

  /** Routes under public prefixes that exercise the exception handler. */
  @RestController
  static class TestRoutes {

    @GetMapping("/api/v1/products/test-problem")
    Map<String, String> problem() {
      throw ProblemException.of(ProblemCode.QUOTA_EXCEEDED, "The trial quota is used up.")
          .with("limit", 3)
          .with("used", 3)
          .withHeader("X-Test", "yes");
    }

    @GetMapping("/api/v1/products/test-validation")
    Map<String, String> validation() {
      throw ProblemException.validation(
          "The request contains invalid fields.",
          List.of(new FieldViolation("weights", "weights_all_zero", "All weights are 0.")));
    }

    @PostMapping("/api/v1/products/test-body")
    Map<String, Object> body(@Valid @RequestBody Sample sample) {
      return Map.of("name", sample.name(), "count", sample.count());
    }

    @GetMapping("/api/v1/products/test-param")
    Map<String, Object> param(@RequestParam int page) {
      return Map.of("page", page);
    }

    @GetMapping("/api/v1/products/test-crash")
    Map<String, String> crash() {
      throw new IllegalStateException("secret-internal-detail Bearer abc.def.ghi");
    }

    @GetMapping("/api/v1/products/test-method-validation")
    Map<String, Object> methodValidation(@RequestParam @Max(100) int size) {
      return Map.of("size", size);
    }

    @GetMapping("/api/v1/products/test-constraint")
    Map<String, Object> constraint() {
      try (jakarta.validation.ValidatorFactory factory =
          jakarta.validation.Validation.buildDefaultValidatorFactory()) {
        java.util.Set<jakarta.validation.ConstraintViolation<Sample>> violations =
            factory.getValidator().validate(new Sample(" ", 50));
        throw new jakarta.validation.ConstraintViolationException(violations);
      }
    }

    @GetMapping("/api/v1/products/test-denied")
    Map<String, Object> denied() {
      throw new org.springframework.security.access.AccessDeniedException("denied");
    }

    @GetMapping("/api/v1/products/test-authentication")
    Map<String, Object> authentication() {
      throw new org.springframework.security.authentication.BadCredentialsException("bad");
    }

    @GetMapping("/api/v1/products/test-header")
    Map<String, Object> header(
        @org.springframework.web.bind.annotation.RequestHeader("X-Thing") String thing) {
      return Map.of("thing", thing);
    }

    @GetMapping("/api/v1/products/test-rate-limited")
    Map<String, Object> rateLimited() {
      throw ProblemException.of(ProblemCode.RATE_LIMITED, "Too many requests.")
          .withHeader(HttpHeaders.RETRY_AFTER, "30");
    }

    @GetMapping("/api/v1/me/test-me")
    Map<String, String> me() {
      return Map.of("ok", "me");
    }

    @GetMapping("/api/v1/admin/test-admin")
    Map<String, String> admin() {
      return Map.of("ok", "admin");
    }

    @GetMapping("/api/v1/auth/test-auth")
    Map<String, String> auth() {
      return Map.of("ok", "auth");
    }
  }

  @BeforeEach
  void setUp() {
    client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  private static String token(String role, Instant expires) {
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .subject(UUID.randomUUID().toString())
            .claim("role", role)
            .claim("email_verified", true)
            .id(UUID.randomUUID().toString())
            .issuedAt(expires.minusSeconds(900))
            .expiresAt(expires)
            .build();
    return new NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableSecret<>(KEY))
        .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
        .getTokenValue();
  }

  private static String validToken(String role) {
    return token(role, Instant.now().plusSeconds(600));
  }

  private EntityExchangeResult<String> get(String path, String bearer) {
    RestTestClient.RequestHeadersSpec<?> spec = client.get().uri(path);
    if (bearer != null) {
      spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
    }
    return spec.exchange().returnResult(String.class);
  }

  private static JsonNode json(EntityExchangeResult<String> result) {
    return JSON.readTree(result.getResponseBody());
  }

  private static void assertProblem(EntityExchangeResult<String> result, int status, String type) {
    assertThat(result.getStatus().value()).isEqualTo(status);
    assertThat(result.getResponseHeaders().getContentType())
        .isNotNull()
        .satisfies(t -> assertThat(t.toString()).startsWith("application/problem+json"));
    JsonNode body = json(result);
    assertThat(body.get("type").asString()).isEqualTo(type);
    assertThat(body.get("status").asInt()).isEqualTo(status);
    assertThat(body.get("requestId").asString())
        .isEqualTo(result.getResponseHeaders().getFirst("X-Request-Id"));
  }

  // Route matrix

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/auth/unknown",
        "/api/v1/countries",
        "/api/v1/nutrients",
        "/api/v1/weights/defaults",
        "/api/v1/legal",
        "/api/v1/fx/currencies",
        "/api/v1/products",
        "/api/v1/products/123",
        "/api/v1/recommendations/query"
      })
  void publicRoutesNeedNoCredentials(String path) {
    EntityExchangeResult<String> result = get(path, null);
    assertProblem(result, 404, "about:blank");
    assertThat(json(result).get("instance").asString()).isEqualTo(path);
  }

  @Test
  void meRequiresAnAuthenticatedPrincipal() {
    EntityExchangeResult<String> anonymous = get("/api/v1/me/test-me", null);
    assertProblem(anonymous, 401, ProblemCode.TYPE_PREFIX + "unauthorized");
    assertThat(anonymous.getResponseHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
        .isEqualTo("Bearer");
    assertProblem(get("/api/v1/me", null), 401, ProblemCode.TYPE_PREFIX + "unauthorized");
    EntityExchangeResult<String> user = get("/api/v1/me/test-me", validToken("USER"));
    assertThat(user.getStatus().value()).isEqualTo(200);
    assertThat(user.getResponseBody()).contains("\"me\"");
    assertThat(get("/api/v1/me/test-me", validToken("ADMIN")).getStatus().value()).isEqualTo(200);
  }

  @Test
  void adminRequiresTheAdminRole() {
    assertProblem(
        get("/api/v1/admin/test-admin", null), 401, ProblemCode.TYPE_PREFIX + "unauthorized");
    assertProblem(
        get("/api/v1/admin/test-admin", validToken("USER")),
        403,
        ProblemCode.TYPE_PREFIX + "forbidden");
    assertThat(get("/api/v1/admin/test-admin", validToken("ADMIN")).getStatus().value())
        .isEqualTo(200);
  }

  @Test
  void invalidOrExpiredTokensAreRejected() {
    assertProblem(
        get("/api/v1/me/test-me", "not-a-jwt"), 401, ProblemCode.TYPE_PREFIX + "unauthorized");
    assertProblem(
        get("/api/v1/me/test-me", token("USER", Instant.now().minusSeconds(120))),
        401,
        ProblemCode.TYPE_PREFIX + "unauthorized");
    String foreign =
        new NimbusJwtEncoder(
                new com.nimbusds.jose.jwk.source.ImmutableSecret<>(
                    new SecretKeySpec(
                        TestEnvironment.randomHex(32).getBytes(StandardCharsets.UTF_8),
                        "HmacSHA256")))
            .encode(
                JwtEncoderParameters.from(
                    JwsHeader.with(MacAlgorithm.HS256).build(),
                    JwtClaimsSet.builder()
                        .subject("x")
                        .claim("role", "ADMIN")
                        .expiresAt(Instant.now().plusSeconds(60))
                        .build()))
            .getTokenValue();
    assertProblem(
        get("/api/v1/admin/test-admin", foreign), 401, ProblemCode.TYPE_PREFIX + "unauthorized");
  }

  @Test
  void unlistedRoutesAreDenied() {
    assertProblem(get("/api/v1/unlisted", null), 401, ProblemCode.TYPE_PREFIX + "unauthorized");
    assertProblem(
        get("/api/v1/unlisted", validToken("ADMIN")), 403, ProblemCode.TYPE_PREFIX + "forbidden");
    assertProblem(get("/actuator/health", null), 401, ProblemCode.TYPE_PREFIX + "unauthorized");
    assertProblem(get("/error", null), 401, ProblemCode.TYPE_PREFIX + "unauthorized");
  }

  // Headers

  @Test
  void apiResponsesCarryTheSecurityHeaders() {
    EntityExchangeResult<String> health = get("/api/v1/health", null);
    HttpHeaders headers = health.getResponseHeaders();
    assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(headers.getFirst("Referrer-Policy")).isEqualTo("no-referrer");
    assertThat(headers.getFirst("Content-Security-Policy"))
        .isEqualTo("default-src 'none'; frame-ancestors 'none'");
    assertThat(headers.getFirst("X-Frame-Options")).isEqualTo("DENY");
    assertThat(headers.containsHeader("Strict-Transport-Security")).isFalse();
    assertThat(headers.containsHeader(HttpHeaders.SET_COOKIE)).isFalse();
    EntityExchangeResult<String> denied = get("/api/v1/unlisted", null);
    assertThat(denied.getResponseHeaders().getFirst("Content-Security-Policy"))
        .isEqualTo("default-src 'none'; frame-ancestors 'none'");
  }

  @Test
  void authResponsesAreNotStored() {
    EntityExchangeResult<String> ok = get("/api/v1/auth/test-auth", null);
    assertThat(ok.getStatus().value()).isEqualTo(200);
    assertThat(ok.getResponseHeaders().get(HttpHeaders.CACHE_CONTROL)).containsExactly("no-store");
    assertThat(
            get("/api/v1/auth/unknown", null).getResponseHeaders().get(HttpHeaders.CACHE_CONTROL))
        .containsExactly("no-store");
  }

  // CORS

  @Test
  void corsAllowsOnlyTheWebOriginsWithoutCredentials() {
    EntityExchangeResult<String> allowed =
        client
            .method(HttpMethod.OPTIONS)
            .uri("/api/v1/products")
            .header(HttpHeaders.ORIGIN, TestEnvironment.WEB_ORIGIN)
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
            .exchange()
            .returnResult(String.class);
    assertThat(allowed.getStatus().value()).isEqualTo(200);
    assertThat(allowed.getResponseHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
        .isEqualTo(TestEnvironment.WEB_ORIGIN);
    assertThat(
            allowed
                .getResponseHeaders()
                .containsHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS))
        .isFalse();
    EntityExchangeResult<String> foreign =
        client
            .method(HttpMethod.OPTIONS)
            .uri("/api/v1/products")
            .header(HttpHeaders.ORIGIN, "https://evil.example")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
            .exchange()
            .returnResult(String.class);
    assertProblem(foreign, 403, ProblemCode.TYPE_PREFIX + "forbidden");
    assertThat(foreign.getResponseHeaders().containsHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
        .isFalse();
    EntityExchangeResult<String> simple =
        client
            .get()
            .uri("/api/v1/health")
            .header(HttpHeaders.ORIGIN, "https://evil.example")
            .exchange()
            .returnResult(String.class);
    assertProblem(simple, 403, ProblemCode.TYPE_PREFIX + "forbidden");
    assertThat(simple.getResponseHeaders().containsHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
        .isFalse();
    assertThat(simple.getResponseHeaders().getFirst("Content-Security-Policy"))
        .isEqualTo(SecurityConfig.CONTENT_SECURITY_POLICY);
    EntityExchangeResult<String> foreignMethod =
        client
            .method(HttpMethod.OPTIONS)
            .uri("/api/v1/products")
            .header(HttpHeaders.ORIGIN, TestEnvironment.WEB_ORIGIN)
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "TRACE")
            .exchange()
            .returnResult(String.class);
    assertProblem(foreignMethod, 403, ProblemCode.TYPE_PREFIX + "forbidden");
  }

  // Request id

  @Test
  void validClientRequestIdsAreEchoedAndInvalidOnesReplaced() {
    String valid = "abc-123-DEF";
    assertThat(
            client
                .get()
                .uri("/api/v1/health")
                .header("X-Request-Id", valid)
                .exchange()
                .returnResult(String.class)
                .getResponseHeaders()
                .getFirst("X-Request-Id"))
        .isEqualTo(valid);
    String replaced =
        client
            .get()
            .uri("/api/v1/unlisted")
            .header("X-Request-Id", "bad id; drop table")
            .exchange()
            .returnResult(String.class)
            .getResponseHeaders()
            .getFirst("X-Request-Id");
    assertThat(replaced).matches("^[A-Za-z0-9-]{1,64}$").isNotEqualTo("bad id; drop table");
    assertThat(get("/api/v1/health", null).getResponseHeaders().getFirst("X-Request-Id"))
        .matches("^[A-Za-z0-9-]{36}$");
  }

  // Exception handler

  @Test
  void problemExceptionsKeepCodeStatusExtensionsAndHeaders() {
    EntityExchangeResult<String> result = get("/api/v1/products/test-problem", null);
    assertProblem(result, 403, ProblemCode.TYPE_PREFIX + "quota_exceeded");
    JsonNode body = json(result);
    assertThat(body.get("title").asString()).isEqualTo("Quota exceeded");
    assertThat(body.get("detail").asString()).isEqualTo("The trial quota is used up.");
    assertThat(body.get("instance").asString()).isEqualTo("/api/v1/products/test-problem");
    assertThat(body.get("limit").asInt()).isEqualTo(3);
    assertThat(body.get("used").asInt()).isEqualTo(3);
    assertThat(result.getResponseHeaders().getFirst("X-Test")).isEqualTo("yes");
    assertThat(body.propertyNames())
        .startsWith("type", "title", "status", "detail", "instance", "requestId");
  }

  @Test
  void validationProblemsCarryFieldErrors() {
    JsonNode thrown = json(get("/api/v1/products/test-validation", null));
    assertThat(thrown.get("status").asInt()).isEqualTo(422);
    assertThat(thrown.get("errors").get(0).get("code").asString()).isEqualTo("weights_all_zero");

    EntityExchangeResult<String> invalidBody =
        client
            .post()
            .uri("/api/v1/products/test-body")
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"name\": \"\", \"count\": 11}")
            .exchange()
            .returnResult(String.class);
    assertProblem(invalidBody, 422, ProblemCode.TYPE_PREFIX + "validation_failed");
    JsonNode errors = json(invalidBody).get("errors");
    assertThat(errors.size()).isEqualTo(2);
    assertThat(errors.toString())
        .contains("\"field\":\"name\"", "\"code\":\"required\"")
        .contains("\"field\":\"count\"", "\"code\":\"out_of_range\"");

    EntityExchangeResult<String> malformed =
        client
            .post()
            .uri("/api/v1/products/test-body")
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"name\": ")
            .exchange()
            .returnResult(String.class);
    assertProblem(malformed, 422, ProblemCode.TYPE_PREFIX + "validation_failed");
    assertThat(json(malformed).get("errors").get(0).get("code").asString())
        .isEqualTo("invalid_format");

    assertThat(
            json(get("/api/v1/products/test-param", null))
                .get("errors")
                .get(0)
                .get("code")
                .asString())
        .isEqualTo("required");
    assertThat(
            json(get("/api/v1/products/test-param?page=x", null))
                .get("errors")
                .get(0)
                .get("code")
                .asString())
        .isEqualTo("invalid_format");
  }

  @Test
  void otherValidationAndSecurityExceptionsAreMapped() {
    JsonNode method = json(get("/api/v1/products/test-method-validation?size=500", null));
    assertThat(method.get("status").asInt()).isEqualTo(422);
    assertThat(method.get("errors").get(0).get("field").asString()).isEqualTo("size");
    assertThat(method.get("errors").get(0).get("code").asString()).isEqualTo("out_of_range");

    JsonNode constraint = json(get("/api/v1/products/test-constraint", null));
    assertThat(constraint.get("status").asInt()).isEqualTo(422);
    assertThat(constraint.get("errors").toString())
        .contains("\"field\":\"name\"", "\"code\":\"required\"")
        .contains("\"field\":\"count\"", "\"code\":\"out_of_range\"");

    JsonNode header = json(get("/api/v1/products/test-header", null));
    assertThat(header.get("status").asInt()).isEqualTo(422);
    assertThat(header.get("errors").get(0).get("code").asString()).isEqualTo("required");

    assertProblem(
        get("/api/v1/products/test-denied", null), 401, ProblemCode.TYPE_PREFIX + "unauthorized");
    assertProblem(
        get("/api/v1/products/test-denied", validToken("USER")),
        403,
        ProblemCode.TYPE_PREFIX + "forbidden");
    assertProblem(
        get("/api/v1/products/test-authentication", null),
        401,
        ProblemCode.TYPE_PREFIX + "unauthorized");
  }

  @Test
  void unsupportedMethodsAndMediaTypesAreGenericProblems() {
    EntityExchangeResult<String> method =
        client.delete().uri("/api/v1/products/test-problem").exchange().returnResult(String.class);
    assertProblem(method, 405, "about:blank");
    EntityExchangeResult<String> media =
        client
            .post()
            .uri("/api/v1/products/test-body")
            .contentType(MediaType.TEXT_PLAIN)
            .body("x")
            .exchange()
            .returnResult(String.class);
    assertProblem(media, 415, "about:blank");
  }

  @Test
  void unexpectedErrorsRevealNothing() {
    EntityExchangeResult<String> result = get("/api/v1/products/test-crash", null);
    assertProblem(result, 500, ProblemCode.TYPE_PREFIX + "internal_error");
    assertThat(json(result).get("detail").asString()).isEqualTo("An unexpected error occurred.");
    assertThat(result.getResponseBody())
        .doesNotContain("secret-internal-detail")
        .doesNotContain("IllegalStateException")
        .doesNotContain("abc.def.ghi")
        .doesNotContain("at com.");
  }

  @Test
  void declaredBodiesOverTheLimitAreRejectedBeforeReading() {
    byte[] large = new byte[64 * 1024 + 1];
    java.util.Arrays.fill(large, (byte) 'a');
    EntityExchangeResult<String> result =
        client
            .post()
            .uri("/api/v1/recommendations/query")
            .contentType(MediaType.APPLICATION_JSON)
            .body(large)
            .exchange()
            .returnResult(String.class);
    assertProblem(result, 413, ProblemCode.TYPE_PREFIX + "validation_failed");
  }

  // Security headers on early rejections (section 15.2)

  private java.net.http.HttpResponse<String> raw(
      String method, String path, java.net.http.HttpRequest.BodyPublisher body, String... headers)
      throws Exception {
    java.net.http.HttpRequest.Builder request =
        java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + path))
            .method(method, body);
    if (headers.length > 0) {
      request.headers(headers);
    }
    try (java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient()) {
      return http.send(
          request.build(),
          java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
  }

  private static void assertSecurityHeaders(
      java.net.http.HttpResponse<String> response, int status) {
    String what =
        response.request().method() + " " + response.uri() + " -> " + response.statusCode();
    assertThat(response.statusCode()).as(what).isEqualTo(status);
    java.net.http.HttpHeaders headers = response.headers();
    assertThat(headers.firstValue("X-Content-Type-Options")).as(what).hasValue("nosniff");
    assertThat(headers.firstValue("Referrer-Policy")).as(what).hasValue("no-referrer");
    assertThat(headers.firstValue("Content-Security-Policy"))
        .as(what)
        .hasValue(SecurityConfig.CONTENT_SECURITY_POLICY);
    assertThat(headers.firstValue("X-Frame-Options")).as(what).hasValue("DENY");
    assertThat(headers.firstValue("X-Request-Id")).as(what).isPresent();
    assertThat(headers.firstValue(HttpHeaders.CONTENT_TYPE))
        .as(what)
        .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
    if (response.uri().getPath().startsWith("/api/v1/auth/")) {
      assertThat(headers.allValues(HttpHeaders.CACHE_CONTROL)).as(what).containsExactly("no-store");
    }
  }

  /** A well-formed JSON object over the 64 kB limit, so only the size can reject it. */
  private static byte[] oversizedJson() {
    return ("{\"name\":\"" + "a".repeat(64 * 1024) + "\",\"count\":1}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static java.net.http.HttpRequest.BodyPublisher oversized() {
    return java.net.http.HttpRequest.BodyPublishers.ofByteArray(oversizedJson());
  }

  private static java.net.http.HttpRequest.BodyPublisher oversizedChunked() {
    byte[] large = oversizedJson();
    return java.net.http.HttpRequest.BodyPublishers.ofInputStream(
        () -> new java.io.ByteArrayInputStream(large));
  }

  @Test
  void everyEarlyRejectionCarriesTheApiSecurityHeaders() throws Exception {
    java.net.http.HttpRequest.BodyPublisher none =
        java.net.http.HttpRequest.BodyPublishers.noBody();
    String json = "Content-Type";
    assertSecurityHeaders(
        raw("POST", "/api/v1/recommendations/query", oversized(), json, "application/json"), 413);
    assertSecurityHeaders(
        raw("POST", "/api/v1/auth/login", oversized(), json, "application/json"), 413);
    assertSecurityHeaders(
        raw("POST", "/api/v1/products/test-body", oversizedChunked(), json, "application/json"),
        413);
    assertSecurityHeaders(
        raw("POST", "/api/v1/auth/login", oversizedChunked(), json, "application/json"), 413);
    assertSecurityHeaders(raw("GET", "/api/v1/me", none), 401);
    assertSecurityHeaders(
        raw(
            "GET",
            "/api/v1/admin/test-admin",
            none,
            "Authorization",
            "Bearer " + validToken("USER")),
        403);
    assertSecurityHeaders(raw("GET", "/api/v1/products/unknown-route", none), 404);
    assertSecurityHeaders(raw("DELETE", "/api/v1/products/test-problem", none), 405);
    assertSecurityHeaders(
        raw(
            "POST",
            "/api/v1/products/test-body",
            java.net.http.HttpRequest.BodyPublishers.ofString("x"),
            json,
            "text/plain"),
        415);
    java.net.http.HttpResponse<String> limited =
        raw("GET", "/api/v1/products/test-rate-limited", none);
    assertSecurityHeaders(limited, 429);
    assertThat(limited.headers().firstValue(HttpHeaders.RETRY_AFTER)).hasValue("30");
    assertSecurityHeaders(
        raw(
            "OPTIONS",
            "/api/v1/products",
            none,
            "Origin",
            "https://evil.example",
            "Access-Control-Request-Method",
            "GET"),
        403);
    assertSecurityHeaders(
        raw("GET", "/api/v1/auth/unknown", none, "Origin", "https://evil.example"), 403);
  }

  // Management port

  @Test
  void actuatorExposesOnlyHealthAndMetricsOnTheManagementPort() {
    RestTestClient management =
        RestTestClient.bindToServer().baseUrl("http://localhost:" + managementPort).build();
    EntityExchangeResult<String> health =
        management.get().uri("/actuator/health").exchange().returnResult(String.class);
    assertThat(health.getStatus().value()).isEqualTo(200);
    assertThat(json(health).propertyNames()).containsExactly("status");
    assertThat(json(health).get("status").asString()).isEqualTo("UP");
    assertThat(
            management
                .get()
                .uri("/actuator/metrics/jvm.memory.used")
                .exchange()
                .returnResult(String.class)
                .getStatus()
                .value())
        .isEqualTo(200);
    for (String path : List.of("/actuator/env", "/actuator/heapdump", "/actuator/configprops")) {
      assertThat(
              management.get().uri(path).exchange().returnResult(String.class).getStatus().value())
          .as(path)
          .isIn(401, 404);
    }
  }
}
