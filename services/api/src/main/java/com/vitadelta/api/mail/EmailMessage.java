package com.vitadelta.api.mail;

import com.vitadelta.api.common.logging.EmailMasking;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * A fully rendered e-mail, ready for an {@link EmailSender}.
 *
 * <p>Extra headers are limited to {@link #ALLOWED_HEADERS} (list headers of RFC 2369, RFC 2919 and
 * RFC 8058); names are matched without regard to case and stored in their canonical spelling.
 * Values must be single-line printable ASCII, so no header can be injected or folded through them.
 *
 * @param to recipient address
 * @param subject single-line subject
 * @param textBody plain-text part
 * @param htmlBody HTML part
 * @param locale language of the content
 * @param headers extra headers in their canonical spelling (unmodifiable, never {@code null})
 */
public record EmailMessage(
    String to,
    String subject,
    String textBody,
    String htmlBody,
    Locale locale,
    Map<String, String> headers) {

  /** Link to unsubscribe or to the notification preferences (RFC 2369). */
  public static final String LIST_UNSUBSCRIBE = "List-Unsubscribe";

  /** One-click unsubscription marker (RFC 8058). */
  public static final String LIST_UNSUBSCRIBE_POST = "List-Unsubscribe-Post";

  /** List identifier (RFC 2919). */
  public static final String LIST_ID = "List-Id";

  /** The only extra headers a message may carry. */
  public static final Set<String> ALLOWED_HEADERS =
      Set.of(LIST_UNSUBSCRIBE, LIST_UNSUBSCRIBE_POST, LIST_ID);

  /** Longest accepted header value (the RFC 5322 line limit minus the longest name). */
  static final int MAX_HEADER_VALUE_LENGTH = 900;

  public EmailMessage {
    if (to == null || to.isBlank() || containsLineBreak(to)) {
      throw new IllegalArgumentException("recipient must be a single non-blank address");
    }
    if (subject == null || subject.isBlank() || containsLineBreak(subject)) {
      throw new IllegalArgumentException("subject must be a single non-blank line");
    }
    Objects.requireNonNull(textBody, "textBody");
    Objects.requireNonNull(htmlBody, "htmlBody");
    Objects.requireNonNull(locale, "locale");
    to = to.strip();
    headers = validatedHeaders(headers);
  }

  /** A message without extra headers. */
  public EmailMessage(String to, String subject, String textBody, String htmlBody, Locale locale) {
    this(to, subject, textBody, htmlBody, locale, Map.of());
  }

  /**
   * A copy of this message with {@code name} set to {@code value} (replacing an earlier value).
   *
   * @throws IllegalArgumentException when the name is not allowed or the value is not a single line
   *     of printable ASCII
   */
  public EmailMessage withHeader(String name, String value) {
    Map<String, String> copy = new LinkedHashMap<>(headers);
    copy.remove(canonicalName(name));
    copy.put(name, value);
    return new EmailMessage(to, subject, textBody, htmlBody, locale, copy);
  }

  private static Map<String, String> validatedHeaders(Map<String, String> headers) {
    if (headers == null || headers.isEmpty()) {
      return Map.of();
    }
    Map<String, String> result = new TreeMap<>();
    headers.forEach((name, value) -> {
      String canonical = canonicalName(name);
      if (value == null || value.isBlank() || !isPrintableAscii(value)) {
        throw new IllegalArgumentException(
            "header " + canonical + " must be a single line of printable ASCII");
      }
      if (value.length() > MAX_HEADER_VALUE_LENGTH) {
        throw new IllegalArgumentException("header " + canonical + " is too long");
      }
      if (result.put(canonical, value.strip()) != null) {
        throw new IllegalArgumentException("header " + canonical + " is given twice");
      }
    });
    return Collections.unmodifiableMap(result);
  }

  private static String canonicalName(String name) {
    Optional<String> allowed =
        name == null
            ? Optional.empty()
            : ALLOWED_HEADERS.stream()
                .filter(candidate -> candidate.equalsIgnoreCase(name))
                .findFirst();
    return allowed.orElseThrow(() -> new IllegalArgumentException("header is not allowed"));
  }

  private static boolean isPrintableAscii(String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < 0x20 || c > 0x7E) {
        return false;
      }
    }
    return true;
  }

  private static boolean containsLineBreak(String value) {
    return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
  }

  /** Never prints the bodies (they may carry tokens) or header values; masks the recipient. */
  @Override
  public String toString() {
    return "EmailMessage[to="
        + EmailMasking.mask(to)
        + ", subject="
        + subject
        + ", locale="
        + locale.toLanguageTag()
        + ", headers="
        + headers.keySet()
        + "]";
  }
}
