package com.vitadelta.api.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitadelta.api.config.properties.HttpLimitsProperties;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

class RequestFiltersTest {

  @Test
  void requestIdIsAcceptedGeneratedStoredAndCleared() throws Exception {
    RequestIdFilter filter = new RequestIdFilter();
    AtomicReference<String> seen = new AtomicReference<>();
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/health");
    request.addHeader(RequestIds.HEADER, "client-id-1");
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(
        request,
        response,
        new MockFilterChain() {
          @Override
          public void doFilter(
              jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
            seen.set(MDC.get(RequestIds.KEY));
          }
        });
    assertThat(seen.get()).isEqualTo("client-id-1");
    assertThat(response.getHeader(RequestIds.HEADER)).isEqualTo("client-id-1");
    assertThat(RequestIds.current(request)).contains("client-id-1");
    assertThat(MDC.get(RequestIds.KEY)).isNull();

    assertThat(RequestIds.acceptOrGenerate("x".repeat(65))).hasSize(36);
    assertThat(RequestIds.acceptOrGenerate("x".repeat(64))).isEqualTo("x".repeat(64));
    assertThat(RequestIds.acceptOrGenerate("a\nb")).isNotEqualTo("a\nb");
    assertThat(RequestIds.acceptOrGenerate("")).hasSize(36);
    assertThat(RequestIds.acceptOrGenerate(null)).matches(RequestIds.FORMAT);
    MDC.put(RequestIds.KEY, "from-mdc");
    try {
      assertThat(RequestIds.current(null)).contains("from-mdc");
    } finally {
      MDC.remove(RequestIds.KEY);
    }
  }

  private static final HttpLimitsProperties LIMITS =
      new HttpLimitsProperties(
          DataSize.ofBytes(10), DataSize.ofBytes(100), List.of("/api/v1/admin/imports/"));

  @Test
  void declaredLengthOverTheLimitIsRejectedWithAProblem() throws Exception {
    RequestBodyLimitFilter filter = new RequestBodyLimitFilter(LIMITS);
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/recommendations/query");
    request.setContent(new byte[11]);
    request.setAttribute(RequestIds.KEY, "rid-1");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(request, response, chain);
    assertThat(response.getStatus()).isEqualTo(413);
    assertThat(response.getContentType()).startsWith("application/problem+json");
    assertThat(response.getContentAsString())
        .contains("\"type\":\"https://vitadelta.dev/problems/validation_failed\"")
        .contains("\"requestId\":\"rid-1\"");
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void importPathsUseTheLargerLimitAndOtherPathsAreNotFiltered() throws Exception {
    RequestBodyLimitFilter filter = new RequestBodyLimitFilter(LIMITS);
    MockHttpServletRequest imports =
        new MockHttpServletRequest("POST", "/api/v1/admin/imports/offers");
    imports.setContent(new byte[50]);
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(imports, new MockHttpServletResponse(), chain);
    assertThat(chain.getRequest()).isNotNull();

    MockHttpServletRequest other = new MockHttpServletRequest("POST", "/actuator/health");
    other.setContent(new byte[50]);
    MockFilterChain otherChain = new MockFilterChain();
    filter.doFilter(other, new MockHttpServletResponse(), otherChain);
    assertThat(otherChain.getRequest()).isSameAs(other);
  }

  /** A request whose body length is not declared (chunked transfer). */
  private static MockHttpServletRequest undeclared(byte[] body) {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/recommendations/query") {
          @Override
          public long getContentLengthLong() {
            return -1;
          }

          @Override
          public int getContentLength() {
            return -1;
          }
        };
    request.setContent(body);
    return request;
  }

  private static HttpServletRequest wrapped(MockHttpServletRequest request) throws Exception {
    MockFilterChain chain = new MockFilterChain();
    new RequestBodyLimitFilter(LIMITS).doFilter(request, new MockHttpServletResponse(), chain);
    return (HttpServletRequest) chain.getRequest();
  }

  @Test
  void undeclaredBodiesAreCountedWhileRead() throws Exception {
    HttpServletRequest small = wrapped(undeclared("0123456789".getBytes(StandardCharsets.UTF_8)));
    ServletInputStream in = small.getInputStream();
    assertThat(in.readAllBytes()).hasSize(10);
    assertThat(small.getInputStream()).isSameAs(in);
    assertThat(in.isFinished()).isTrue();
    assertThat(in.isReady()).isTrue();
    assertThatThrownBy(small::getReader).isInstanceOf(IllegalStateException.class);

    HttpServletRequest large = wrapped(undeclared("0123456789A".getBytes(StandardCharsets.UTF_8)));
    assertThatThrownBy(() -> large.getInputStream().readAllBytes())
        .isInstanceOf(PayloadTooLargeException.class);

    HttpServletRequest singleBytes = wrapped(undeclared(new byte[11]));
    ServletInputStream bytes = singleBytes.getInputStream();
    assertThatThrownBy(
            () -> {
              for (int i = 0; i < 20; i++) {
                bytes.read();
              }
            })
        .isInstanceOf(PayloadTooLargeException.class);
  }

  @Test
  void readersAreCountedToo() throws Exception {
    MockHttpServletRequest request = undeclared("01234567890123".getBytes(StandardCharsets.UTF_8));
    request.setCharacterEncoding("UTF-8");
    HttpServletRequest limited = wrapped(request);
    BufferedReader reader = limited.getReader();
    assertThat(limited.getReader()).isSameAs(reader);
    assertThatThrownBy(reader::readLine).isInstanceOf(IOException.class);
    assertThatThrownBy(limited::getInputStream).isInstanceOf(IllegalStateException.class);

    HttpServletRequest defaultCharset = wrapped(undeclared("ok".getBytes(StandardCharsets.UTF_8)));
    assertThat(defaultCharset.getReader().readLine()).isEqualTo("ok");
  }
}
