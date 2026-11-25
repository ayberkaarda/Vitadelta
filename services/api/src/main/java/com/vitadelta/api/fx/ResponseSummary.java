package com.vitadelta.api.fx;

import java.nio.charset.StandardCharsets;

/**
 * Bounded, sanitised excerpt of a provider response for log lines: at most {@link #MAX_CHARS}
 * characters of the body, every character outside printable ASCII replaced by {@code ?}, so that a
 * hostile or broken body can neither flood the log nor inject line breaks or control sequences.
 */
final class ResponseSummary {

  /** Longest excerpt of a body. */
  static final int MAX_CHARS = 120;

  private ResponseSummary() {}

  /** {@code "<n> bytes: <excerpt>"} of {@code body}. */
  static String of(byte[] body) {
    if (body == null || body.length == 0) {
      return "0 bytes";
    }
    int length = Math.min(body.length, MAX_CHARS);
    String text = new String(body, 0, length, StandardCharsets.UTF_8);
    StringBuilder excerpt = new StringBuilder(text.length());
    text.codePoints()
        .limit(MAX_CHARS)
        .forEach(c -> excerpt.append(c >= 0x20 && c < 0x7f ? (char) c : '?'));
    return body.length + " bytes: " + excerpt + (body.length > length ? "[truncated]" : "");
  }
}
