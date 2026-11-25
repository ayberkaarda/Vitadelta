package com.vitadelta.api.common.problem;

import com.vitadelta.api.common.web.PayloadTooLargeException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every exception raised while handling a request to an RFC 9457 problem (project
 * specification sections 9.3 and 15.4).
 *
 * <ul>
 *   <li>{@link ProblemException}: its code, status, detail, field errors, extension members and
 *       headers.
 *   <li>Bean Validation, unreadable bodies, missing or mistyped parameters: {@code
 *       validation_failed} (422) with {@code errors[]}; a body over the size limit: {@code
 *       validation_failed} with 413.
 *   <li>Authentication and authorization failures raised inside controllers: {@code unauthorized}
 *       (401) for unauthenticated principals, otherwise {@code forbidden} (403).
 *   <li>Other Spring MVC exceptions (unknown path, unsupported method or media type): a generic
 *       {@code about:blank} problem with the standard reason phrase.
 *   <li>Anything else: {@code internal_error} (500) with a generic detail. Exception class names,
 *       messages and stack traces are never serialized; the exception is logged (through the
 *       redacting log layer) with the request id.
 * </ul>
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  static final String VALIDATION_DETAIL = "The request contains invalid fields.";
  static final String UNREADABLE_DETAIL = "The request body is not valid JSON for this operation.";
  static final String TOO_LARGE_DETAIL = "The request body exceeds the size limit.";
  static final String MULTIPART_DETAIL = "The multipart body or one of its parts cannot be read.";

  @ExceptionHandler(ProblemException.class)
  ResponseEntity<Object> handleProblem(ProblemException exception, HttpServletRequest request) {
    return ProblemBodies.entity(Problems.of(exception, request), exception.headers());
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ResponseEntity<Object> handleConstraintViolation(
      ConstraintViolationException exception, HttpServletRequest request) {
    List<FieldViolation> errors = new ArrayList<>();
    for (ConstraintViolation<?> violation : exception.getConstraintViolations()) {
      String annotation =
          violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
      errors.add(
          new FieldViolation(
              fieldPath(violation.getPropertyPath().toString()),
              codeFor(annotation),
              String.valueOf(violation.getMessage())));
    }
    return validation(HttpStatus.UNPROCESSABLE_CONTENT, VALIDATION_DETAIL, errors, request);
  }

  @ExceptionHandler(PayloadTooLargeException.class)
  ResponseEntity<Object> handleTooLarge(Exception exception, HttpServletRequest request) {
    return validation(HttpStatus.CONTENT_TOO_LARGE, TOO_LARGE_DETAIL, List.of(), request);
  }

  /**
   * A multipart body the container cannot parse, for example a part whose file name contains NUL or
   * other characters the container rejects: {@code 422 validation_failed} on the {@code file} part.
   * Uploaded file names are never used, so nothing of the name is echoed. An oversized body is
   * still {@code 413}.
   */
  @ExceptionHandler(MultipartException.class)
  ResponseEntity<Object> handleMultipart(MultipartException exception, HttpServletRequest request) {
    if (exception instanceof MaxUploadSizeExceededException || causedByTooLarge(exception)) {
      return validation(HttpStatus.CONTENT_TOO_LARGE, TOO_LARGE_DETAIL, List.of(), request);
    }
    LOG.warn(
        "Unreadable multipart body for {} {}: {}",
        request.getMethod(),
        request.getRequestURI(),
        exception.getClass().getSimpleName());
    return validation(
        HttpStatus.UNPROCESSABLE_CONTENT,
        VALIDATION_DETAIL,
        List.of(new FieldViolation("file", "invalid_format", MULTIPART_DETAIL)),
        request);
  }

  @ExceptionHandler(AuthenticationException.class)
  ResponseEntity<Object> handleAuthentication(
      AuthenticationException exception, HttpServletRequest request) {
    return ProblemBodies.entity(unauthorized(request), null);
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<Object> handleAccessDenied(
      AccessDeniedException exception, HttpServletRequest request) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    boolean anonymous =
        authentication == null
            || authentication instanceof AnonymousAuthenticationToken
            || !authentication.isAuthenticated();
    return ProblemBodies.entity(anonymous ? unauthorized(request) : forbidden(request), null);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Object> handleUnexpected(Exception exception, HttpServletRequest request) {
    LOG.error(
        "Unhandled exception for {} {}", request.getMethod(), request.getRequestURI(), exception);
    return ProblemBodies.entity(Problems.internalError(request), null);
  }

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception exception,
      Object body,
      HttpHeaders headers,
      HttpStatusCode statusCode,
      WebRequest webRequest) {
    HttpServletRequest request = ((ServletWebRequest) webRequest).getRequest();
    HttpHeaders responseHeaders = headers == null ? new HttpHeaders() : headers;
    return switch (exception) {
      case MethodArgumentNotValidException e ->
          validation(
              HttpStatus.UNPROCESSABLE_CONTENT,
              VALIDATION_DETAIL,
              fromBindingResult(e.getBindingResult()),
              request);
      case HandlerMethodValidationException e ->
          validation(
              HttpStatus.UNPROCESSABLE_CONTENT,
              VALIDATION_DETAIL,
              fromMethodValidation(e),
              request);
      case HttpMessageNotReadableException e when causedByTooLarge(e) ->
          validation(HttpStatus.CONTENT_TOO_LARGE, TOO_LARGE_DETAIL, List.of(), request);
      case HttpMessageNotReadableException e ->
          validation(
              HttpStatus.UNPROCESSABLE_CONTENT,
              UNREADABLE_DETAIL,
              List.of(new FieldViolation("body", "invalid_format", UNREADABLE_DETAIL)),
              request);
      case MissingServletRequestParameterException e ->
          validation(
              HttpStatus.UNPROCESSABLE_CONTENT,
              VALIDATION_DETAIL,
              List.of(new FieldViolation(e.getParameterName(), "required", "Value is required.")),
              request);
      case MissingRequestValueException e ->
          validation(
              HttpStatus.UNPROCESSABLE_CONTENT,
              VALIDATION_DETAIL,
              List.of(new FieldViolation("request", "required", "A required value is missing.")),
              request);
      case ServletRequestBindingException e ->
          validation(HttpStatus.UNPROCESSABLE_CONTENT, VALIDATION_DETAIL, List.of(), request);
      case MaxUploadSizeExceededException e ->
          validation(HttpStatus.CONTENT_TOO_LARGE, TOO_LARGE_DETAIL, List.of(), request);
      default -> {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        String detail = status == null ? "Request failed." : status.getReasonPhrase() + ".";
        ProblemDetail problem =
            statusCode.is5xxServerError()
                ? Problems.internalError(request)
                : Problems.generic(statusCode, detail, request);
        if (statusCode.is5xxServerError()) {
          LOG.error(
              "Request failed for {} {}", request.getMethod(), request.getRequestURI(), exception);
        }
        yield ProblemBodies.entity(problem, responseHeaders);
      }
    };
  }

  @Override
  protected ResponseEntity<Object> handleTypeMismatch(
      TypeMismatchException exception,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest webRequest) {
    HttpServletRequest request = ((ServletWebRequest) webRequest).getRequest();
    String field =
        exception instanceof MethodArgumentTypeMismatchException mismatch
            ? mismatch.getName()
            : String.valueOf(exception.getPropertyName());
    return validation(
        HttpStatus.UNPROCESSABLE_CONTENT,
        VALIDATION_DETAIL,
        List.of(new FieldViolation(field, "invalid_format", "Value has an invalid format.")),
        request);
  }

  private static ResponseEntity<Object> validation(
      HttpStatus status, String detail, List<FieldViolation> errors, HttpServletRequest request) {
    return ProblemBodies.entity(Problems.validation(status, detail, errors, request), null);
  }

  static ProblemDetail unauthorized(HttpServletRequest request) {
    return Problems.of(
        ProblemCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED, "Authentication is required.", request);
  }

  static ProblemDetail forbidden(HttpServletRequest request) {
    return Problems.of(
        ProblemCode.FORBIDDEN,
        HttpStatus.FORBIDDEN,
        "The principal may not perform this action.",
        request);
  }

  private static boolean causedByTooLarge(Throwable exception) {
    for (Throwable t = exception; t != null; t = t.getCause()) {
      if (t instanceof PayloadTooLargeException) {
        return true;
      }
    }
    return false;
  }

  static List<FieldViolation> fromBindingResult(BindingResult result) {
    List<FieldViolation> errors = new ArrayList<>();
    for (FieldError error : result.getFieldErrors()) {
      errors.add(
          new FieldViolation(
              fieldPath(error.getField()), codeFor(error.getCode()), message(error)));
    }
    for (ObjectError error : result.getGlobalErrors()) {
      errors.add(
          new FieldViolation(error.getObjectName(), codeFor(error.getCode()), message(error)));
    }
    return errors;
  }

  private static List<FieldViolation> fromMethodValidation(HandlerMethodValidationException e) {
    List<FieldViolation> errors = new ArrayList<>();
    e.getParameterValidationResults()
        .forEach(result -> {
          String name = result.getMethodParameter().getParameterName();
          for (MessageSourceResolvable error : result.getResolvableErrors()) {
            String[] codes = error.getCodes();
            String code = codes == null || codes.length == 0 ? null : codes[codes.length - 1];
            errors.add(
                new FieldViolation(
                    name == null ? "request" : name,
                    codeFor(code),
                    String.valueOf(error.getDefaultMessage())));
          }
        });
    return errors;
  }

  private static String message(ObjectError error) {
    return error.getDefaultMessage() == null ? "Value is invalid." : error.getDefaultMessage();
  }

  private static final Map<String, String> CONSTRAINT_CODES =
      Map.ofEntries(
          Map.entry("NotNull", "required"),
          Map.entry("NotBlank", "required"),
          Map.entry("NotEmpty", "required"),
          Map.entry("Size", "out_of_range"),
          Map.entry("Min", "out_of_range"),
          Map.entry("Max", "out_of_range"),
          Map.entry("DecimalMin", "out_of_range"),
          Map.entry("DecimalMax", "out_of_range"),
          Map.entry("Positive", "out_of_range"),
          Map.entry("PositiveOrZero", "out_of_range"),
          Map.entry("Negative", "out_of_range"),
          Map.entry("NegativeOrZero", "out_of_range"),
          Map.entry("Digits", "out_of_range"),
          Map.entry("Pattern", "invalid_format"),
          Map.entry("Email", "invalid_format"),
          Map.entry("typeMismatch", "invalid_format"));

  /** Maps a constraint or binding error code to a field error code ({@code ^[a-z][a-z0-9_]*$}). */
  static String codeFor(String constraint) {
    if (constraint == null || constraint.isBlank()) {
      return "invalid";
    }
    String mapped = CONSTRAINT_CODES.get(constraint);
    if (mapped != null) {
      return mapped;
    }
    String snake =
        constraint
            .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
            .replaceAll("[^A-Za-z0-9]+", "_")
            .toLowerCase(Locale.ROOT)
            .replaceAll("^_+|_+$", "");
    return snake.isEmpty() || !Character.isLetter(snake.charAt(0)) ? "invalid" : snake;
  }

  /** The property path of a violation, or {@code request} when it is empty. */
  static String fieldPath(String path) {
    if (path == null || path.isBlank()) {
      return "request";
    }
    return path;
  }
}
