package com.vitadelta.api.mail;

import com.vitadelta.api.config.properties.WebProperties;
import com.vitadelta.api.support.TestEnvironment;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;

/** Shared builders of the mail tests. */
final class MailTestSupport {

  /** A synthetic base64url token of 43 characters (256 bits), as issued by the identity module. */
  static final String TOKEN = "Zk3sQ9vL_2mXr7Tq-8bNw4YcHp1uEaDfGjKoRiSyVtU";

  private MailTestSupport() {}

  static MailLinks links() {
    return new MailLinks(
        new WebProperties(
            List.of(TestEnvironment.WEB_ORIGIN), TestEnvironment.WEB_BASE_URL, true, true));
  }

  static MailTemplateRenderer renderer() {
    return new MailTemplateRenderer(links());
  }

  static double count(MeterRegistry registry, String name, String template) {
    Counter counter = registry.find(name).tag("template", template).counter();
    return counter == null ? 0 : counter.count();
  }

  static double dropped(MeterRegistry registry, String template, String reason) {
    Counter counter =
        registry
            .find(AuthMailDispatcher.DROPPED)
            .tag("template", template)
            .tag("reason", reason)
            .counter();
    return counter == null ? 0 : counter.count();
  }
}
