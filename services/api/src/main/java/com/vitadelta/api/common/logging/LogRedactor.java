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
 *   <li>request-body text quoted by JSON parser and binding messages ({@code Unrecognized token
 *       '...'}, {@code from String "..."}); the JSON converter already drops these excerpts, this
 *       is the second line of defence;
 *   <li>e-mail addresses, also URL-encoded ({@code %40}, {@code %2540}) in paths and query strings,
 *       masked as {@code a***@domain}.
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

  /** Input text quoted by JSON parser messages ({@code Unrecognized token 'value'}). */
  private static final Pattern PARSER_TOKEN = Pattern.compile("(Unrecognized token ')[^']*(')");

  /** String input quoted by JSON binding messages ({@code from String "value"}). */
  private static final Pattern PARSER_STRING =
      Pattern.compile("(from String |String value \\()\"(?:[^\"\\\\]|\\\\.)*\"");

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
    result = PARSER_TOKEN.matcher(result).replaceAll("$1" + REDACTED + "$2");
    result = PARSER_STRING.matcher(result).replaceAll("$1\"" + REDACTED + "\"");
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

  /** Length of the {@code @} form that starts at {@code i} (plain or encoded), or 0. */
  private static int markerLength(String text, int i) {
    char c = text.charAt(i);
    if (c == '@') {
      return 1;
    }
    if (c == '%') {
      if (text.startsWith("%2540", i)) {
        return 5;
      }
      return text.startsWith("%40", i) ? 3 : 0;
    }
    return c == '\\' && text.startsWith("\\u0040", i) ? 6 : 0;
  }

  /**
   * Masks {@code local@domain} addresses to {@code l***@domain}; the {@code @} may be plain,
   * URL-encoded ({@code %40}), double-encoded ({@code %2540}) or a JSON escape ({@code
   * \u005Cu0040}). A single left-to-right scan anchored on each marker: the work is linear in the
   * text length (a regular expression over the open-ended local part would rescan the text from
   * every start position). The domain is matched first, so the local part is only walked back for a
   * real match.
   */
  private static String maskEmails(String text) {
    int length = text.length();
    StringBuilder out = null;
    int copied = 0;
    int i = 0;
    while (i < length) {
      int marker = markerLength(text, i);
      if (marker == 0) {
        i++;
        continue;
      }
      int domainStart = i + marker;
      int domainEnd = domainStart;
      while (domainEnd < length && isDomain(text.charAt(domainEnd))) {
        domainEnd++;
      }
      int end = matchEnd(text, domainStart, domainEnd);
      int start = i;
      if (end > 0) {
        while (start > copied && isLocal(text.charAt(start - 1))) {
          start--;
        }
      }
      if (end > 0 && start < i) {
        if (out == null) {
          out = new StringBuilder(length);
        }
        out.append(text, copied, start).append(text.charAt(start)).append("***@");
        out.append(text, domainStart, end);
        copied = end;
        i = end;
      } else {
        i += marker;
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
