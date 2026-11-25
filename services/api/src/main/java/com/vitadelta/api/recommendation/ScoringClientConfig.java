package com.vitadelta.api.recommendation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vitadelta.api.config.properties.ScoringProperties;
import com.vitadelta.api.recommendation.scoring.ApiClient;
import com.vitadelta.api.recommendation.scoring.api.ComparisonApi;
import com.vitadelta.api.recommendation.scoring.api.ScoringApi;
import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The generated scoring client (contract {@code contracts/scoring-api.openapi.yaml}) on a JDK HTTP
 * client with the timeouts of section 7.6: connect 500 ms (a connect timeout is a connection error
 * and may be retried), read 2 s (never retried). HTTP/1.1 only, no redirects. Optional properties
 * that are {@code null} are omitted from requests; required nullable properties (for example {@code
 * presence_floor_per_day}) are always written, as the generated models declare.
 */
@Configuration(proxyBeanMethods = false)
public class ScoringClientConfig {

  @Bean
  ApiClient scoringApiClient(ScoringProperties properties) {
    JsonMapper mapper =
        ApiClient.createDefaultMapper(null)
            .rebuild()
            .changeDefaultPropertyInclusion(
                inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();
    HttpClient httpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(properties.connectTimeout())
            .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(properties.readTimeout());
    RestClient restClient =
        RestClient.builder()
            .requestFactory(requestFactory)
            .configureMessageConverters(converters ->
                converters
                    .registerDefaults()
                    .withJsonConverter(new JacksonJsonHttpMessageConverter(mapper)))
            .build();
    ApiClient client = new ApiClient(restClient, mapper, ApiClient.createDefaultDateFormat());
    client.setBasePath(properties.apiUrl());
    return client;
  }

  @Bean
  ScoringApi scoringApi(ApiClient scoringApiClient) {
    return new ScoringApi(scoringApiClient);
  }

  @Bean
  ComparisonApi comparisonApi(ApiClient scoringApiClient) {
    return new ComparisonApi(scoringApiClient);
  }
}
