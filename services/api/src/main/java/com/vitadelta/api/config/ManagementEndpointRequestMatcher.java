package com.vitadelta.api.config;

import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

/**
 * Matches {@code GET} requests to the exposed Actuator endpoints ({@code /actuator/health} and
 * {@code /actuator/metrics}, with sub-paths) that arrive on the <b>management port</b>. The
 * management server runs on its own port ({@code management.server.port}), which is not published
 * by the compose file and not proxied by nginx; on the application port the same paths stay denied.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ManagementEndpointRequestMatcher
    implements RequestMatcher, ApplicationListener<WebServerInitializedEvent> {

  /** Server namespace of the management child context. */
  static final String MANAGEMENT_NAMESPACE = "management";

  private final AtomicInteger managementPort = new AtomicInteger(-1);

  @Override
  public void onApplicationEvent(WebServerInitializedEvent event) {
    if (MANAGEMENT_NAMESPACE.equals(event.getApplicationContext().getServerNamespace())) {
      managementPort.set(event.getWebServer().getPort());
    }
  }

  @Override
  public boolean matches(HttpServletRequest request) {
    int port = managementPort.get();
    if (port <= 0 || request.getLocalPort() != port) {
      return false;
    }
    if (!HttpMethod.GET.matches(request.getMethod())) {
      return false;
    }
    String path = request.getRequestURI();
    return path.equals("/actuator/health")
        || path.startsWith("/actuator/health/")
        || path.equals("/actuator/metrics")
        || path.startsWith("/actuator/metrics/");
  }

  /** The management port, or -1 before the management server has started. */
  public int managementPort() {
    return managementPort.get();
  }
}
