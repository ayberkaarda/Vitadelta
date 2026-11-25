package com.vitadelta.api.common.problem;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.catalog.web.CatalogApiTest;
import com.vitadelta.api.identity.ContractSchemas;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

/**
 * Error leakage guarantees of section 15.4 on the running server: every error path (unknown route,
 * wrong method, wrong media type, malformed and mistyped JSON, oversized header, malformed request
 * line, an unexpected server error) answers a problem body that reveals no stack trace, class or
 * package name, SQL, library message or internal host name, and no response carries a {@code
 * Server} or framework banner header.
 */
@ExtendWith(OutputCaptureExtension.class)
class ErrorLeakageIntegrationTest extends CatalogApiTest {

  /** Markers of internal detail that must never appear in an error body. */
  private static final List<String> MARKERS =
      List.of(
          "exception",
          "java.",
          "jakarta.",
          "org.springframework",
          "com.vitadelta",
          "tools.jackson",
          "hibernate",
          "postgres",
          "jdbc",
          "hikari",
          "tomcat",
          "apache",
          "select ",
          "insert into",
          "sqlstate",
          "\tat ",
          "caused by",
          "stacktrace",
          "trace",
          "scoring:",
          "mailpit",
          "localhost",
          "127.0.0.1",
          "unexpected character",
          "cannot deserialize",
          "canonical unit",
          "leak_ref");

  private static final Pattern FRAME = Pattern.compile("\\w+\\.java:\\d+");

  @AfterEach
  void removeSyntheticCatalogRows() {
    jdbc.sql("DELETE FROM product WHERE code LIKE 'LEAK-%'").update();
    jdbc.sql("DELETE FROM country WHERE code = 'XL'").update();
    jdbc.sql("DELETE FROM reference_value WHERE reference_set_code = 'LEAK_REF'").update();
    jdbc.sql("DELETE FROM reference_set WHERE code = 'LEAK_REF'").update();
    jdbc.sql("DELETE FROM ul_set WHERE code = 'LEAK_UL'").update();
  }

  private static void assertNoLeak(String label, String body) {
    String lower = body == null ? "" : body.toLowerCase(Locale.ROOT);
    for (String marker : MARKERS) {
      assertThat(lower).as(label + " reveals " + marker + ": " + body).doesNotContain(marker);
    }
    assertThat(FRAME.matcher(body == null ? "" : body).find()).as(label + ": " + body).isFalse();
  }

  private static void assertNoBanner(String label, HttpHeaders headers) {
    for (String banner :
        List.of("Server", "X-Powered-By", "X-Application-Context", "X-AspNet-Version")) {
      assertThat(headers.get(banner)).as(label + " sends " + banner).isNull();
    }
  }

  private EntityExchangeResult<String> send(
      HttpMethod method, String path, MediaType type, String body) {
    var spec =
        client
            .method(method)
            .uri(java.net.URI.create("http://localhost:" + port + path))
            .header("X-Forwarded-For", ip);
    if (type != null) {
      spec = spec.contentType(type);
    }
    if (body != null) {
      return spec.body(body).exchange().returnResult(String.class);
    }
    return spec.exchange().returnResult(String.class);
  }

