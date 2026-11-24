package com.vitadelta.api.adversarial;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.identity.ContractSchemas;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A raw HTTP/1.1 client for attacks: the request path is sent exactly as written (no encoding or
 * normalization), headers and cookies are set verbatim, and bodies can be sent with a declared
 * length or chunked.
 */
final class AttackClient {

  static final JsonMapper JSON = JsonMapper.builder().build();

  private static final HttpClient HTTP =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .followRedirects(HttpClient.Redirect.NEVER)
          .connectTimeout(Duration.ofSeconds(10))
          .build();

  private final String base;

  AttackClient(int port) {
    this.base = "http://localhost:" + port;
  }

  /** A request builder; the path is appended to the server base verbatim. */
  Req req(String method, String path) {
    return new Req(method, path);
  }

  /** The response of one exchange. */
  record Resp(int status, HttpHeaders headers, String body, long nanos) {

    String header(String name) {
      return headers.firstValue(name).orElse(null);
    }

    List<String> setCookies() {
      return headers.allValues("set-cookie");
    }

    /** The full {@code Set-Cookie} header of cookie {@code name}, if set. */
    Optional<String> setCookie(String name) {
      return setCookies().stream().filter(h -> h.startsWith(name + "=")).findFirst();
    }

    /** The value of cookie {@code name} set by this response. */
    String cookieValue(String name) {
      String header = setCookie(name).orElseThrow(() -> new AssertionError(name + " not set"));
      String pair = header.split(";", 2)[0];
      return pair.substring(pair.indexOf('=') + 1);
    }

    JsonNode json() {
      return JSON.readTree(body);
    }

    /** Headers as a sorted map without the per-response {@code Date} header. */
    Map<String, List<String>> comparableHeaders() {
      Map<String, List<String>> map = new TreeMap<>();
      headers
          .map()
          .forEach((k, v) -> {
            if (!k.equalsIgnoreCase("date")) {
              map.put(k.toLowerCase(Locale.ROOT), v);
            }
          });
      return map;
    }

    @Override
    public String toString() {
      return status + " " + body;
    }
  }

  final class Req {
    private final String method;
    private final String path;
    private final List<String[]> headers = new ArrayList<>();
    private final List<String> cookies = new ArrayList<>();
    private byte[] body;
    private boolean chunked;

    private Req(String method, String path) {
      this.method = method;
      this.path = path;
    }

    Req header(String name, String value) {
      headers.add(new String[] {name, value});
      return this;
    }

    Req ip(String ip) {
      return ip == null ? this : header("X-Forwarded-For", ip);
    }

    Req bearer(String token) {
      return token == null ? this : header("Authorization", "Bearer " + token);
    }

    Req cookie(String name, String value) {
      cookies.add(name + "=" + value);
      return this;
    }

    Req requestId(String id) {
      return header("X-Request-Id", id);
    }

    /** A JSON body ({@code Content-Type: application/json}). */
    Req json(String json) {
      return body(json, "application/json");
    }

    /** A JSON body serialized from {@code value}. */
    Req json(Object value) {
      return json(JSON.writeValueAsString(value));
    }

    /** A body with the given content type ({@code null}: no content type header). */
    Req body(String content, String contentType) {
      this.body = content.getBytes(StandardCharsets.UTF_8);
      if (contentType != null) {
        header("Content-Type", contentType);
      }
      return this;
    }

    /** Sends the body with {@code Transfer-Encoding: chunked} (no declared length). */
    Req chunked() {
      this.chunked = true;
      return this;
    }

    Resp send() {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60));
      for (String[] header : headers) {
        builder.header(header[0], header[1]);
      }
      if (!cookies.isEmpty()) {
        builder.header("Cookie", String.join("; ", cookies));
      }
      HttpRequest.BodyPublisher publisher;
      if (body == null) {
        publisher = HttpRequest.BodyPublishers.noBody();
      } else if (chunked) {
        byte[] bytes = body;
        publisher = HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes));
      } else {
        publisher = HttpRequest.BodyPublishers.ofByteArray(body);
      }
      builder.method(method, publisher);
      long start = System.nanoTime();
      try {
        HttpResponse<String> response =
            HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Resp(
            response.statusCode(), response.headers(), response.body(), System.nanoTime() - start);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
  }

  // Assertions shared by every adversarial test -------------------------------------------------

  /** Fragments that must never appear in an error body (section 15.4). */
  static final List<String> LEAK_MARKERS =
      List.of(
          "Exception",
          "exception",
          "at com.",
          "at org.",
          "at java.",
          "java.lang",
          "springframework",
          "hibernate",
          "jackson",
          "postgres",
          "SQLState",
          "Tomcat",
          "tomcat",
          "trace",
          "\tat ");

  static void assertNoLeak(Resp response) {
    String body = response.body() == null ? "" : response.body();
    for (String marker : LEAK_MARKERS) {
      assertThat(body)
          .as("error body must not contain %s: %s", marker, body)
          .doesNotContain(marker);
    }
  }

  /** Asserts an RFC 9457 problem with the given status and problem code. */
  static void assertProblem(Resp response, int status, String code) {
    assertThat(response.status()).as(response.toString()).isEqualTo(status);
    assertThat(response.header("content-type")).startsWith("application/problem+json");
    assertThat(response.json().get("type").asString())
        .as(response.body())
        .isEqualTo("https://vitadelta.dev/problems/" + code);
    ContractSchemas.assertConforms("Problem", response.body());
    assertNoLeak(response);
  }

  /** The section 15.2 headers every {@code /api/v1/**} response carries. */
  static void assertApiSecurityHeaders(Resp response) {
    assertThat(response.header("x-content-type-options"))
        .as("X-Content-Type-Options on %s", response)
        .isEqualTo("nosniff");
    assertThat(response.header("referrer-policy"))
        .as("Referrer-Policy on %s", response)
        .isEqualTo("no-referrer");
    assertThat(response.header("content-security-policy"))
        .as("Content-Security-Policy on %s", response)
        .isEqualTo("default-src 'none'; frame-ancestors 'none'");
    assertThat(response.header("x-request-id"))
        .as("X-Request-Id on %s", response)
        .matches("^[A-Za-z0-9-]{1,64}$");
  }
}
