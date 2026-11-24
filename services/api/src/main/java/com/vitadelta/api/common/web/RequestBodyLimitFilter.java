package com.vitadelta.api.common.web;

import com.vitadelta.api.common.problem.ProblemBodies;
import com.vitadelta.api.common.problem.Problems;
import com.vitadelta.api.config.properties.HttpLimitsProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enforces the request body size limits of {@code /api/v1/**} (64 kB by default, 20 MB on the
 * import endpoints; {@link HttpLimitsProperties}). A declared {@code Content-Length} over the limit
 * is rejected before the body is read with {@code 413} {@code validation_failed}; a body without a
 * declared length is counted while it is read and fails with {@link PayloadTooLargeException} once
 * it passes the limit.
 */
public class RequestBodyLimitFilter extends OncePerRequestFilter {

  private final HttpLimitsProperties limits;

  public RequestBodyLimitFilter(HttpLimitsProperties limits) {
    this.limits = limits;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/api/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    long limit = limits.limitFor(request.getRequestURI());
    long declared = request.getContentLengthLong();
    if (declared > limit) {
      ProblemBodies.write(
          response,
          Problems.validation(
              HttpStatus.CONTENT_TOO_LARGE,
              "The request body exceeds the size limit.",
              List.of(),
              request));
      return;
    }
    chain.doFilter(new LimitedRequest(request, limit), response);
  }

  /** Counts the bytes read from the body and fails once the limit is passed. */
  static final class LimitedRequest extends HttpServletRequestWrapper {

    private final long limit;
    private ServletInputStream stream;
    private BufferedReader reader;

    LimitedRequest(HttpServletRequest request, long limit) {
      super(request);
      this.limit = limit;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
      if (reader != null) {
        throw new IllegalStateException("getReader() has already been called");
      }
      if (stream == null) {
        stream = new LimitedInputStream(super.getInputStream(), limit);
      }
      return stream;
    }

    @Override
    public BufferedReader getReader() throws IOException {
      if (stream != null) {
        throw new IllegalStateException("getInputStream() has already been called");
      }
      if (reader == null) {
        String encoding = getCharacterEncoding();
        Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
        reader =
            new BufferedReader(
                new InputStreamReader(
                    new LimitedInputStream(super.getInputStream(), limit), charset));
      }
      return reader;
    }
  }

  /** A servlet input stream that throws {@link PayloadTooLargeException} past the limit. */
  static final class LimitedInputStream extends ServletInputStream {

    private final ServletInputStream delegate;
    private final long limit;
    private long count;

    LimitedInputStream(ServletInputStream delegate, long limit) {
      this.delegate = delegate;
      this.limit = limit;
    }

    @Override
    public int read() throws IOException {
      int b = delegate.read();
      if (b >= 0) {
        count(1);
      }
      return b;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int n = delegate.read(buffer, offset, length);
      if (n > 0) {
        count(n);
      }
      return n;
    }

    private void count(int n) throws PayloadTooLargeException {
      count += n;
      if (count > limit) {
        throw new PayloadTooLargeException(limit);
      }
    }

    @Override
    public boolean isFinished() {
      return delegate.isFinished();
    }

    @Override
    public boolean isReady() {
      return delegate.isReady();
    }

    @Override
    public void setReadListener(ReadListener listener) {
      delegate.setReadListener(listener);
    }
  }
}
