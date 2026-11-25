package com.vitadelta.api.common.logging;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes credentials and personal data from log text (project specification sections 11.2 and
 * 15.4). Applied by the logging layer to every string value of every log event (message, stack
 * trace, MDC values), so it also covers exception messages and third-party log lines.
 *
 * <ul>
 *   <li>{@code Authorization} header values and {@code Bearer} tokens;
 *   <li>JSON Web Tokens anywhere in the text;
 *   <li>the values of the {@code vd_refresh} and {@code vd_anon} cookies;
 *   <li>values of password and token fields ({@code password}, {@code newPassword}, {@code
 *       currentPassword}, {@code token}, {@code accessToken}, {@code refreshToken}) in JSON, form
 *       or {@code key=value} notation, including {@code #token=} URL fragments;
 *   <li>e-mail addresses, masked as {@code a***@domain}.
 * </ul>
 */
public final class LogRedactor {

  /** Replacement of a removed value. */
  public static final String REDACTED = "[REDACTED]";

  private static final Pattern AUTHORIZATION =
      Pattern.compile(
          "(?i)(\\b(?:proxy-)?authorization[\"']?\\s*[:=]\\s*[\"']?)"
              + "(?:(?:bearer|basic|digest)\\s+)?[^\\s,;\"'\\]}]+");
  private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer)\\s+[A-Za-z0-9._~+/=-]+");
  private static final Pattern JWT =
      Pattern.compile("\\beyJ[A-Za-z0-9_-]*\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*");
  private static final Pattern COOKIES =
      Pattern.compile("(?i)\\b(vd_refresh|vd_anon)([\"']?\\s*[=:]\\s*[\"']?)[^\\s;,\"'&\\]}]+");
  private static final Pattern SECRET_FIELDS =
      Pattern.compile(
          "(?i)([\"']?\\b(?:password|newPassword|currentPassword|oldPassword|token|accessToken"
              + "|refreshToken|access_token|refresh_token)\\b[\"']?\\s*[:=]\\s*)"
              + "(\"(?:[^\"\\\\]|\\\\.)*\"|'[^']*'|[^\\s,;&\"'\\]}]+)");

  private LogRedactor() {}

  /** The text with every sensitive value replaced; {@code null} stays {@code null}. */
  public static String redact(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    String result = AUTHORIZATION.matcher(text).replaceAll("$1" + REDACTED);
    result = BEARER.matcher(result).replaceAll("$1 " + REDACTED);
    result = JWT.matcher(result).replaceAll(REDACTED);
    result = COOKIES.matcher(result).replaceAll("$1$2" + REDACTED);
    result = replaceSecretFields(result);
    return maskEmails(result);
  }

  private static boolean isLocal(char c) {
    return (c >= 'a' && c <= 'z')
        || (c >= 'A' && c <= 'Z')
        || (c >= '0' && c <= '9')
        || c == '.'
        || c == '_'
        || c == '%'
        || c == '+'
        || c == '-';
  }

  private static boolean isLetter(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
  }

  private static boolean isDomain(char c) {
    return (c >= 'a' && c <= 'z')
        || (c >= 'A' && c <= 'Z')
        || (c >= '0' && c <= '9')
        || c == '.'
        || c == '-';
  }

  /**
   * Masks {@code local@domain} addresses to {@code l***@domain}. A single left-to-right scan
   * anchored on each {@code @}: the work is linear in the text length (a regular expression over
   * the open-ended local part would rescan the text from every start position).
   */
  private static String maskEmails(String text) {
    int length = text.length();
    StringBuilder out = null;
    int copied = 0;
    int at = text.indexOf('@');
    while (at >= 0) {
      int start = at;
      while (start > copied && isLocal(text.charAt(start - 1))) {
        start--;
      }
      int domainEnd = at + 1;
      while (domainEnd < length && isDomain(text.charAt(domainEnd))) {
        domainEnd++;
      }
      int end = matchEnd(text, at + 1, domainEnd);
      if (start < at && end > 0) {
        if (out == null) {
          out = new StringBuilder(length);
        }
        out.append(text, copied, start).append(text.charAt(start)).append("***@");
        out.append(text, at + 1, end);
        copied = end;
        at = text.indexOf('@', end);
      } else {
        at = text.indexOf('@', at + 1);
      }
    }
    if (out == null) {
      return text;
    }
    return out.append(text, copied, length).toString();
  }

  /**
   * End of the domain {@code [A-Za-z0-9.-]+\.[A-Za-z]{2,}} that starts at {@code from} inside the
   * run {@code [from, runEnd)}, preferring the longest one; {@code -1} when there is none.
   */
  private static int matchEnd(String text, int from, int runEnd) {
    int dot = runEnd - 1;
    while (dot > from) {
      if (text.charAt(dot) == '.') {
        int letters = dot + 1;
        while (letters < runEnd && isLetter(text.charAt(letters))) {
          letters++;
        }
        if (letters - (dot + 1) >= 2) {
          return letters;
        }
      }
      dot--;
    }
    return -1;
  }

  private static String replaceSecretFields(String text) {
    Matcher matcher = SECRET_FIELDS.matcher(text);
    StringBuilder out = new StringBuilder();
    while (matcher.find()) {
      String value = matcher.group(2);
      String replacement =
          value.startsWith("\"")
              ? "\"" + REDACTED + "\""
              : value.startsWith("'") ? "'" + REDACTED + "'" : REDACTED;
      matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(1) + replacement));
    }
    matcher.appendTail(out);
    return out.toString();
  }
}
