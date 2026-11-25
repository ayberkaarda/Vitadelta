package com.vitadelta.api.recommendation;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.util.function.Predicate;
import org.springframework.web.client.ResourceAccessException;

/**
 * Retry predicate of the scoring client (section 7.6): only connection errors are retried, that is
 * failures before the request reached the scoring service (connection refused, connect timeout,
 * unknown host, no route). A read timeout, a reset during the exchange and every HTTP response (4xx
 * or 5xx) are never retried, so a request that may have reached the service is never sent twice.
 * Referenced by {@code resilience4j.retry.instances.scoring.retry-exception-predicate}.
 */
public class ConnectionErrors implements Predicate<Throwable> {

  @Override
  public boolean test(Throwable throwable) {
    if (!(throwable instanceof ResourceAccessException)) {
      return false;
    }
    for (Throwable cause = throwable.getCause(); cause != null; cause = cause.getCause()) {
      if (cause instanceof ConnectException
          || cause instanceof HttpConnectTimeoutException
          || cause instanceof UnknownHostException
          || cause instanceof NoRouteToHostException) {
        return true;
      }
    }
    return false;
  }
}
