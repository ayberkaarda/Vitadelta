package com.vitadelta.api.mail;

import static com.vitadelta.api.mail.MailTestSupport.TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitadelta.api.mail.MailTemplateRenderer.RenderedMail;
import com.vitadelta.api.support.TestEnvironment;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Rendering of every identity and plan template in both languages. */
class MailTemplateRendererTest {

  private static final String BASE = TestEnvironment.WEB_BASE_URL;
  private static final String PRIVACY = BASE + "/privacy";
  private static final Pattern HREF = Pattern.compile("href=\"([^\"]*)\"");
  private static final String HOSTILE = "<script>alert('x')</script>";
  private static final Instant PERIOD_END = Instant.parse("2026-12-24T10:15:30Z");

  private final MailTemplateRenderer renderer = MailTestSupport.renderer();

  static Stream<Arguments> templates() {
    List<Arguments> all = new ArrayList<>();
    for (MailLocale locale : MailLocale.values()) {
      all.add(Arguments.of(locale, new VerifyEmailMail(TOKEN)));
      all.add(Arguments.of(locale, new AlreadyRegisteredMail()));
      all.add(Arguments.of(locale, new ResetPasswordMail(TOKEN)));
      all.add(Arguments.of(locale, new PlanActivatedMail("PRO", PERIOD_END)));
      all.add(Arguments.of(locale, new PlanExpiredMail("PRO")));
    }
    return all.stream();
  }

