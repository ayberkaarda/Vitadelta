package com.vitadelta.api.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.common.logging.LogContext;
import jakarta.servlet.FilterChain;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** The request part of the logging context ({@link LogContextFilter}). */
class LogContextFilterTest {

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
    MDC.clear();
  }

  private static Map<String, String> during(MockHttpServletRequest request) throws Exception {
    Map<String, String> seen = new HashMap<>();
    FilterChain chain =
        (req, res) -> {
          LogContext.catalogVersion(7);
          Map<String, String> context = MDC.getCopyOfContextMap();
          if (context != null) {
            seen.putAll(context);
          }
        };
    new LogContextFilter().doFilter(request, new MockHttpServletResponse(), chain);
    return seen;
  }

  @Test
  void anonymousRequestsWithACountryParameter() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/products");
    request.setQueryString("q=gold&country=TR&page=2");
    Map<String, String> seen = during(request);
    assertThat(seen)
        .containsEntry(LogContext.PRINCIPAL_TYPE, "ANONYMOUS")
        .containsEntry(LogContext.COUNTRY, "TR")
        .containsEntry(LogContext.CATALOG_VERSION, "7");
    assertThat(MDC.get(LogContext.PRINCIPAL_TYPE)).as("cleared after the request").isNull();
    assertThat(MDC.get(LogContext.COUNTRY)).isNull();
    assertThat(MDC.get(LogContext.CATALOG_VERSION)).isNull();
  }

  @Test
  void signedInUsersAndAdministrators() throws Exception {
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                "user-id", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    assertThat(during(new MockHttpServletRequest("GET", "/api/v1/me")))
        .containsEntry(LogContext.PRINCIPAL_TYPE, "USER")
        .doesNotContainKey(LogContext.COUNTRY)
        .doesNotContainValue("user-id");
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                "admin-id", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    assertThat(during(new MockHttpServletRequest("GET", "/api/v1/admin/health")))
        .containsEntry(LogContext.PRINCIPAL_TYPE, "ADMIN");
    SecurityContextHolder.getContext()
        .setAuthentication(
            new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
    assertThat(during(new MockHttpServletRequest("GET", "/api/v1/health")))
        .containsEntry(LogContext.PRINCIPAL_TYPE, "ANONYMOUS");
  }

  @Test
  void onlyAWellFormedCountryCodeIsLogged() throws Exception {
    for (String query :
        List.of(
            "country=tr",
            "country=TUR",
            "country=%0ATR",
            "country=TR%0A",
            "country",
            "country=%ZZ",
            "")) {
      MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/products");
      request.setQueryString(query);
      assertThat(during(request)).as(query).doesNotContainKey(LogContext.COUNTRY);
    }
    assertThat(LogContextFilter.queryParameter("a=1&country=DE", "country")).isEqualTo("DE");
    assertThat(LogContextFilter.queryParameter(null, "country")).isNull();
  }
}
