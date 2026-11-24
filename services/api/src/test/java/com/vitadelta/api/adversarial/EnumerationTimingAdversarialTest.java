package com.vitadelta.api.adversarial;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.adversarial.AttackClient.Resp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Section 15.7 case 12: register, forgot-password and resend-verification answer byte-identically
 * (status, headers including {@code Set-Cookie}, body) for a known and an unknown address, with
 * SMTP up and with SMTP down; login with an unknown address costs the same as a wrong password.
 */
class EnumerationTimingAdversarialTest extends AdversarialIntegrationTest {

  private void assertIdentical(Resp known, Resp unknown) {
    assertThat(unknown.status()).isEqualTo(known.status());
    assertThat(unknown.comparableHeaders()).isEqualTo(known.comparableHeaders());
    assertThat(unknown.setCookies()).isEqualTo(known.setCookies());
    assertThat(unknown.body()).isEqualTo(known.body());
  }

  private Resp send(String path, Map<String, ?> body, String requestId, String anonCookie) {
    var request = req("POST", path).requestId(requestId).json(body);
    if (anonCookie != null) {
      request.cookie("vd_anon", anonCookie);
    }
    return request.send();
  }

  private void compareAll(String known, String unknown) {
    String anon = get(API + "/session/quota", null).cookieValue("vd_anon");
    for (String path :
        List.of(
            API + "/auth/register",
            API + "/auth/forgot-password",
            API + "/auth/resend-verification")) {
      Map<String, String> knownBody =
          path.endsWith("register")
              ? Map.of("email", known, "password", PASSWORD)
              : Map.of("email", known);
      Map<String, String> unknownBody =
          path.endsWith("register")
              ? Map.of("email", unknown, "password", PASSWORD)
              : Map.of("email", unknown);
      String requestId = "enum-" + UUID.randomUUID();
      ip = randomIp();
      Resp knownResponse = send(path, knownBody, requestId, null);
      ip = randomIp();
      Resp unknownResponse = send(path, unknownBody, requestId, null);
      assertThat(knownResponse.status()).as(path).isEqualTo(202);
      assertIdentical(knownResponse, unknownResponse);

      if (path.endsWith("register")) {
        ip = randomIp();
        Resp knownWithCookie = send(path, knownBody, requestId, anon);
        ip = randomIp();
        Resp unknownWithCookie =
            send(path, Map.of("email", uniqueEmail(), "password", PASSWORD), requestId, anon);
        assertIdentical(knownWithCookie, unknownWithCookie);
        assertThat(knownWithCookie.setCookie("vd_anon").orElseThrow()).contains("Max-Age=0");
      }
    }
  }

  @Test
  void knownAndUnknownAddressesAnswerIdenticallyWithSmtpUp() {
    String known = uniqueEmail();
    registerVerified(known);
    compareAll(known, uniqueEmail());
    compareAll(known.toUpperCase(Locale.ROOT), uniqueEmail().toUpperCase(Locale.ROOT));
  }

  @Test
  void knownAndUnknownAddressesAnswerIdenticallyWithSmtpDown() {
    String known = uniqueEmail();
    registerVerified(known);
    String unverified = uniqueEmail();
    register(unverified, PASSWORD);
    mail().failing(true);
    compareAll(known, uniqueEmail());
    compareAll(unverified, uniqueEmail());
  }

  @Test
  void registeringAnExistingAddressNeverCreatesASecondAccount() {
    String email = uniqueEmail();
    registerVerified(email);
    List<String> variants =
        List.of(
            email.toUpperCase(Locale.ROOT),
            Character.toUpperCase(email.charAt(0)) + email.substring(1),
            email.replace("@example.org", "@EXAMPLE.ORG"));
    for (String variant : variants) {
      ip = randomIp();
      Resp response =
          postJson(API + "/auth/register", Map.of("email", variant, "password", PASSWORD));
      assertThat(response.status()).as(variant).isIn(202, 422);
    }
    assertThat(count("SELECT count(*) FROM app_user WHERE lower(email::text) = lower(?)", email))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM app_user WHERE email_verified_at IS NULL AND"
                    + " lower(email::text) = lower(?)",
                email))
        .isZero();
  }

  private static long median(List<Long> values) {
    List<Long> sorted = new ArrayList<>(values);
    sorted.sort(null);
    int n = sorted.size();
    return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
  }

  private Resp attempt(String email, String password) {
    return http.req("POST", API + "/auth/login")
        .ip(randomIp())
        .json(Map.of("email", email, "password", password))
        .send();
  }

  /**
   * Medians over 30 interleaved attempts each, after a warm-up; every attempt uses a fresh client
   * IP, unknown attempts a fresh address, and wrong-password attempts rotate over four accounts so
   * that neither rate limit nor back-off answers early. A validation failure (no Argon2 at all) is
   * measured too, to show that the measurement can see the cost of a hash.
   */
  @Test
  void unknownLoginTakesAsLongAsAWrongPassword() {
    List<String> accounts = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      String email = uniqueEmail();
      registerVerified(email);
      accounts.add(email);
    }
    for (int i = 0; i < 6; i++) {
      attempt(uniqueEmail(), "wrong-password-value");
      attempt(accounts.get(i % 4), "wrong-password-value");
    }
    List<Long> unknown = new ArrayList<>();
    List<Long> wrong = new ArrayList<>();
    List<Long> invalid = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      Resp u = attempt(uniqueEmail(), "wrong-password-value");
      Resp w = attempt(accounts.get(i % 4), "wrong-password-value");
      Resp v = attempt("not-an-address", "wrong-password-value");
      assertThat(u.status()).isEqualTo(401);
      assertThat(w.status()).isEqualTo(401);
      assertThat(v.status()).isEqualTo(422);
      assertThat(u.body().replace(u.header("x-request-id"), ""))
          .isEqualTo(w.body().replace(w.header("x-request-id"), ""));
      unknown.add(u.nanos());
      wrong.add(w.nanos());
      invalid.add(v.nanos());
      if (i == 14) {
        clock.advance(java.time.Duration.ofMinutes(16));
      }
    }
    long unknownMedian = median(unknown);
    long wrongMedian = median(wrong);
    long invalidMedian = median(invalid);
    String summary =
        "medians ms: unknown=%.1f wrong=%.1f no-hash=%.1f"
            .formatted(unknownMedian / 1e6, wrongMedian / 1e6, invalidMedian / 1e6);
    assertThat(Math.abs(unknownMedian - wrongMedian))
        .as(summary)
        .isLessThanOrEqualTo((long) (wrongMedian * 0.20));
    assertThat(wrongMedian).as(summary).isGreaterThan(invalidMedian);
  }
}
