package com.vitadelta.api.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.vitadelta.api.billing.StripeSignatureVerifier.Failure;
import com.vitadelta.api.billing.StripeSignatureVerifier.Rejected;
import com.vitadelta.api.support.TestEnvironment;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The {@code Stripe-Signature} scheme as Stripe documents it for manual verification. Expected
 * signatures are computed here independently of the verifier: HMAC-SHA256 keyed with the whole
 * {@code whsec_...} secret over {@code <t>.<raw body>}, lower-case hex.
 */
class StripeSignatureVerifierTest {

  static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");
  static final String SECRET = "whsec_" + TestEnvironment.randomHex(24);
  static final byte[] BODY =
      "{\"id\":\"evt_1Synthetic\",\"object\":\"event\",\"type\":\"invoice.paid\"}"
          .getBytes(StandardCharsets.UTF_8);

  private final StripeSignatureVerifier verifier =
      new StripeSignatureVerifier(SECRET, Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));

  /** The signature Stripe sends for {@code body} at {@code timestamp} under {@code secret}. */
  static String sign(String secret, long timestamp, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(mac.doFinal(body));
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  static String header(String secret, long timestamp, byte[] body) {
    return "t=" + timestamp + ",v1=" + sign(secret, timestamp, body);
  }

  private Failure failure(byte[] body, List<String> headers) {
    Rejected rejected = catchThrowableOfType(Rejected.class, () -> verifier.verify(body, headers));
    assertThat(rejected).as("the request is rejected").isNotNull();
    return rejected.failure();
  }

  private static long now() {
    return NOW.getEpochSecond();
  }

  @Test
  void aCorrectlySignedRecentBodyIsAccepted() {
    assertThatCode(() -> verifier.verify(BODY, List.of(header(SECRET, now(), BODY))))
        .doesNotThrowAnyException();
  }

  @Test
  void anyMatchingV1EntryIsEnoughAndOtherEntriesAreIgnored() {
    String otherSecret = "whsec_" + TestEnvironment.randomHex(24);
    String value =
        "t="
            + now()
            + ",v1="
            + sign(otherSecret, now(), BODY)
            + ",v1="
            + sign(SECRET, now(), BODY)
            + ",v0="
            + sign(SECRET, now(), BODY)
            + ",secret=whsec_****abcd,destination=we_1Synthetic,payload=a25aefc6";
    assertThatCode(() -> verifier.verify(BODY, List.of(value))).doesNotThrowAnyException();
  }

  @Test
  void aV0SignatureAloneIsNotAccepted() {
    String value = "t=" + now() + ",v0=" + sign(SECRET, now(), BODY);
    assertThat(failure(BODY, List.of(value))).isEqualTo(Failure.NO_SIGNATURE);
  }

  @Test
  void aTamperedBodyFails() {
    String value = header(SECRET, now(), BODY);
    byte[] tampered =
        new String(BODY, StandardCharsets.UTF_8)
            .replace("invoice.paid", "invoice.payment_failed")
            .getBytes(StandardCharsets.UTF_8);
    assertThat(failure(tampered, List.of(value))).isEqualTo(Failure.SIGNATURE_MISMATCH);
    byte[] reformatted =
        new String(BODY, StandardCharsets.UTF_8)
            .replace(",", ", ")
            .getBytes(StandardCharsets.UTF_8);
    assertThat(failure(reformatted, List.of(value))).isEqualTo(Failure.SIGNATURE_MISMATCH);
  }

  @Test
  void anotherSecretFails() {
    String value = header("whsec_" + TestEnvironment.randomHex(24), now(), BODY);
    assertThat(failure(BODY, List.of(value))).isEqualTo(Failure.SIGNATURE_MISMATCH);
  }

  @Test
  void aChangedTimestampInvalidatesTheSignature() {
    String signature = sign(SECRET, now() - 600, BODY);
    assertThat(failure(BODY, List.of("t=" + now() + ",v1=" + signature)))
        .isEqualTo(Failure.SIGNATURE_MISMATCH);
  }

  @Test
  void aReplayOutsideTheWindowFailsInBothDirections() {
    assertThat(failure(BODY, List.of(header(SECRET, now() - 301, BODY))))
        .isEqualTo(Failure.TIMESTAMP_OUT_OF_WINDOW);
    assertThat(failure(BODY, List.of(header(SECRET, now() + 301, BODY))))
        .isEqualTo(Failure.TIMESTAMP_OUT_OF_WINDOW);
    assertThatCode(() -> verifier.verify(BODY, List.of(header(SECRET, now() - 300, BODY))))
        .doesNotThrowAnyException();
    assertThatCode(() -> verifier.verify(BODY, List.of(header(SECRET, now() + 300, BODY))))
        .doesNotThrowAnyException();
  }

  @Test
  void aMissingOrRepeatedHeaderFails() {
    assertThat(failure(BODY, List.of())).isEqualTo(Failure.MISSING_HEADER);
    assertThat(failure(BODY, null)).isEqualTo(Failure.MISSING_HEADER);
    String value = header(SECRET, now(), BODY);
    assertThat(failure(BODY, List.of(value, value))).isEqualTo(Failure.DUPLICATE_HEADER);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        " ",
        "garbage",
        "t=,v1=00",
        "t=abc,v1=00",
        "t=-5,v1=00",
        "=1,v1=00",
        "v1=00",
        "t=1,t=1,v1=00",
        "t=1,,v1=00",
        "t=1;v1=00",
        "t=1234567890123,v1=00"
      })
  void aMalformedHeaderFails(String value) {
    assertThat(failure(BODY, List.of(value))).isEqualTo(Failure.MALFORMED_HEADER);
  }

  @Test
  void aRepeatedTimestampFailsEvenWithAValidSignature() {
    String value = header(SECRET, now(), BODY) + ",t=" + now();
    assertThat(failure(BODY, List.of(value))).isEqualTo(Failure.MALFORMED_HEADER);
  }

  @Test
  void anOversizedHeaderOrTooManySignaturesFail() {
    String longValue = header(SECRET, now(), BODY) + ",x=" + "a".repeat(2048);
    assertThat(failure(BODY, List.of(longValue))).isEqualTo(Failure.MALFORMED_HEADER);
    StringBuilder many = new StringBuilder("t=" + now());
    for (int i = 0; i <= StripeSignatureVerifier.MAX_SIGNATURES; i++) {
      many.append(",v1=").append("0".repeat(64));
    }
    assertThat(failure(BODY, List.of(many.toString()))).isEqualTo(Failure.MALFORMED_HEADER);
  }

  @Test
  void aSignatureThatIsNotLowerCaseHexNeverMatches() {
    String upper = sign(SECRET, now(), BODY).toUpperCase(java.util.Locale.ROOT);
    assertThat(failure(BODY, List.of("t=" + now() + ",v1=" + upper)))
        .isEqualTo(Failure.SIGNATURE_MISMATCH);
    assertThat(failure(BODY, List.of("t=" + now() + ",v1=zz")))
        .isEqualTo(Failure.SIGNATURE_MISMATCH);
  }

  @Test
  void theSecretAndToleranceAreChecked() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new StripeSignatureVerifier("", Duration.ofMinutes(5), Clock.systemUTC()))
        .isInstanceOf(IllegalArgumentException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new StripeSignatureVerifier(SECRET, Duration.ZERO, Clock.systemUTC()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(Failure.SIGNATURE_MISMATCH.tag()).isEqualTo("signature_mismatch");
  }
}
