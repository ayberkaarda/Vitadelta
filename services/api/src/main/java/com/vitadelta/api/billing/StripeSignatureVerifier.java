package com.vitadelta.api.billing;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies the {@code Stripe-Signature} header of a webhook request (ADR-0014), as Stripe documents
 * for manual verification:
 *
 * <ol>
 *   <li>exactly one header; its comma-separated entries are {@code key=value} pairs; {@code t} (the
 *       signing time in Unix seconds) appears exactly once; at least one {@code v1} entry; every
 *       other entry ({@code v0}, debug metadata) is ignored and never used for verification;
 *   <li>expected signature: HMAC-SHA256 keyed with the endpoint signing secret (the whole {@code
 *       whsec_...} string as UTF-8) over {@code <t>.<raw body bytes>};
 *   <li>every {@code v1} value is compared with it in constant time; one match is required;
 *   <li>the signing time must lie within the tolerance of the server clock, in both directions
 *       (Stripe's libraries check only the past; a future time is rejected here as well).
 * </ol>
 *
 * The body is never parsed before this check passes. The reason of a rejection is returned to the
 * caller for logging and metrics only; it is never sent to the client.
 */
public final class StripeSignatureVerifier {

  /** Longest accepted header value. */
  static final int MAX_HEADER_LENGTH = 2048;

  /** Most {@code v1} entries considered (one per active secret during a rotation). */
  static final int MAX_SIGNATURES = 8;

  private static final String ALGORITHM = "HmacSHA256";
  private static final Pattern TIMESTAMP = Pattern.compile("^[0-9]{1,12}$");
  private static final Pattern SIGNATURE = Pattern.compile("^[0-9a-f]{64}$");
  private static final HexFormat HEX = HexFormat.of();

  /** Why a request was rejected (logged and counted only). */
  public enum Failure {
    MISSING_HEADER,
    DUPLICATE_HEADER,
    MALFORMED_HEADER,
    NO_SIGNATURE,
    SIGNATURE_MISMATCH,
    TIMESTAMP_OUT_OF_WINDOW;

    /** Metric tag. */
    public String tag() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** A rejected request. */
  public static final class Rejected extends Exception {

    private static final long serialVersionUID = 1L;

    private final Failure failure;

    Rejected(Failure failure) {
      super(failure.tag(), null, false, false);
      this.failure = failure;
    }

    public Failure failure() {
      return failure;
    }
  }

  private final SecretKeySpec key;
  private final long toleranceSeconds;
  private final Clock clock;

  /**
   * @param signingSecret the endpoint signing secret ({@code whsec_...})
   * @param tolerance largest accepted distance between {@code t} and the clock
   */
  public StripeSignatureVerifier(String signingSecret, Duration tolerance, Clock clock) {
    Objects.requireNonNull(signingSecret, "signingSecret");
    if (signingSecret.isEmpty()) {
      throw new IllegalArgumentException("the signing secret is empty");
    }
    if (tolerance.isNegative() || tolerance.isZero()) {
      throw new IllegalArgumentException("the tolerance must be positive");
    }
    this.key = new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    this.toleranceSeconds = tolerance.toSeconds();
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Verifies {@code payload} against the values of every {@code Stripe-Signature} header of the
   * request.
   *
   * @param headers every value of the header (an empty list when it is absent)
   * @throws Rejected when the request is not authentic or not recent
   */
  public void verify(byte[] payload, List<String> headers) throws Rejected {
    if (headers == null || headers.isEmpty()) {
      throw new Rejected(Failure.MISSING_HEADER);
    }
    if (headers.size() > 1) {
      throw new Rejected(Failure.DUPLICATE_HEADER);
    }
    String header = headers.get(0);
    if (header == null || header.isBlank() || header.length() > MAX_HEADER_LENGTH) {
      throw new Rejected(Failure.MALFORMED_HEADER);
    }
    Long timestamp = null;
    List<String> signatures = new ArrayList<>();
    for (String element : header.split(",", -1)) {
      int separator = element.indexOf('=');
      if (separator <= 0) {
        throw new Rejected(Failure.MALFORMED_HEADER);
      }
      String name = element.substring(0, separator);
      String value = element.substring(separator + 1);
      if (name.equals("t")) {
        if (timestamp != null || !TIMESTAMP.matcher(value).matches()) {
          throw new Rejected(Failure.MALFORMED_HEADER);
        }
        timestamp = Long.parseLong(value);
      } else if (name.equals("v1")) {
        if (signatures.size() == MAX_SIGNATURES) {
          throw new Rejected(Failure.MALFORMED_HEADER);
        }
        signatures.add(value);
      }
    }
    if (timestamp == null) {
      throw new Rejected(Failure.MALFORMED_HEADER);
    }
    if (signatures.isEmpty()) {
      throw new Rejected(Failure.NO_SIGNATURE);
    }
    byte[] expected = sign(timestamp, payload == null ? new byte[0] : payload);
    boolean matched = false;
    for (String signature : signatures) {
      // Every entry is compared (no early exit); a value that is not 64 lower-case hex characters
      // can never match.
      byte[] candidate =
          SIGNATURE.matcher(signature).matches() ? HEX.parseHex(signature) : new byte[0];
      matched |= MessageDigest.isEqual(expected, candidate);
    }
    if (!matched) {
      throw new Rejected(Failure.SIGNATURE_MISMATCH);
    }
    long now = clock.instant().getEpochSecond();
    if (Math.abs(now - timestamp) > toleranceSeconds) {
      throw new Rejected(Failure.TIMESTAMP_OUT_OF_WINDOW);
    }
  }

  /** HMAC-SHA256 over {@code <timestamp>.<payload>}. */
  private byte[] sign(long timestamp, byte[] payload) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(key);
      mac.update(Long.toString(timestamp).getBytes(StandardCharsets.US_ASCII));
      mac.update((byte) '.');
      return mac.doFinal(payload);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HmacSHA256 is unavailable", e);
    }
  }
}
