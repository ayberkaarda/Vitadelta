package com.vitadelta.api.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import tools.jackson.databind.json.JsonMapper;

/** Read errors of {@link BodySafeJsonHttpMessageConverter} quote no part of the body. */
class BodySafeJsonHttpMessageConverterTest {

  private static final String SECRET = "sEcReTvalue1234567890";

  record Credentials(String email, String password, List<String> roles) {}

  enum Plan {
    FREE,
    PRO
  }

  record Redemption(Plan plan, java.util.UUID id, int days) {}

  private final BodySafeJsonHttpMessageConverter converter =
      new BodySafeJsonHttpMessageConverter(JsonMapper.builder().build());

  private static MockHttpInputMessage json(String body) {
    MockHttpInputMessage message = new MockHttpInputMessage(body.getBytes(StandardCharsets.UTF_8));
    message.getHeaders().setContentType(MediaType.APPLICATION_JSON);
    return message;
  }

  private static String everything(Throwable error) {
    StringWriter out = new StringWriter();
    error.printStackTrace(new PrintWriter(out));
    return out.toString();
  }

  private HttpMessageNotReadableException readFailure(Class<?> type, String body) {
    return catchThrowableOfType(
        HttpMessageNotReadableException.class,
        () -> converter.read(ResolvableType.forClass(type), json(body), null));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"email\":\"a@example.org\",\"password\":" + SECRET + "}",
        "{\"email\":\"a@example.org\",\"password\":\"" + SECRET,
        "{\"email\":\"a@example.org\",\"password\":{\"v\":\"" + SECRET + "\"}}",
        "{\"email\":\"a@example.org\",\"password\":\"x\",\"roles\":\"" + SECRET + "\"}",
        "{\"email\":\"a@example.org\",\"password\":\"x\",\"roles\":[" + SECRET + "]}",
        "[\"" + SECRET + "\"]",
        SECRET
      })
  void aBodyThatDoesNotBindFailsWithoutQuotingIt(String body) {
    HttpMessageNotReadableException error = readFailure(Credentials.class, body);

    assertThat(error).isNotNull();
    assertThat(everything(error)).doesNotContain(SECRET).doesNotContain("a@example.org");
    assertThat(error.getMessage())
        .matches("JSON (parse|binding) error( at line \\d+, column \\d+)?");
    assertThat(error.getCause()).isNull();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"plan\":\"" + SECRET + "\"}",
        "{\"id\":\"" + SECRET + "\"}",
        "{\"days\":\"" + SECRET + "\"}",
        "{\"plan\":" + SECRET + "}"
      })
  void mistypedValuesAreNotQuotedEither(String body) {
    HttpMessageNotReadableException error = readFailure(Redemption.class, body);

    assertThat(everything(error)).doesNotContain(SECRET);
  }

  @Test
  void theReadClassEntryPointIsBodySafeToo() {
    HttpMessageNotReadableException error =
        catchThrowableOfType(
            HttpMessageNotReadableException.class,
            () ->
                converter.read(
                    Credentials.class, json("{\"email\":\"a\",\"password\":" + SECRET + "}")));

    assertThat(everything(error)).doesNotContain(SECRET);
    assertThat(error.getMessage()).startsWith(BodySafeJsonHttpMessageConverter.PARSE_ERROR);
  }

  @Test
  void anOversizedBodyKeepsOnlyThePayloadTooLargeCause() {
    HttpInputMessage tooLarge =
        new HttpInputMessage() {
          @Override
          public InputStream getBody() {
            return new InputStream() {
              private final InputStream start =
                  new ByteArrayInputStream(
                      ("{\"password\":\"" + SECRET).getBytes(StandardCharsets.UTF_8));

              @Override
              public int read() throws IOException {
                int next = start.read();
                if (next < 0) {
                  throw new PayloadTooLargeException(64);
                }
                return next;
              }
            };
          }

          @Override
          public HttpHeaders getHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            return headers;
          }
        };

    HttpMessageNotReadableException error =
        catchThrowableOfType(
            HttpMessageNotReadableException.class,
            () -> converter.read(ResolvableType.forClass(Credentials.class), tooLarge, null));

    assertThat(error.getCause()).isInstanceOf(PayloadTooLargeException.class);
    assertThat(error.getCause().getCause()).isNull();
    assertThat(everything(error)).doesNotContain(SECRET);
  }

  @Test
  void aValidBodyIsReadAndABodySafeErrorIsNotWrappedTwice() throws IOException {
    Object read =
        converter.read(
            ResolvableType.forClass(Credentials.class),
            json("{\"email\":\"a@example.org\",\"password\":\"pw\",\"roles\":[\"USER\"]}"),
            null);
    assertThat(read).isEqualTo(new Credentials("a@example.org", "pw", List.of("USER")));

    HttpMessageNotReadableException first = readFailure(Credentials.class, "{");
    assertThat(BodySafeJsonHttpMessageConverter.bodySafe(first, json("{}"))).isSameAs(first);
    assertThat(
            BodySafeJsonHttpMessageConverter.bodySafe(
                    new HttpMessageNotReadableException("Unreadable " + SECRET, json("{}")),
                    json("{}"))
                .getMessage())
        .isEqualTo("Unreadable request body");
  }
}
