package com.vitadelta.api.adversarial;

import static com.vitadelta.api.adversarial.AttackClient.assertNoLeak;
import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.adversarial.AttackClient.Resp;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Section 15.7 case 9 on every free-text input that exists in Phase 3: hostile strings in the auth
 * bodies, the profile settings, promo codes, admin user search and admin path variables are
 * ordinary validation input; no statement changes a row count or drops a table, and nothing answers
 * with a server error.
 */
class InjectionAdversarialTest extends AdversarialIntegrationTest {

  static final List<String> HOSTILE =
      List.of(
          "' OR '1'='1",
          "'); DROP TABLE app_user;--",
          "); DROP TABLE product;--",
          "\"; DROP TABLE promo_code; --",
          "admin'--",
          "x' UNION SELECT password_hash FROM app_user--",
          "1; SELECT pg_sleep(5)--",
          "%' OR email ILIKE '%",
          "\\'; DELETE FROM app_user WHERE ''='",
          "${jndi:ldap://127.0.0.1/a}",
          "<script>alert(1)</script>",
          "\u0000' OR 1=1--");

  private static final List<String> TABLES =
      List.of(
          "app_user",
          "subscription",
          "refresh_token",
          "email_token",
          "promo_code",
          "promo_redemption",
          "product",
          "plan",
          "anonymous_identity");

  private Map<String, Long> snapshot() {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (String table : TABLES) {
      counts.put(table, count("SELECT count(*) FROM " + table));
    }
    return counts;
  }

  private final List<String> unhandled = new java.util.ArrayList<>();

  /** Records a request that answered with a server error; other answers must not leak. */
  private void assertHandled(String what, Resp response) {
    if (response.status() >= 500) {
      unhandled.add(what.replace("\u0000", "\\u0000") + " -> " + response.status());
      return;
    }
    assertNoLeak(response);
  }

  @org.junit.jupiter.api.AfterEach
  void noServerErrors() {
    assertThat(unhandled).as("hostile inputs answered with a server error").isEmpty();
  }

  @Test
  void hostileStringsInAuthBodiesChangeNothing() {
    Map<String, Long> before = snapshot();
    for (String hostile : HOSTILE) {
      ip = randomIp();
      String hostileEmail = hostile + "@example.org";
      assertHandled(
          "login email " + hostile,
          postJson(API + "/auth/login", Map.of("email", hostileEmail, "password", hostile)));
      assertHandled(
          "login password " + hostile,
          postJson(API + "/auth/login", Map.of("email", uniqueEmail(), "password", hostile)));
      assertHandled(
          "register " + hostile,
          postJson(
              API + "/auth/register",
              Map.of("email", hostileEmail, "password", hostile, "locale", hostile)));
      assertHandled(
          "register country " + hostile,
          postJson(
              API + "/auth/register",
              Map.of("email", hostileEmail, "password", PASSWORD, "countryCode", hostile)));
      assertHandled(
          "forgot " + hostile,
          postJson(API + "/auth/forgot-password", Map.of("email", hostileEmail)));
      assertHandled(
          "resend " + hostile,
          postJson(API + "/auth/resend-verification", Map.of("email", hostileEmail)));
      assertHandled(
          "verify " + hostile, postJson(API + "/auth/verify-email", Map.of("token", hostile)));
      assertHandled(
          "reset " + hostile,
          postJson(API + "/auth/reset-password", Map.of("token", hostile, "newPassword", hostile)));
    }
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void hostileStringsInProfileSettingsAndPromoCodesChangeNothing() {
    String email = uniqueEmail();
    registerVerified(email);
    Session session = login(email, PASSWORD);
    Map<String, Long> before = snapshot();
    Map<String, Object> profileBefore =
        jdbc.sql(
                "SELECT locale, display_currency, default_country_code, email_alerts_enabled"
                    + " FROM app_user WHERE email = CAST(? AS citext)")
            .param(email)
            .query()
            .singleRow();
    for (String hostile : HOSTILE) {
      for (String field : List.of("displayCurrency", "locale", "defaultCountryCode")) {
        assertHandled(
            field + " " + hostile,
            req("PATCH", API + "/me")
                .bearer(session.accessToken())
                .json(Map.of(field, hostile))
                .send());
      }
      assertHandled(
          "emailAlertsEnabled " + hostile,
          req("PATCH", API + "/me")
              .bearer(session.accessToken())
              .json(Map.of("emailAlertsEnabled", hostile))
              .send());
      ip = randomIp();
      Resp promo =
          req("POST", API + "/me/subscription/promo")
              .bearer(session.accessToken())
              .json(Map.of("code", hostile))
              .send();
      assertHandled("promo " + hostile, promo);
    }
    assertThat(snapshot()).isEqualTo(before);
    assertThat(
            jdbc.sql(
                    "SELECT locale, display_currency, default_country_code, email_alerts_enabled"
                        + " FROM app_user WHERE email = CAST(? AS citext)")
                .param(email)
                .query()
                .singleRow())
        .isEqualTo(profileBefore);
  }

  @Test
  void hostileAdminSearchAndPathVariablesChangeNothingAndMatchLiterally() {
    String admin = adminToken();
    Map<String, Long> before = snapshot();
    for (String hostile : HOSTILE) {
      String encoded = URLEncoder.encode(hostile, StandardCharsets.UTF_8).replace("+", "%20");
      Resp search = get(API + "/admin/users?q=" + encoded, admin);
      assertHandled("q " + hostile, search);
      if (search.status() == 200) {
        long literal =
            count(
                "SELECT count(*) FROM app_user WHERE strpos(lower(email::text), lower(?)) > 0",
                hostile);
        assertThat(search.json().get("meta").get("total").asLong())
            .as("q=%s matches literally", hostile)
            .isEqualTo(literal);
        assertThat(search.body()).doesNotContain("password").doesNotContain("$argon2");
      }
      assertHandled(
          "delete promo " + hostile,
          req("DELETE", API + "/admin/promo-codes/" + encoded).bearer(admin).send());
      assertHandled(
          "status " + hostile,
          req("POST", API + "/admin/users/" + encoded + "/status")
              .bearer(admin)
              .json(Map.of("status", "DISABLED"))
              .send());
      assertHandled(
          "promo prefix " + hostile,
          req("POST", API + "/admin/promo-codes")
              .bearer(admin)
              .json(
                  Map.of(
                      "prefix",
                      hostile,
                      "planCode",
                      "PRO",
                      "durationDays",
                      30,
                      "maxRedemptions",
                      1))
              .send());
      assertHandled(
          "plan code " + hostile,
          req("POST", API + "/admin/promo-codes")
              .bearer(admin)
              .json(Map.of("planCode", hostile, "durationDays", 30, "maxRedemptions", 1))
              .send());
    }
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void likeWildcardsInAdminSearchAreLiteral() {
    String admin = adminToken();
    for (String wildcard : List.of("%", "_", "%25", "\\")) {
      String encoded = URLEncoder.encode(wildcard, StandardCharsets.UTF_8);
      Resp search = get(API + "/admin/users?q=" + encoded, admin);
      assertThat(search.status()).as(search.toString()).isEqualTo(200);
      String decoded = java.net.URLDecoder.decode(encoded, StandardCharsets.UTF_8);
      long literal =
          count("SELECT count(*) FROM app_user WHERE strpos(email::text, ?) > 0", decoded);
      assertThat(search.json().get("meta").get("total").asLong())
          .as("q=%s", wildcard)
          .isEqualTo(literal);
    }
  }
}
