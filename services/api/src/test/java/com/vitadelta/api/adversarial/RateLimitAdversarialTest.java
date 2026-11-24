package com.vitadelta.api.adversarial;

import static com.vitadelta.api.adversarial.AttackClient.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.adversarial.AttackClient.Resp;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Section 15.7 case 6 behind a trusted proxy: the per-IP login limit, a rotating left part of
 * {@code X-Forwarded-For} (only the right-most, proxy-added entry counts), the per-account back-off
 * for attempts spread over many source IPs and over spellings of the same address.
 */
class RateLimitAdversarialTest extends AdversarialIntegrationTest {

  private static void assertRateLimited(Resp response) {
    assertProblem(response, 429, "rate_limited");
    assertThat(Long.parseLong(response.header("retry-after"))).isPositive();
  }

  private Resp failedLogin(String sourceIp, String email) {
    return http.req("POST", API + "/auth/login")
        .ip(sourceIp)
        .json(Map.of("email", email, "password", "definitely-wrong-password"))
        .send();
  }

  @Test
  void loginIsLimitedPerClientIp() {
    String client = randomIp();
    for (int i = 0; i < 10; i++) {
      assertThat(failedLogin(client, uniqueEmail()).status()).isEqualTo(401);
    }
    assertRateLimited(failedLogin(client, uniqueEmail()));
  }

  @Test
  void spoofedLeftEntriesOfTheForwardedHeaderDoNotEscapeTheLimit() {
    String proxied = randomIp();
    for (int i = 0; i < 10; i++) {
      Resp response =
          failedLogin(randomIp() + ", 198.51.100." + (i + 1) + ", " + proxied, uniqueEmail());
      assertThat(response.status()).isEqualTo(401);
    }
    assertRateLimited(failedLogin("203.0.113.99, " + proxied, uniqueEmail()));
    Resp twoHeaders =
        http.req("POST", API + "/auth/login")
            .header("X-Forwarded-For", randomIp())
            .header("X-Forwarded-For", proxied)
            .json(Map.of("email", uniqueEmail(), "password", "definitely-wrong-password"))
            .send();
    assertRateLimited(twoHeaders);
  }

  @Test
  void elevenFailuresForOneAccountFromElevenIpsTriggerTheBackOff() {
    String email = uniqueEmail();
    registerVerified(email);
    for (int i = 0; i < 10; i++) {
      assertThat(failedLogin(randomIp(), email).status()).isEqualTo(401);
    }
    assertRateLimited(failedLogin(randomIp(), email));
    Resp correct =
        http.req("POST", API + "/auth/login")
            .ip(randomIp())
            .json(Map.of("email", email, "password", PASSWORD))
            .send();
    assertRateLimited(correct);
    assertThat(correct.setCookie("vd_refresh")).isEmpty();
  }

  @Test
  void backOffAppliesToUnknownAddressesAlike() {
    String unknown = uniqueEmail();
    for (int i = 0; i < 10; i++) {
      assertThat(failedLogin(randomIp(), unknown).status()).isEqualTo(401);
    }
    Resp blocked = failedLogin(randomIp(), unknown);
    assertRateLimited(blocked);
  }

  @Test
  void caseAndWhitespaceVariantsShareOneAccountBucket() {
    String email = uniqueEmail();
    registerVerified(email);
    List<String> spellings =
        List.of(
            email,
            email.toUpperCase(Locale.ROOT),
            " " + email,
            email + " ",
            Character.toUpperCase(email.charAt(0)) + email.substring(1));
    int accepted = 0;
    for (int i = 0; i < 10; i++) {
      Resp response = failedLogin(randomIp(), spellings.get(i % spellings.size()));
      if (response.status() == 401) {
        accepted++;
      } else {
        assertThat(response.status()).as(response.toString()).isEqualTo(422);
      }
    }
    for (int i = accepted; i < 10; i++) {
      assertThat(failedLogin(randomIp(), email.toUpperCase(Locale.ROOT)).status()).isEqualTo(401);
    }
    assertRateLimited(failedLogin(randomIp(), email.toUpperCase(Locale.ROOT)));
    assertRateLimited(failedLogin(randomIp(), email));
  }

  @Test
  void promoRedemptionIsLimitedPerIpAcrossUsers() {
    String client = randomIp();
    for (int i = 0; i < 10; i++) {
      String token = accessToken(insertUser(true), "USER", true);
      Resp response =
          http.req("POST", API + "/me/subscription/promo")
              .ip(client)
              .bearer(token)
              .json(Map.of("code", randomCode()))
              .send();
      assertProblem(response, 400, "promo_invalid");
    }
    String token = accessToken(insertUser(true), "USER", true);
    assertRateLimited(
        http.req("POST", API + "/me/subscription/promo")
            .ip(client)
            .bearer(token)
            .json(Map.of("code", randomCode()))
            .send());
  }
}
