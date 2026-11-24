package com.vitadelta.api.common.problem;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Replaces the default error page: errors that escape the MVC layer (filters, the container) and
 * are forwarded to {@code /error} are rendered as problems. 5xx become {@code internal_error} with
 * the generic detail, 413 becomes {@code validation_failed}, every other status a generic problem.
 * Nothing from the original exception is serialized. A direct request to {@code /error} is denied
 * by the security configuration; only error dispatches reach this controller.
 */
@RestController
public class ProblemErrorController implements ErrorController {

  private static final Logger LOG = LoggerFactory.getLogger(ProblemErrorController.class);

  @RequestMapping("/error")
  public ResponseEntity<Object> error(HttpServletRequest request) {
    Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
    HttpStatus status =
        code instanceof Integer value && HttpStatus.resolve(value) != null
            ? HttpStatus.valueOf(value)
            : HttpStatus.INTERNAL_SERVER_ERROR;
    ErrorRequest original = new ErrorRequest(request);
    if (status.is5xxServerError()) {
      Object exception = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
      if (exception instanceof Throwable throwable) {
        LOG.error("Request failed for {}", original.getRequestURI(), throwable);
      }
      return ProblemBodies.entity(Problems.internalError(original), null);
    }
    if (status == HttpStatus.CONTENT_TOO_LARGE) {
      return ProblemBodies.entity(
          Problems.validation(status, GlobalExceptionHandler.TOO_LARGE_DETAIL, List.of(), original),
          null);
    }
    return ProblemBodies.entity(
        Problems.generic(status, status.getReasonPhrase() + ".", original), null);
  }

  /** Presents the original request path of an error dispatch as the request URI. */
  private static final class ErrorRequest extends jakarta.servlet.http.HttpServletRequestWrapper {

    ErrorRequest(HttpServletRequest request) {
      super(request);
    }

    @Override
    public String getRequestURI() {
      Object uri = getRequest().getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
      return uri instanceof String value ? value : super.getRequestURI();
    }
  }
}
