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

  /**
   * An e-mail address with its {@code @} plain, URL-encoded ({@code %40}), double-encoded ({@code
   * %2540}) or as a JSON escape ({@code \u005Cu0040}); the local part may itself be encoded.
   */
  private static final Pattern EMAIL =
      Pattern.compile(
          "([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*(?:@|%40|%2540|\\\\u0040)"
              + "([A-Za-z0-9.-]+\\.[A-Za-z]{2,})");

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
