package com.vitadelta.api.adversarial;

import static com.vitadelta.api.adversarial.AttackClient.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.adversarial.AttackClient.Resp;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Section 15.7, Stripe webhook authenticity: only a body signed with the endpoint secret within the
 * tolerance window is ever parsed or applied. Every forged, tampered, replayed or malformed
 * delivery gets the same generic {@code 400}, reads nothing from Stripe and changes nothing;
 * transport games (content types, charsets, chunked and oversized bodies, forwarded addresses)
 * never bypass the signature, the body limit or the rate cap.
 */
class StripeWebhookSignatureAdversarialTest extends StripeAdversarialTest {

  /** A FREE user and a checkout event that would make them PRO if it were applied. */
  private record Target(Customer user, String subscription, String body, byte[] bytes) {}

  private Target target() {
    Customer user = freeUser();
    String subscription = stripeId("sub_");
    String customer = stripeId("cus_");
    stub(subscription(subscription, customer, "active", user.id(), end(30), "paid"));
    String body = checkoutCompleted(stripeId("evt_"), user.id(), subscription, customer);
    return new Target(user, subscription, body, body.getBytes(StandardCharsets.UTF_8));
  }

  private void assertUntouched(Target target) {
    assertThat(plan(target.user().id())).isEqualTo("FREE");
    assertThat(billingRows(target.user().id())).isZero();
    assertThat(stripeReads(target.subscription()))
        .as("Stripe must not be read for a rejected delivery")
        .isZero();
  }

  private static long stripeReads(String subscription) {
    return stripeRequests().stream()
        .filter(request -> request.getUrl().startsWith("/v1/subscriptions/" + subscription))
        .count();
  }

  private Resp withSignature(byte[] body, String... signatureHeaders) {
    AttackClient.Req request = webhookRequest(body, "application/json; charset=utf-8");
    for (String header : signatureHeaders) {
      request.header("Stripe-Signature", header);
    }
    return request.requestId("stripe-forgery").send();
  }

  @Test
  void forgedSignaturesAllGetTheSameGenericRejection() {
    Target target = target();
    byte[] body = target.bytes();
    long t = now();
    String valid = hmac(WEBHOOK_SECRET, t, body);
    String otherSecret = "whsec_" + randomSecret(24);

    Map<String, Supplier<Resp>> forgeries = new LinkedHashMap<>();
    forgeries.put("no header", () -> withSignature(body));
    forgeries.put("empty header", () -> withSignature(body, ""));
    forgeries.put(
        "another endpoint's secret",
        () -> withSignature(body, signatureHeader(otherSecret, t, body)));
    forgeries.put(
        "the API key used as the secret", () -> withSignature(body, signatureHeader(KEY, t, body)));
    forgeries.put(
        "the secret without its prefix",
        () -> withSignature(body, signatureHeader(WEBHOOK_SECRET.substring(6), t, body)));
    forgeries.put(
        "signed over the body only",
        () -> withSignature(body, "t=" + t + ",v1=" + hmacWithoutTimestamp(WEBHOOK_SECRET, body)));
    forgeries.put(
        "timestamp moved after signing",
        () -> withSignature(body, "t=" + (t + 1) + ",v1=" + valid));
    forgeries.put(
        "upper-case hex",
        () -> withSignature(body, "t=" + t + ",v1=" + valid.toUpperCase(java.util.Locale.ROOT)));
    forgeries.put(
        "truncated", () -> withSignature(body, "t=" + t + ",v1=" + valid.substring(0, 32)));
    forgeries.put("v0 scheme", () -> withSignature(body, "t=" + t + ",v0=" + valid));
    forgeries.put("scheme in upper case", () -> withSignature(body, "t=" + t + ",V1=" + valid));
    forgeries.put("blanks around entries", () -> withSignature(body, "t=" + t + ", v1=" + valid));
    forgeries.put("no timestamp", () -> withSignature(body, "v1=" + valid));
    forgeries.put(
        "two timestamps", () -> withSignature(body, "t=" + t + ",t=" + t + ",v1=" + valid));
    forgeries.put("negative timestamp", () -> withSignature(body, "t=-" + t + ",v1=" + valid));
    forgeries.put("fractional timestamp", () -> withSignature(body, "t=" + t + ".0,v1=" + valid));
    forgeries.put(
        "header repeated (valid twice)",
        () -> withSignature(body, "t=" + t + ",v1=" + valid, "t=" + t + ",v1=" + valid));
    forgeries.put(
        "header repeated (forged first)",
        () -> withSignature(body, "t=" + t + ",v1=" + "0".repeat(64), "t=" + t + ",v1=" + valid));
    forgeries.put(
        "two headers folded into one line",
        () -> withSignature(body, "t=" + t + ",v1=" + "0".repeat(64) + ",t=" + t + ",v1=" + valid));
    forgeries.put(
        "signature spraying beyond the rotation limit",
        () -> {
          StringBuilder header = new StringBuilder("t=" + t);
          for (int i = 0; i < 8; i++) {
            header.append(",v1=").append(String.format("%064x", i));
          }
          return withSignature(body, header.append(",v1=").append(valid).toString());
        });
    forgeries.put(
        "oversized header",
        () -> withSignature(body, "t=" + t + ",v1=" + valid + ",x=" + "a".repeat(4000)));
    forgeries.put(
        "tampered user",
        () ->
            withSignature(
                target
                    .body()
                    .replace(target.user().id().toString(), UUID.randomUUID().toString())
                    .getBytes(StandardCharsets.UTF_8),
                "t=" + t + ",v1=" + valid));
    forgeries.put(
        "re-serialized body (whitespace added)",
        () ->
            withSignature(
                target.body().replace(",", ", ").getBytes(StandardCharsets.UTF_8),
                "t=" + t + ",v1=" + valid));
    forgeries.put(
        "trailing newline appended",
        () ->
            withSignature(
                (target.body() + "\n").getBytes(StandardCharsets.UTF_8),
                "t=" + t + ",v1=" + valid));
    forgeries.put("empty body", () -> withSignature(new byte[0], "t=" + t + ",v1=" + valid));

    List<String> bodies = new ArrayList<>();
    forgeries.forEach((name, attempt) -> {
      Resp response = attempt.get();
      assertThat(response.status()).as(name + ": " + response).isEqualTo(400);
      assertGenericRejection(response);
      bodies.add(response.body());
    });
    assertThat(bodies).as("one rejection body for every forgery").containsOnly(bodies.get(0));
    assertUntouched(target);
  }

