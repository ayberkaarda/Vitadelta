package com.vitadelta.api.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LogRedactorTest {

  private static final String JWT =
      "eyJraWQiOiJhYmMiLCJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMiLCJyb2xlIjoiVVNFUiJ9.c2lnbmF0dXJlLXZhbHVl";

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "Authorization: Bearer abc.def.ghi|Authorization: [REDACTED]",
        "authorization=Basic dXNlcjpwYXNz|authorization=[REDACTED]",
        "{\"Authorization\":\"Bearer xyz\"}|{\"Authorization\":\"[REDACTED]\"}",
        "token Bearer sometoken123 used|token Bearer [REDACTED] used",
        "Cookie: vd_refresh=r3fr3sh; vd_anon=4n0n.sig|Cookie: vd_refresh=[REDACTED]; vd_anon=[REDACTED]",
        "{\"password\":\"hunter2 secret\",\"x\":1}|{\"password\":\"[REDACTED]\",\"x\":1}",
        "newPassword=Sup3rS3cret&email=x|newPassword=[REDACTED]&email=x",
        "{'currentPassword': 'abc'}|{'currentPassword': '[REDACTED]'}",
        "/auth/verify#token=abcdEFGH123|/auth/verify#token=[REDACTED]",
        "{\"accessToken\":\"tok\",\"refreshToken\":\"tok2\"}|{\"accessToken\":\"[REDACTED]\",\"refreshToken\":\"[REDACTED]\"}",
        "user alice.smith@example.com logged in|user a***@example.com logged in",
        "nothing sensitive here|nothing sensitive here"
      })
  void redactsSensitiveValues(String input, String expected) {
    assertThat(LogRedactor.redact(input)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "Securing GET /api/v1/admin/users?q=adv.x1%40example.org|"
            + "Securing GET /api/v1/admin/users?q=a***@example.org",
        "GET /api/v1/admin/users?q=ADV.X1%40EXAMPLE.ORG&page=2|"
            + "GET /api/v1/admin/users?q=A***@EXAMPLE.ORG&page=2",
        "q=Adv.X1%2540Example.Org|q=A***@Example.Org",
        "q=adv.x1%2b1%40example.org|q=a***@example.org",
        "url /users?email=adv%40mail.example.co.uk#top|url /users?email=a***@mail.example.co.uk#top",
        "json {\"email\":\"adv\\u0040example.org\"}|json {\"email\":\"a***@example.org\"}",
        "50%40 off at the store|50%40 off at the store"
      })
  void masksEncodedEmailAddressesInQueryStringsAndText(String input, String expected) {
    assertThat(LogRedactor.redact(input)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "JSON parse error: Unrecognized token 'hunter2secret': was expecting (JSON String)|"
            + "JSON parse error: Unrecognized token '[REDACTED]': was expecting (JSON String)",
        "Cannot deserialize value of type `Plan` from String \"PROMO-ABC\": not one of the values|"
            + "Cannot deserialize value of type `Plan` from String \"[REDACTED]\": not one of the values"
      })
  void redactsParserExcerptsOfRequestBodies(String input, String expected) {
    assertThat(LogRedactor.redact(input)).isEqualTo(expected);
  }

  @Test
  void redactsBareJsonWebTokens() {
    assertThat(LogRedactor.redact("decoded " + JWT + " ok")).isEqualTo("decoded [REDACTED] ok");
  }

  @Test
  void longInputsWithoutAnAddressAreRedactedInLinearTime() {
    String[] inputs = {
      "a".repeat(200_000),
      "a.".repeat(100_000),
      "a@".repeat(100_000),
      "a@" + "b".repeat(200_000),
      "a@" + "b.".repeat(100_000),
      "/api/v1/" + "x".repeat(200_000) + "@"
    };
    for (String input : inputs) {
      long started = System.nanoTime();
      LogRedactor.redact(input);
      assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
          .as("input of %d chars", input.length())
          .isLessThan(java.time.Duration.ofSeconds(2));
    }
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "a@b.c@d.com|a@b***@d.com",
        "a@b.com@x.org|a***@b.com@x.org",
        "x@y.com-z|x***@y.com-z",
        "p@q.r.st u@v.io|p***@q.r.st u***@v.io",
        "@a.com bob@|@a.com bob@"
      })
  void emailMaskingKeepsItsShape(String input, String expected) {
    assertThat(LogRedactor.redact(input)).isEqualTo(expected);
  }

  @Test
  void keepsNullAndEmpty() {
    assertThat(LogRedactor.redact(null)).isNull();
    assertThat(LogRedactor.redact("")).isEmpty();
  }

  @Test
  void masksEmailAddresses() {
    assertThat(EmailMasking.mask("alice@example.com")).isEqualTo("a***@example.com");
    assertThat(EmailMasking.mask(" Bob@Example.org ")).isEqualTo("B***@Example.org");
    assertThat(EmailMasking.mask("no-at-sign")).isEqualTo("***");
    assertThat(EmailMasking.mask("@example.com")).isEqualTo("***");
    assertThat(EmailMasking.mask("bob@")).isEqualTo("***");
    assertThat(EmailMasking.mask(null)).isEqualTo("***");
  }
}
