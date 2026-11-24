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
  private static final Pattern EMAIL =
      Pattern.compile("([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})");

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
    result = EMAIL.matcher(result).replaceAll("$1***@$2");
    return result;
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
