package com.vitadelta.api.mail;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * Renders e-mail templates from {@code classpath:/mail/<locale>/}: {@code <name>.subject.txt}
 * (text), {@code <name>.txt} (text) and {@code <name>.html} (HTML, escaped output only). The
 * language is {@code tr} or {@code en}; every other locale falls back to {@code en}.
 */
@Component
public class MailTemplateRenderer {

  /** Classpath directory holding one sub-directory per {@link MailLocale}. */
  public static final String TEMPLATE_ROOT = "mail/";

  /** Variable holding the base URL of the web app in every template. */
  public static final String HOME_URL = "homeUrl";

  /** Variable holding the privacy notice link in every template. */
  public static final String PRIVACY_URL = "privacyUrl";

  private static final Pattern TEMPLATE_NAME = Pattern.compile("[a-z][a-z0-9-]*");
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  private final TemplateEngine engine;
  private final MailLinks links;

  public MailTemplateRenderer(MailLinks links) {
    this.links = links;
    ClassLoaderTemplateResolver resolver =
        new ClassLoaderTemplateResolver(MailTemplateRenderer.class.getClassLoader());
    resolver.setPrefix(TEMPLATE_ROOT);
    resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
    resolver.setTemplateMode(TemplateMode.TEXT);
    resolver.setHtmlTemplateModePatterns(Set.of("*.html"));
    resolver.setTextTemplateModePatterns(Set.of("*.txt"));
    resolver.setCheckExistence(true);
    resolver.setCacheable(true);
    SpringTemplateEngine templateEngine = new SpringTemplateEngine();
    templateEngine.setTemplateResolver(resolver);
    this.engine = templateEngine;
  }

  /** Renders {@code model} in the language of {@code mail} and addresses it to its recipient. */
  public EmailMessage render(OutgoingMail mail) {
    RenderedMail rendered = render(mail.model(), MailLocale.of(mail.locale()));
    return new EmailMessage(
        mail.to(), rendered.subject(), rendered.textBody(), rendered.htmlBody(), mail.locale());
  }

  /** Renders the three parts of {@code model} in {@code locale}. */
  public RenderedMail render(MailModel model, MailLocale locale) {
    String name = model.templateName();
    if (name == null || !TEMPLATE_NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("invalid template name");
    }
    Map<String, Object> variables = new HashMap<>(model.variables(links, locale));
    variables.put(HOME_URL, links.home());
    variables.put(PRIVACY_URL, links.privacy());
    Context context = new Context(locale.locale(), variables);
    String base = locale.directory() + "/" + name;
    String subject =
        WHITESPACE.matcher(engine.process(base + ".subject.txt", context)).replaceAll(" ").strip();
    String text = engine.process(base + ".txt", context);
    String html = engine.process(base + ".html", context);
    return new RenderedMail(subject, text, html);
  }

  /**
   * The rendered parts of one template.
   *
   * @param subject single-line subject
   * @param textBody plain-text body
   * @param htmlBody HTML body
   */
  public record RenderedMail(String subject, String textBody, String htmlBody) {}
}
