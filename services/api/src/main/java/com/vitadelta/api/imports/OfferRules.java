package com.vitadelta.api.imports;

import java.math.BigDecimal;
import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Offer validation rules shared by the products document and the offers CSV. */
public final class OfferRules {

  public static final int MAX_URL_LENGTH = 2048;
  public static final int MAX_RETAILER_LENGTH = 100;
  public static final BigDecimal MAX_PRICE = new BigDecimal("9999999999.99");

  public static final Pattern PRODUCT_CODE = Pattern.compile("^[A-Z0-9][A-Z0-9-]{1,39}$");
  public static final Pattern COUNTRY_CODE = Pattern.compile("^[A-Z]{2}$");
  public static final Pattern CURRENCY_CODE = Pattern.compile("^[A-Z]{3}$");

  private static final Pattern URL_PARTS =
      Pattern.compile("^([Hh][Tt][Tt][Pp][Ss]?)://([^\\s/?#]+)([/?#]\\S*)?$");
  private static final Pattern HOST_NAME =
      Pattern.compile(
          "^[A-Za-z0-9_](?:[A-Za-z0-9_-]*[A-Za-z0-9_])?"
              + "(?:\\.[A-Za-z0-9_](?:[A-Za-z0-9_-]*[A-Za-z0-9_])?)*\\.?$");
  private static final Pattern IPV6_LITERAL =
      Pattern.compile("^\\[[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*\\]$");
  private static final Pattern PORT = Pattern.compile("^[0-9]{1,5}$");
  private static final Pattern PLAIN_DECIMAL = Pattern.compile("^[0-9]+(?:\\.[0-9]+)?$");

  private OfferRules() {}

  /**
   * Whether {@code url} is an absolute {@code http} or {@code https} URI of at most 2048 characters
   * with a non-empty host: a host name (internationalized names are checked in their ASCII form),
   * an IPv4 address or a bracketed IPv6 literal, an optional userinfo and an optional port up to
   * 65535. Userinfo or a port alone (for example {@code https://user@:443/}) is not a host.
   */
  public static boolean isValidUrl(String url) {
    if (url == null || url.length() > MAX_URL_LENGTH) {
      return false;
    }
    Matcher parts = URL_PARTS.matcher(url);
    if (!parts.matches()) {
      return false;
    }
    String authority = parts.group(2);
    String rest = parts.group(3) == null ? "" : parts.group(3);
    int at = authority.indexOf('@');
    if (at != authority.lastIndexOf('@')) {
      return false;
    }
    String userinfo = at >= 0 ? authority.substring(0, at + 1) : "";
    String hostAndPort = authority.substring(at + 1);
    String host;
    String port;
    if (hostAndPort.startsWith("[")) {
      int close = hostAndPort.indexOf(']');
      if (close < 0) {
        return false;
      }
      host = hostAndPort.substring(0, close + 1);
      String afterHost = hostAndPort.substring(close + 1);
      if (!afterHost.isEmpty() && !afterHost.startsWith(":")) {
        return false;
      }
      port = afterHost.isEmpty() ? "" : afterHost.substring(1);
      if (!IPV6_LITERAL.matcher(host).matches()) {
        return false;
      }
    } else {
      int colon = hostAndPort.lastIndexOf(':');
      host = colon >= 0 ? hostAndPort.substring(0, colon) : hostAndPort;
      port = colon >= 0 ? hostAndPort.substring(colon + 1) : "";
      if (host.isEmpty()) {
        return false;
      }
      try {
        host = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED);
      } catch (IllegalArgumentException e) {
        return false;
      }
      if (!HOST_NAME.matcher(host).matches()) {
        return false;
      }
    }
    if (!port.isEmpty() && (!PORT.matcher(port).matches() || Integer.parseInt(port) > 65535)) {
      return false;
    }
    try {
      new URI(parts.group(1) + "://" + userinfo + host + (port.isEmpty() ? "" : ":" + port) + rest);
      return true;
    } catch (URISyntaxException e) {
      return false;
    }
  }

  /** Whether a retailer name has 1 to 100 characters without leading or trailing whitespace. */
  public static boolean isValidRetailer(String retailer) {
    return retailer != null
        && !retailer.isEmpty()
        && retailer.length() <= MAX_RETAILER_LENGTH
        && retailer.strip().equals(retailer);
  }

  /** Whether a price is {@code > 0}, at most 9999999999.99 and has at most 2 decimals. */
  public static boolean isValidPrice(BigDecimal price) {
    return price != null
        && price.signum() > 0
        && price.compareTo(MAX_PRICE) <= 0
        && price.stripTrailingZeros().scale() <= 2;
  }

  /** Parses a plain decimal price ({@code .} separator, no sign, no exponent). */
  public static Optional<BigDecimal> parsePrice(String text) {
    if (text == null || !PLAIN_DECIMAL.matcher(text).matches()) {
      return Optional.empty();
    }
    BigDecimal price = new BigDecimal(text);
    return isValidPrice(price) ? Optional.of(price) : Optional.empty();
  }

  /** Parses an RFC 3339 date-time with {@code Z} or a numeric offset into an instant. */
  public static Optional<Instant> parseCheckedAt(String text) {
    if (text == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME))
          .map(OffsetDateTime::toInstant);
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }
}
