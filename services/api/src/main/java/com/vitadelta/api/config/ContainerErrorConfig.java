package com.vitadelta.api.config;

import org.apache.catalina.Container;
import org.apache.catalina.Valve;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.valves.ErrorReportValve;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.tomcat.ConfigurableTomcatWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Replaces the host's error report valve of the embedded Tomcat with {@link
 * ProblemErrorReportValve}, so that requests rejected by the container before any filter runs are
 * answered as problems with the API security headers (sections 9.3 and 15.2). Runs after Spring
 * Boot's own Tomcat customizer, which installs a plain {@link ErrorReportValve}; that valve is
 * removed.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ContainerErrorConfig {

  @Bean
  WebServerFactoryCustomizer<ConfigurableTomcatWebServerFactory> problemErrorReportCustomizer() {
    return new ProblemErrorReportCustomizer();
  }

  /** Installs the valve on the host of the application context. */
  static final class ProblemErrorReportCustomizer
      implements WebServerFactoryCustomizer<ConfigurableTomcatWebServerFactory>, Ordered {

    @Override
    public void customize(ConfigurableTomcatWebServerFactory factory) {
      factory.addContextCustomizers(context -> install(context.getParent()));
    }

    @Override
    public int getOrder() {
      return Ordered.LOWEST_PRECEDENCE;
    }
  }

  /**
   * Removes every error report valve of {@code host} and adds a {@link ProblemErrorReportValve}.
   */
  static void install(Container host) {
    if (host instanceof StandardHost standardHost) {
      // The host adds a valve of this class at start unless one is present; naming ours keeps it
      // the only error report valve.
      standardHost.setErrorReportValveClass(ProblemErrorReportValve.class.getName());
    }
    for (Valve valve : host.getPipeline().getValves()) {
      if (valve instanceof ErrorReportValve) {
        host.getPipeline().removeValve(valve);
      }
    }
    ProblemErrorReportValve valve = new ProblemErrorReportValve();
    valve.setShowReport(false);
    valve.setShowServerInfo(false);
    host.getPipeline().addValve(valve);
  }
}
