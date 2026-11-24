package com.vitadelta.api.common.web;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Optional;

/**
 * An IPv4 or IPv6 CIDR range such as {@code 172.30.80.0/24} or {@code fd00::/8}. A bare address is
 * a single-host range. Parsing never performs a DNS lookup.
 */
public final class IpRange {

  private final byte[] network;
  private final int prefixLength;
  private final String text;

  private IpRange(byte[] network, int prefixLength, String text) {
    this.network = network;
    this.prefixLength = prefixLength;
    this.text = text;
  }

  /**
   * Parses {@code address/prefix} or a bare address.
   *
   * @throws IllegalArgumentException when the text is not a CIDR range of literal addresses
   */
  public static IpRange parse(String cidr) {
    if (cidr == null || cidr.isBlank()) {
      throw new IllegalArgumentException("empty CIDR range");
    }
    String value = cidr.strip();
    int slash = value.indexOf('/');
    String address = slash < 0 ? value : value.substring(0, slash);
    byte[] bytes =
        parseLiteral(address)
            .orElseThrow(() -> new IllegalArgumentException("not an IP address: " + address))
            .getAddress();
    int maxPrefix = bytes.length * 8;
    int prefix;
    if (slash < 0) {
      prefix = maxPrefix;
    } else {
      String prefixText = value.substring(slash + 1);
      if (!prefixText.matches("[0-9]{1,3}")) {
        throw new IllegalArgumentException("invalid prefix length: " + value);
      }
      prefix = Integer.parseInt(prefixText);
      if (prefix > maxPrefix) {
        throw new IllegalArgumentException("prefix length too large: " + value);
      }
    }
    return new IpRange(mask(bytes, prefix), prefix, value);
  }

  /**
   * Parses a literal IPv4 or IPv6 address (IPv6 optionally in brackets) without any DNS lookup.
   *
   * @return the address, or empty when the text is not an address literal
   */
  public static Optional<InetAddress> parseLiteral(String text) {
    if (text == null) {
      return Optional.empty();
    }
    String value = text.strip();
    if (value.startsWith("[") && value.endsWith("]")) {
      value = value.substring(1, value.length() - 1);
    }
    if (value.isEmpty() || !value.matches("[0-9A-Fa-f:.%a-zA-Z]+")) {
      return Optional.empty();
    }
    try {
      return Optional.of(InetAddress.ofLiteral(value));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /** Whether {@code address} lies in this range (an IPv4 address never matches an IPv6 range). */
  public boolean contains(InetAddress address) {
    byte[] bytes = address.getAddress();
    if (bytes.length != network.length) {
      return false;
    }
    return Arrays.equals(mask(bytes, prefixLength), network);
  }

  private static byte[] mask(byte[] bytes, int prefix) {
    byte[] result = bytes.clone();
    for (int i = 0; i < result.length; i++) {
      int bitsInByte = Math.max(0, Math.min(8, prefix - i * 8));
      int byteMask = bitsInByte == 0 ? 0 : (0xFF << (8 - bitsInByte)) & 0xFF;
      result[i] = (byte) (result[i] & byteMask);
    }
    return result;
  }

  @Override
  public String toString() {
    return text;
  }
}
