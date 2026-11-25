package com.vitadelta.api.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.config.properties.WebProperties;
import com.vitadelta.api.mail.MailLinks;
import com.vitadelta.api.mail.MailLocale;
import com.vitadelta.api.mail.MailTemplateRenderer;
import com.vitadelta.api.mail.MailTemplateRenderer.RenderedMail;
import com.vitadelta.api.support.TestEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The alert and cap templates (section 8.7): present in both languages with the three parts,
 * rendered with the preference link, the privacy link and escaped catalog texts, and free of {@code
 * th:utext} (section 15.5).
 */
class AlertMailTemplatesTest {

  private static final List<String> TEMPLATES =
      List.of(
          AlertMails.PRICE_DROP_TEMPLATE,
          AlertMails.BACK_IN_STOCK_TEMPLATE,
          AlertMails.PRICE_CHANGE_TEMPLATE,
          AlertMails.CAP_REACHED_TEMPLATE);

  private static final String HOSTILE = "Daily <script>alert(1)</script> & Co";

  private final MailTemplateRenderer renderer =
      new MailTemplateRenderer(
          new MailLinks(
              new WebProperties(
                  List.of(TestEnvironment.WEB_ORIGIN), TestEnvironment.WEB_BASE_URL, true, true)));

  private static String resource(String path) throws IOException {
    try (InputStream in = AlertMailTemplatesTest.class.getResourceAsStream("/" + path)) {
      assertThat(in).as(path).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static NotificationPayloads.AlertNotificationPayload payload(AlertTrigger trigger) {
    boolean drop = trigger == AlertTrigger.PRICE_DROP;
    return new NotificationPayloads.AlertNotificationPayload(
        UUID.randomUUID(),
        trigger.name(),
        UUID.randomUUID(),
        "GOLD-B",
        "Fixture <b>Labs</b>",
        HOSTILE,
        "TR",
        "TRY",
        drop ? new BigDecimal("5.00") : null,
        drop ? new BigDecimal("900.00") : null,
        new OfferState(
            new BigDecimal("900.00"),
            trigger != AlertTrigger.BACK_IN_STOCK,
            "Fixture Pharmacy",
            "https://fixture-pharmacy.example/tr/p/gold-b"),
        new OfferState(
            new BigDecimal("846.00"),
            true,
            "Fixture Pharmacy",
            "https://fixture-pharmacy.example/tr/p/gold-b"));
  }

  @Test
  void everyTemplateExistsInBothLanguagesWithoutUnescapedOutput() throws IOException {
    for (MailLocale locale : MailLocale.values()) {
      for (String template : TEMPLATES) {
        for (String part : List.of(".subject.txt", ".txt", ".html")) {
          String content = resource("mail/" + locale.directory() + "/" + template + part);
          assertThat(content).as(template + part).isNotBlank().doesNotContain("th:utext");
          if (part.equals(".html")) {
            assertThat(content).doesNotContain("[(");
            assertThat(content).contains("${preferencesUrl}").contains("${privacyUrl}");
          } else if (part.equals(".txt")) {
            assertThat(content).contains("[(${preferencesUrl})]").contains("[(${privacyUrl})]");
          }
        }
      }
    }
  }

  @ParameterizedTest
  @EnumSource(AlertTrigger.class)
  void alertMailsCarryThePreferenceAndPrivacyLinksAndEscapeCatalogTexts(AlertTrigger trigger) {
    AlertMails.AlertMail mail = new AlertMails.AlertMail(payload(trigger));
    for (MailLocale locale : MailLocale.values()) {
      RenderedMail rendered = renderer.render(mail, locale);
      assertThat(rendered.subject()).doesNotContain("\n").contains(HOSTILE);
      assertThat(rendered.htmlBody())
          .contains("href=\"https://vitadelta.test/account\"")
          .contains("href=\"https://vitadelta.test/privacy\"")
          .contains("Daily &lt;script&gt;alert(1)&lt;/script&gt; &amp; Co")
          .doesNotContain("<script>")
          .doesNotContain("<b>Labs</b>");
      assertThat(rendered.textBody())
          .contains("https://vitadelta.test/account")
          .contains("https://vitadelta.test/privacy")
          .contains(HOSTILE)
          .contains("GOLD-B");
      if (trigger == AlertTrigger.PRICE_DROP) {
        assertThat(rendered.textBody()).contains("6");
      }
    }
    assertThat(renderer.render(mail, MailLocale.TR).textBody()).contains("846,00");
    assertThat(renderer.render(mail, MailLocale.EN).textBody()).contains("846.00");
  }

  @Test
  void theCapMailNamesTheMonthAndTheLimit() {
    AlertMails.CapReachedMail mail =
        new AlertMails.CapReachedMail(
            new NotificationPayloads.CapReachedPayload(LocalDate.of(2026, 11, 1), 30));
    RenderedMail en = renderer.render(mail, MailLocale.EN);
    assertThat(en.subject())
        .isEqualTo("Your VitaDelta notification limit for November 2026 is reached");
    assertThat(en.textBody())
        .contains("30 notifications")
        .contains("https://vitadelta.test/account");
    RenderedMail tr = renderer.render(mail, MailLocale.TR);
    assertThat(tr.subject()).contains("Kasım 2026");
    assertThat(tr.htmlBody()).contains("href=\"https://vitadelta.test/account\"");
  }

  @Test
  void moneyAndPercentagesAreFormattedPerLanguage() {
    assertThat(AlertMails.money(null, "TRY", MailLocale.EN)).isEmpty();
    assertThat(AlertMails.percent(null, MailLocale.EN)).isEmpty();
    assertThat(AlertMails.percent(new BigDecimal("7.50"), MailLocale.TR)).isEqualTo("7,5");
    assertThat(AlertMails.money(new BigDecimal("12.5"), "EUR", MailLocale.EN)).contains("12.50");
  }
}
