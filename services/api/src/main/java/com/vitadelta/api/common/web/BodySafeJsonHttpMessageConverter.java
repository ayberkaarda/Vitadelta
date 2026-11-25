package com.vitadelta.api.common.web;

import java.io.IOException;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import tools.jackson.core.JacksonException;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The JSON message converter of every request and response body, with read errors that carry no
 * part of the body (project specification section 15.4).
 *
 * <p>Jackson's parse and binding messages quote the offending input ({@code Unrecognized token
 * 'value'}, {@code from String "value"}), and Spring copies them into the {@link
 * HttpMessageNotReadableException} it throws; that message is printed by the framework's own log
 * lines ({@code Could not resolve parameter ...}, {@code Resolved [...]}) and the parser exception
 * travels along as its cause. This converter replaces such an exception by one whose message names
 * only the kind of error and its line and column, and whose cause chain keeps nothing but a {@link
 * PayloadTooLargeException} (the exception handler still answers {@code 413} for it). Everything
 * else (reading, writing, problem details) is the standard converter.
 */
public class BodySafeJsonHttpMessageConverter extends JacksonJsonHttpMessageConverter {

  /** Message of an unreadable body that is not valid JSON. */
  static final String PARSE_ERROR = "JSON parse error";

  /** Message of a valid JSON body that does not bind to the expected type. */
  static final String BINDING_ERROR = "JSON binding error";

  public BodySafeJsonHttpMessageConverter(JsonMapper mapper) {
    super(mapper);
  }

  @Override
  public Object read(
      ResolvableType type, HttpInputMessage inputMessage, @Nullable Map<String, Object> hints)
      throws IOException {
    try {
      return super.read(type, inputMessage, hints);
    } catch (HttpMessageNotReadableException e) {
      throw bodySafe(e, inputMessage);
    }
  }

  @Override
  protected Object readInternal(Class<?> clazz, HttpInputMessage inputMessage) throws IOException {
    try {
      return super.readInternal(clazz, inputMessage);
    } catch (HttpMessageNotReadableException e) {
      throw bodySafe(e, inputMessage);
    }
  }

  /** The body-safe replacement of {@code error}; an already body-safe one is returned as is. */
  static HttpMessageNotReadableException bodySafe(
      HttpMessageNotReadableException error, HttpInputMessage inputMessage) {
    if (error instanceof BodySafeReadException) {
      return error;
    }
    JacksonException parser = null;
    PayloadTooLargeException tooLarge = null;
    for (Throwable t = error.getCause(); t != null && t != t.getCause(); t = t.getCause()) {
      if (parser == null && t instanceof JacksonException jackson) {
        parser = jackson;
      }
      if (t instanceof PayloadTooLargeException limit) {
        tooLarge = limit;
      }
    }
    return new BodySafeReadException(describe(parser), tooLarge, inputMessage);
  }

  /** The kind of error and, when known, its line and column; never any input text. */
  static String describe(@Nullable JacksonException parser) {
    if (parser == null) {
      return "Unreadable request body";
    }
    String kind = parser instanceof DatabindException ? BINDING_ERROR : PARSE_ERROR;
    TokenStreamLocation location = parser.getLocation();
    if (location == null || location.getLineNr() < 1) {
      return kind;
    }
    return kind + " at line " + location.getLineNr() + ", column " + location.getColumnNr();
  }

  /** A read error whose message and cause chain carry no part of the body. */
  static final class BodySafeReadException extends HttpMessageNotReadableException {

    private static final long serialVersionUID = 1L;

    BodySafeReadException(
        String message, @Nullable Throwable cause, HttpInputMessage httpInputMessage) {
      super(message, cause, httpInputMessage);
    }
  }
}
