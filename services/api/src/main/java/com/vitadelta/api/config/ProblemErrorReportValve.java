package com.vitadelta.api.config;

import com.vitadelta.api.common.problem.ProblemBodies;
import com.vitadelta.api.common.problem.ProblemSecurityHandlers;
import com.vitadelta.api.common.problem.Problems;
import com.vitadelta.api.common.web.ApiHeaders;
import com.vitadelta.api.common.web.RequestIds;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * The container's error report: a request that Tomcat rejects or fails before the application
 * handles it (for example an encoded slash or backslash in the path, a malformed URI or header)
 * gets a problem body ({@code application/problem+json}, {@code about:blank} with the reason
 * phrase, {@code internal_error} for 5xx) with the API security headers and a request id, never
 * Tomcat's HTML page. Responses the application already wrote (every error the application handles
 * is rendered by the global exception handler or the error controller) are left unchanged. Nothing
 * of the request or of an exception is echoed.
 */
public class ProblemErrorReportValve extends ErrorReportValve {

  private static final Logger LOG = LoggerFactory.getLogger(ProblemErrorReportValve.class);

  @Override
  protected void report(Request request, Response response, Throwable throwable) {
    int status = response.getStatus();
    if (status < 400 || response.getContentWritten() > 0 || !response.setErrorReported()) {
      return;
    }
    HttpStatus resolved = HttpStatus.resolve(status);
    ProblemDetail problem;
    if (resolved == null || resolved.is5xxServerError()) {
      problem = Problems.internalError(null);
    } else if (resolved == HttpStatus.BAD_REQUEST) {
      problem = Problems.generic(resolved, ProblemSecurityHandlers.REJECTED_DETAIL, null);
    } else {
      problem = Problems.generic(resolved, resolved.getReasonPhrase() + ".", null);
    }
    Object requestId =
        problem.getProperties() == null ? null : problem.getProperties().get("requestId");
    try {
      response.resetBuffer();
      ApiHeaders.apply(response);
      response.setHeader(RequestIds.HEADER, String.valueOf(requestId));
      response.setContentType(Problems.MEDIA_TYPE);
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      Writer writer = response.getReporter();
      if (writer != null) {
        writer.write(ProblemBodies.json(problem));
        response.finishResponse();
      }
    } catch (IOException | IllegalStateException e) {
      LOG.warn("Container error report could not be written: {}", e.getClass().getSimpleName());
    }
  }
}
