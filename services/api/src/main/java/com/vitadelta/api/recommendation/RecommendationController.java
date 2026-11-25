package com.vitadelta.api.recommendation;

import com.vitadelta.api.recommendation.RecommendationApi.CompareRequest;
import com.vitadelta.api.recommendation.RecommendationApi.CompareResponse;
import com.vitadelta.api.recommendation.RecommendationApi.QueryRequest;
import com.vitadelta.api.recommendation.RecommendationApi.QueryResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/recommendations/query} and {@code POST /api/v1/recommendations/compare}
 * (section 9.1). Both accept every principal; the decisions (quota, visibility, limits) are taken
 * by {@link RecommendationService} and the entitlement module, never here.
 */
@RestController
@RequestMapping(
    path = "/api/v1/recommendations",
    consumes = MediaType.APPLICATION_JSON_VALUE,
    produces = MediaType.APPLICATION_JSON_VALUE)
public class RecommendationController {

  private final RecommendationService recommendations;

  public RecommendationController(RecommendationService recommendations) {
    this.recommendations = recommendations;
  }

  @PostMapping("/query")
  public QueryResponse query(
      @RequestBody QueryRequest body,
      Authentication authentication,
      HttpServletRequest request,
      HttpServletResponse response) {
    return recommendations.query(body, authentication, request, response);
  }

  @PostMapping("/compare")
  public CompareResponse compare(
      @RequestBody CompareRequest body, Authentication authentication, HttpServletRequest request) {
    return recommendations.compare(body, authentication, request);
  }
}
