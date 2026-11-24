package com.vitadelta.api.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes the request id of every request before any other filter runs: the client's {@code
 * X-Request-Id} is accepted only if it matches {@code ^[A-Za-z0-9-]{1,64}$}, otherwise a new id is
 * generated. The id is stored as request attribute and in the logging context ({@code requestId})
 * and echoed in the {@code X-Request-Id} response header, also on error responses written by the
 * security filters. Registered with the highest precedence by {@code WebInfrastructureConfig}.
 */
public class RequestIdFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String id = RequestIds.acceptOrGenerate(request.getHeader(RequestIds.HEADER));
    request.setAttribute(RequestIds.KEY, id);
    response.setHeader(RequestIds.HEADER, id);
    MDC.put(RequestIds.KEY, id);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(RequestIds.KEY);
    }
  }
}