  private static String hmacWithoutTimestamp(String secret, byte[] body) {
    try {
      javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
      mac.init(
          new javax.crypto.spec.SecretKeySpec(
              secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return java.util.HexFormat.of().formatHex(mac.doFinal(body));
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void timestampsOutsideTheWindowAreRejectedInBothDirections() {
    Target target = target();
    byte[] body = target.bytes();
    for (long skew : List.of(-301L, -3600L, -86_400L * 30, 301L, 3600L, 86_400L * 365)) {
      Resp response = withSignature(body, signatureHeader(WEBHOOK_SECRET, now() + skew, body));
      assertThat(response.status()).as("skew %d s", skew).isEqualTo(400);
      assertGenericRejection(response);
    }
    Resp epoch = withSignature(body, signatureHeader(WEBHOOK_SECRET, 0, body));
    assertGenericRejection(epoch);
    Resp far = withSignature(body, signatureHeader(WEBHOOK_SECRET, 999_999_999_999L, body));
    assertGenericRejection(far);
    assertUntouched(target);
  }

  @Test
  void aCapturedDeliveryIsAppliedOnceInsideTheWindowAndRejectedAfterIt() {
    Target applied = target();
    String header = sign(applied.bytes());
    assertAck(withSignature(applied.bytes(), header));
    assertThat(plan(applied.user().id())).isEqualTo("PRO");
    assertThat(stripeReads(applied.subscription())).isEqualTo(1);
    long rows = count("SELECT count(*) FROM subscription WHERE user_id = ?", applied.user().id());

    // Replayed verbatim inside the window: acknowledged, never applied or read again.
    clock.advance(Duration.ofSeconds(120));
    assertAck(withSignature(applied.bytes(), header));
    assertThat(stripeReads(applied.subscription())).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM subscription WHERE user_id = ?", applied.user().id()))
        .isEqualTo(rows);

    // A delivery captured in transit and never seen by the API, replayed after the window.
    Target intercepted = target();
    String capturedHeader = sign(intercepted.bytes());
    clock.advance(Duration.ofSeconds(301));
    assertGenericRejection(withSignature(intercepted.bytes(), capturedHeader));
    assertUntouched(intercepted);
  }

  @Test
  void theSignatureCoversTheExactBytesWhateverTheDeclaredCharsetOrContentType() {
    // Non-ASCII content (Turkish letters, an emoji, a decomposed accent) signed as raw UTF-8 bytes
    // is accepted under any declared charset: the bytes are never decoded before verification.
    Customer user = freeUser();
    String subscription = stripeId("sub_");
    String customer = stripeId("cus_");
    stub(subscription(subscription, customer, "active", user.id(), end(30), "paid"));
    String body =
        checkoutCompleted(stripeId("evt_"), user.id(), subscription, customer)
            .replace(
                "\"payment_status\"",
                "\"custom_text\":\"Şişli çiçekçi ğüıöç 💊 é\",\"payment_status\"");
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    Resp accepted =
        webhookRequest(bytes, "application/json; charset=ISO-8859-1")
            .header("Stripe-Signature", sign(bytes))
            .send();
    assertAck(accepted);
    assertThat(plan(user.id())).isEqualTo("PRO");

    // The same text re-encoded (Latin-1 bytes, precomposed accent) no longer matches.
    Target target = target();
    String nonAscii =
        target.body().replace("\"payment_status\"", "\"custom_text\":\"é\",\"payment_status\"");
    String header = sign(nonAscii.getBytes(StandardCharsets.UTF_8));
    assertGenericRejection(
        withSignature(nonAscii.replace("é", "é").getBytes(StandardCharsets.UTF_8), header));
    assertGenericRejection(withSignature(nonAscii.getBytes(StandardCharsets.ISO_8859_1), header));

    // Content-type games with a forged signature are rejected like any other forgery.
    for (String contentType :
        List.of(
            "text/plain",
            "application/x-www-form-urlencoded",
            "multipart/form-data; boundary=x",
            "application/xml",
            "application/octet-stream")) {
      Resp response =
          webhookRequest(target.bytes(), contentType)
              .header("Stripe-Signature", "t=" + now() + ",v1=" + "0".repeat(64))
              .send();
      assertThat(response.status()).as(contentType).isIn(400, 415);
      assertNoBillingLeak(response);
    }
    Resp noContentType =
        webhookRequest(target.bytes(), null)
            .header("Stripe-Signature", "t=" + now() + ",v1=" + "0".repeat(64))
            .send();
    assertThat(noContentType.status()).isIn(400, 415);
    assertUntouched(target);
  }

  @Test
  void aSignedBodyThatIsNotAWellFormedEventIsRejectedNotGuessed() {
    Target target = target();
    String user = target.user().id().toString();
    String sub = target.subscription();
    List<byte[]> bodies =
        List.of(
            // Duplicate keys: a parser that keeps the first or the last value would disagree.
            target
                .body()
                .replace(
                    "\"type\":\"checkout.session.completed\"",
                    "\"type\":\"customer.created\",\"type\":\"checkout.session.completed\"")
                .getBytes(StandardCharsets.UTF_8),
            target
                .body()
                .replace(
                    "\"client_reference_id\":\"" + user + "\"",
                    "\"client_reference_id\":\""
                        + UUID.randomUUID()
                        + "\",\"client_reference_id\":\""
                        + user
                        + "\"")
                .getBytes(StandardCharsets.UTF_8),
            target
                .body()
                .replace("\"livemode\":false", "\"livemode\":false,\"livemode\":true")
                .getBytes(StandardCharsets.UTF_8),
            // Two concatenated documents.
            (target.body() + target.body()).getBytes(StandardCharsets.UTF_8),
            // Form encoding of the same fields.
            ("id=evt_1&type=checkout.session.completed&livemode=false&data[object][subscription]="
                    + sub)
                .getBytes(StandardCharsets.UTF_8),
            // Invalid UTF-8 inside a string.
            concat(
                target
                    .body()
                    .substring(0, target.body().length() - 2)
                    .getBytes(StandardCharsets.UTF_8),
                new byte[] {',', '"', 'x', '"', ':', '"', (byte) 0xC3, (byte) 0x28, '"', '}', '}'}),
            // Comments and single quotes (lenient JSON).
            target.body().replace("{\"id\"", "{/*x*/\"id\"").getBytes(StandardCharsets.UTF_8),
            target
                .body()
                .replace("\"livemode\":false", "'livemode':false")
                .getBytes(StandardCharsets.UTF_8),
            // Wrong types for the fields that select the handling.
            target
                .body()
                .replace("\"livemode\":false", "\"livemode\":\"false\"")
                .getBytes(StandardCharsets.UTF_8),
            target
                .body()
                .replace(
                    "\"type\":\"checkout.session.completed\"",
                    "\"type\":[\"checkout.session.completed\"]")
                .getBytes(StandardCharsets.UTF_8),
            target
                .body()
                .replace("\"id\":\"evt_", "\"id\":\"evt_/../")
                .getBytes(StandardCharsets.UTF_8),
            "[]".getBytes(StandardCharsets.UTF_8),
            "null".getBytes(StandardCharsets.UTF_8));
    for (byte[] body : bodies) {
      Resp response = withSignature(body, sign(body));
      assertThat(response.status()).as(new String(body, StandardCharsets.UTF_8)).isEqualTo(400);
      assertGenericRejection(response);
    }
    assertUntouched(target);
  }

  private static byte[] concat(byte[] a, byte[] b) {
    byte[] out = new byte[a.length + b.length];
    System.arraycopy(a, 0, out, 0, a.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }

  @Test
  void oversizedBodiesAreRefusedWithOrWithoutADeclaredLength() {
    Target target = target();
    String huge =
        target
            .body()
            .replace(
                "\"payment_status\"", "\"pad\":\"" + "x".repeat(300_000) + "\",\"payment_status\"");
    byte[] bytes = huge.getBytes(StandardCharsets.UTF_8);
    for (boolean chunked : List.of(false, true)) {
      AttackClient.Req request =
          webhookRequest(bytes, "application/json").header("Stripe-Signature", sign(bytes));
      Resp response = (chunked ? request.chunked() : request).send();
      assertProblem(response, 413, "validation_failed");
      assertNoBillingLeak(response);
    }
    assertUntouched(target);
  }

  @Test
  void theWebhookBodyLimitDoesNotLeakToNeighbouringRoutes() {
    Customer user = freeUser();
    String padded = "{\"pad\":\"" + "x".repeat(100_000) + "\"}";
    // 100 kB is accepted on the webhook only; the checkout route keeps the general 64 kB limit.
    Resp checkout = req("POST", CHECKOUT).bearer(user.token()).json(padded).send();
    assertProblem(checkout, 413, "validation_failed");
    for (String path :
        List.of(
            API + "/billing/webhooks/../checkout-session",
            API + "/billing/webhooks/stripe/../../checkout-session",
            API + "/billing/webhooks/%2e%2e/checkout-session",
            API + "/billing/webhooks/stripe;x=1/../../checkout-session")) {
      Resp response = req("POST", path).bearer(user.token()).json(padded).send();
      assertThat(response.status()).as(path + " " + response).isIn(400, 404, 413);
      assertNoBillingLeak(response);
    }
    assertThat(stripeRequests()).isEmpty();
  }

  @Test
  void forgedForwardedAddressesDoNotEscapeTheRateCap() {
    // The trusted proxy appends the real client address; whatever the client prepends is ignored.
    String realClient = randomIp();
    byte[] junk =
        event(stripeId("evt_"), "customer.created", false, "{}").getBytes(StandardCharsets.UTF_8);
    int accepted = 0;
    Resp limited = null;
    while (limited == null && accepted < 1000) {
      Resp response =
          http.req("POST", WEBHOOK)
              .header("X-Forwarded-For", randomIp() + ", " + randomIp() + ", " + realClient)
              .header("X-Real-IP", randomIp())
              .header("Forwarded", "for=" + randomIp())
              .header("Stripe-Signature", "t=1,v1=" + "0".repeat(64))
              .body(junk, "application/json")
              .send();
      if (response.status() == 429) {
        limited = response;
      } else {
        assertThat(response.status()).isEqualTo(400);
        accepted++;
      }
    }
    assertThat(limited).as("the per-address cap applies to the real client address").isNotNull();
    assertThat(accepted).isBetween(300, 999);
    assertProblem(limited, 429, "rate_limited");
    assertThat(limited.header("retry-after")).isNotBlank();

    // Even a correctly signed delivery from the capped address waits for the window.
    Resp signed =
        http.req("POST", WEBHOOK)
            .ip(realClient)
            .header("Stripe-Signature", sign(junk))
            .body(junk, "application/json")
            .send();
    assertProblem(signed, 429, "rate_limited");
  }
}