  /** A raw HTTP/1.1 exchange, for requests a client library refuses to build. */
  private String raw(String request) throws IOException {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(10_000);
      OutputStream out = socket.getOutputStream();
      out.write(request.getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
      InputStream in = socket.getInputStream();
      ByteArrayOutputStream response = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) != -1) {
        response.write(buffer, 0, read);
      }
      return response.toString(StandardCharsets.ISO_8859_1);
    }
  }

  /** A product whose stored reference value is not in the nutrient's canonical unit. */
  private UUID productWithACorruptReferenceValue() {
    jdbc.sql(
            "INSERT INTO reference_set (code, name, source_citation)"
                + " VALUES ('LEAK_REF', 'Leakage test set', 'Synthetic test data')")
        .update();
    jdbc.sql(
            "INSERT INTO ul_set (code, name, source_citation)"
                + " VALUES ('LEAK_UL', 'Leakage test limits', 'Synthetic test data')")
        .update();
    jdbc.sql(
            "INSERT INTO country VALUES ('XL', 'Leakage Testland', 'TRY', 'LEAK_REF', 'LEAK_UL',"
                + " 'en')")
        .update();
    // VIT_C is canonical in mg; a value stored in g is a data error the API must not convert.
    jdbc.sql(
            "INSERT INTO reference_value (reference_set_code, nutrient_code, amount, unit)"
                + " VALUES ('LEAK_REF', 'VIT_C', 1, 'g')")
        .update();
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO product (id, code, brand, name, dosage_form, units_per_container,"
                + " units_per_serving, servings_per_day, synthetic, created_at, updated_at)"
                + " VALUES (?, ?, 'Synthetic Labs', 'Synthetic leak probe', 'TABLET', 60, 1, 1,"
                + " true, now(), now())")
        .params(id, "LEAK-" + id.toString().substring(0, 8).toUpperCase(Locale.ROOT))
        .update();
    jdbc.sql(
            "INSERT INTO product_nutrient (product_id, nutrient_code, amount_per_serving, unit,"
                + " form_code, form_label) VALUES (?, 'VIT_C', 100, 'mg', 'UNKNOWN', 'synthetic')")
        .param(id)
        .update();
    jdbc.sql(
            "INSERT INTO product_availability (product_id, country_code, status)"
                + " VALUES (?, 'XL', 'AVAILABLE')")
        .param(id)
        .update();
    return id;
  }

  @Test
  void everyErrorPathAnswersAGenericProblemWithoutInternalDetail(CapturedOutput output) {
    String goldA = "/api/v1/products/" + productId("GOLD-A") + "?country=TR";
    List<EntityExchangeResult<String>> results = new ArrayList<>();
    results.add(send(HttpMethod.GET, "/api/v1/no-such-route", null, null));
    results.add(send(HttpMethod.GET, "/no-such-route.php", null, null));
    results.add(send(HttpMethod.DELETE, goldA, null, null));
    results.add(send(HttpMethod.PUT, "/api/v1/auth/login", MediaType.APPLICATION_JSON, "{}"));
    results.add(send(HttpMethod.POST, "/api/v1/auth/login", MediaType.APPLICATION_XML, "<login/>"));
    results.add(
        send(HttpMethod.POST, "/api/v1/auth/login", MediaType.APPLICATION_JSON, "{not json"));
    results.add(
        send(
            HttpMethod.POST,
            "/api/v1/auth/login",
            MediaType.APPLICATION_JSON,
            "{\"email\":{\"$gt\":\"\"},\"password\":[1,2]}"));
    results.add(
        send(
            HttpMethod.POST,
            "/api/v1/recommendations/query",
            MediaType.APPLICATION_JSON,
            "{\"country\":\"TR\",\"page\":\"x\"}"));
    results.add(
        send(
            HttpMethod.POST,
            "/api/v1/recommendations/compare",
            MediaType.APPLICATION_JSON,
            "[" + "[".repeat(3000) + "]".repeat(3000) + "]"));
    results.add(send(HttpMethod.GET, "/api/v1/products/not-a-uuid?country=TR", null, null));
    results.add(send(HttpMethod.GET, "/error", null, null));

    UUID corrupt = productWithACorruptReferenceValue();
    EntityExchangeResult<String> serverError =
        send(HttpMethod.GET, "/api/v1/products/" + corrupt + "?country=XL", null, null);
    assertProblem(serverError, 500, "internal_error");
    assertThat(json(serverError).get("detail").asString())
        .isEqualTo("An unexpected error occurred.");
    results.add(serverError);

    for (EntityExchangeResult<String> result : results) {
      String label = result.getMethod() + " " + result.getUrl();
      assertThat(result.getStatus().value()).as(label).isBetween(400, 599);
      assertThat(String.valueOf(result.getResponseHeaders().getContentType()))
          .as(label)
          .startsWith("application/problem+json");
      ContractSchemas.assertConforms("Problem", result.getResponseBody());
      assertNoLeak(label, result.getResponseBody());
      assertNoBanner(label, result.getResponseHeaders());
    }
    // The cause stays in the server log (with the request id), never in the answer.
    assertThat(output.getAll()).contains("canonical unit");
  }

  @Test
  void containerLevelRejectionsRevealNothing() throws IOException {
    List<String> requests =
        List.of(
            "GET /api/v1/%zz HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n",
            "GET /api/v1/health HTTP/1.1\r\nHost: localhost\r\nX-Big: "
                + "a".repeat(20_000)
                + "\r\nConnection: close\r\n\r\n",
            "BREW /api/v1/health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n",
            "GET /api/v1/health HTTP/1.1\r\nConnection: close\r\n\r\n");
    for (String request : requests) {
      String response = raw(request);
      String label = request.substring(0, Math.min(request.length(), 40)).replace("\r\n", " ");
      int split = response.indexOf("\r\n\r\n");
      String head = split < 0 ? response : response.substring(0, split);
      String body = split < 0 ? "" : response.substring(split + 4);
      assertThat(head).as(label).startsWith("HTTP/1.1 4");
      for (String line : head.split("\r\n")) {
        String name = line.contains(":") ? line.substring(0, line.indexOf(':')) : "";
        assertThat(name.toLowerCase(Locale.ROOT))
            .as(label + " header " + line)
            .isNotIn("server", "x-powered-by", "x-application-context");
      }
      assertNoLeak(label, body);
    }
  }

  @Test
  void successfulResponsesCarryNoBannerEither() {
    for (String path :
        List.of(
            "/api/v1/health",
            "/api/v1/countries",
            "/api/v1/openapi.json",
            "/api/v1/products/" + productId("GOLD-A") + "?country=TR")) {
      EntityExchangeResult<String> result = get(path, null);
      assertStatus(result, 200);
      assertNoBanner(path, result.getResponseHeaders());
    }
  }
}