  @ParameterizedTest(name = "{1} in {0}")
  @MethodSource("templates")
  void rendersSubjectTextAndHtmlWithPrivacyLinkAndBaseUrlLinksOnly(
      MailLocale locale, MailModel model) {
    RenderedMail mail = renderer.render(model, locale);

    assertThat(mail.subject()).isNotBlank().doesNotContain("\n").doesNotContain("\r");
    assertThat(mail.subject())
        .contains("VitaDelta")
        .doesNotContain("[[")
        .doesNotContain("[(")
        .doesNotContain("${");
    assertThat(mail.textBody())
        .contains(PRIVACY)
        .doesNotContain("[[")
        .doesNotContain("[(")
        .doesNotContain("${");
    assertThat(mail.htmlBody())
        .contains("href=\"" + PRIVACY + "\"")
        .contains("lang=\"" + locale.directory() + "\"")
        .doesNotContain(" th:")
        .doesNotContain("xmlns:th")
        .doesNotContain("[[")
        .doesNotContain("${");

    List<String> hrefs = hrefs(mail.htmlBody());
    assertThat(hrefs).hasSizeGreaterThanOrEqualTo(2).allMatch(h -> h.startsWith(BASE + "/"));
    for (String line : mail.textBody().lines().toList()) {
      if (line.contains("://")) {
        assertThat(line.substring(line.indexOf("http"))).startsWith(BASE + "/");
      }
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("locales")
  void verificationLinkCarriesTheTokenOnlyInTheFragment(MailLocale locale) {
    RenderedMail mail = renderer.render(new VerifyEmailMail(TOKEN), locale);
    String link = BASE + "/auth/verify#token=" + TOKEN;
    assertThat(mail.textBody()).contains(link);
    assertThat(mail.htmlBody()).contains("href=\"" + link + "\"");
    assertTokenOnlyInFragment(mail);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("locales")
  void resetLinkCarriesTheTokenOnlyInTheFragment(MailLocale locale) {
    RenderedMail mail = renderer.render(new ResetPasswordMail(TOKEN), locale);
    String link = BASE + "/auth/reset#token=" + TOKEN;
    assertThat(mail.textBody()).contains(link);
    assertThat(mail.htmlBody()).contains("href=\"" + link + "\"");
    assertTokenOnlyInFragment(mail);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("locales")
  void alreadyRegisteredLinksToLoginAndForgotPassword(MailLocale locale) {
    RenderedMail mail = renderer.render(new AlreadyRegisteredMail(), locale);
    assertThat(hrefs(mail.htmlBody()))
        .contains(BASE + "/auth/login", BASE + "/auth/forgot", PRIVACY);
    assertThat(mail.textBody()).doesNotContain("token");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("locales")
  void hostileValuesAreEscapedInHtml(MailLocale locale) {
    for (MailModel model :
        List.<MailModel>of(
            new PlanActivatedMail(HOSTILE, PERIOD_END), new PlanExpiredMail(HOSTILE))) {
      RenderedMail mail = renderer.render(model, locale);
      assertThat(mail.htmlBody())
          .doesNotContain("<script>")
          .contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;");
      assertThat(mail.subject()).contains(HOSTILE);
      assertThat(mail.textBody()).contains(HOSTILE).doesNotContain("&lt;");
    }
  }

  @Test
  void planActivatedShowsTheLocalizedUtcDate() {
    PlanActivatedMail model = new PlanActivatedMail("PRO", Instant.parse("2026-11-30T23:30:00Z"));
    assertThat(renderer.render(model, MailLocale.TR).textBody()).contains("30 Kasım 2026");
    assertThat(renderer.render(model, MailLocale.EN).textBody()).contains("November 30, 2026");
    assertThat(renderer.render(model, MailLocale.TR).subject())
        .isEqualTo("VitaDelta PRO planınız etkin");
    assertThat(renderer.render(model, MailLocale.EN).subject())
        .isEqualTo("Your VitaDelta PRO plan is active");
  }

  @Test
  void turkishCopyKeepsTurkishLetters() {
    RenderedMail tr = renderer.render(new VerifyEmailMail(TOKEN), MailLocale.TR);
    assertThat(tr.subject()).isEqualTo("VitaDelta e-posta adresinizi doğrulayın");
    assertThat(tr.textBody()).contains("Gizlilik bildirimi").contains("yalnızca");
    assertThat(tr.htmlBody()).contains("Düğme çalışmazsa");
    RenderedMail en = renderer.render(new VerifyEmailMail(TOKEN), MailLocale.EN);
    assertThat(en.subject()).isEqualTo("Confirm your VitaDelta e-mail address");
    assertThat(en.textBody()).contains("Privacy notice");
  }

  @Test
  void outgoingMailIsRenderedInItsLocaleWithEnglishFallback() {
    EmailMessage tr =
        renderer.render(OutgoingMail.verifyEmail("user@example.org", Locale.of("tr", "TR"), TOKEN));
    assertThat(tr.locale()).isEqualTo(Locale.forLanguageTag("tr"));
    assertThat(tr.to()).isEqualTo("user@example.org");
    assertThat(tr.subject()).contains("doğrulayın");

    EmailMessage german =
        renderer.render(OutgoingMail.resetPassword("user@example.org", Locale.GERMAN, TOKEN));
    assertThat(german.locale()).isEqualTo(Locale.forLanguageTag("en"));
    assertThat(german.subject()).isEqualTo("Reset your VitaDelta password");

    EmailMessage unset = renderer.render(OutgoingMail.alreadyRegistered("user@example.org", null));
    assertThat(unset.subject()).isEqualTo("You already have a VitaDelta account");
  }

  @Test
  void rejectsInvalidTemplateNamesAndUnknownTemplates() {
    MailModel traversal = model("../secret");
    assertThatThrownBy(() -> renderer.render(traversal, MailLocale.EN))
        .isInstanceOf(IllegalArgumentException.class);
    MailModel missing = model("no-such-template");
    assertThatThrownBy(() -> renderer.render(missing, MailLocale.EN))
        .isInstanceOf(RuntimeException.class);
  }

  static Stream<MailLocale> locales() {
    return Stream.of(MailLocale.values());
  }

  private static MailModel model(String name) {
    return new MailModel() {
      @Override
      public String templateName() {
        return name;
      }

      @Override
      public Map<String, Object> variables(MailLinks links, MailLocale locale) {
        return Map.of();
      }
    };
  }

  private static void assertTokenOnlyInFragment(RenderedMail mail) {
    for (String body : List.of(mail.subject(), mail.textBody(), mail.htmlBody())) {
      int from = 0;
      int index;
      while ((index = body.indexOf(TOKEN, from)) >= 0) {
        assertThat(body.substring(0, index)).endsWith("#token=");
        from = index + TOKEN.length();
      }
      assertThat(body).doesNotContain("?token").doesNotContain("&token");
    }
    assertThat(mail.subject()).doesNotContain(TOKEN);
  }

  private static List<String> hrefs(String html) {
    List<String> values = new ArrayList<>();
    Matcher matcher = HREF.matcher(html);
    while (matcher.find()) {
      values.add(matcher.group(1));
    }
    return values;
  }
}
