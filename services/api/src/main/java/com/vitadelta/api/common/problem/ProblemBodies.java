package com.vitadelta.api.common.problem;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;

/**
 * Serializes problems with a fixed member order ({@code type}, {@code title}, {@code status},
 * {@code detail}, {@code instance}, {@code requestId}, {@code errors}, extension members) and
 * extension members flattened into the top level, independent of the JSON mapper configuration.
 */
public final class ProblemBodies {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private ProblemBodies() {}

  /** The JSON object of a problem. */
  public static Map<String, Object> toBody(ProblemDetail problem) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", problem.getType().toString());
    body.put("title", problem.getTitle());
    body.put("status", problem.getStatus());
    if (problem.getDetail() != null) {
      body.put("detail", problem.getDetail());
    }
    if (problem.getInstance() != null) {
      body.put("instance", problem.getInstance().toString());
    }
    Map<String, Object> properties = problem.getProperties();
    if (properties != null) {
      if (properties.containsKey("requestId")) {
        body.put("requestId", properties.get("requestId"));
      }
      if (properties.containsKey("errors")) {
        body.put("errors", properties.get("errors"));
      }
      properties.forEach(body::putIfAbsent);
    }
    return body;
  }

  /** A response entity with the problem body, its status and {@code application/problem+json}. */
  public static ResponseEntity<Object> entity(ProblemDetail problem, HttpHeaders headers) {
    HttpHeaders all = new HttpHeaders();
    if (headers != null) {
      all.addAll(headers);
    }
    all.set(HttpHeaders.CONTENT_TYPE, Problems.MEDIA_TYPE);
    return ResponseEntity.status(problem.getStatus()).headers(all).body(toBody(problem));
  }

  /** Writes a problem to a servlet response (used by filters and security handlers). */
  public static void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
    response.setStatus(problem.getStatus());
    response.setContentType(Problems.MEDIA_TYPE);
    response.setCharacterEncoding("UTF-8");
    MAPPER.writeValue(response.getOutputStream(), toBody(problem));
  }
}
