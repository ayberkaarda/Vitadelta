package com.vitadelta.api.common.logging;

/**
 * Masks e-mail addresses for logs (project specification section 11.2): {@code alice@example.com}
 * becomes {@code a***@example.com}. Use it whenever an e-mail address has to be mentioned in a log
 * line; the log redaction layer applies the same masking to any address it finds.
 */
public final class EmailMasking {

  private EmailMasking() {}

  /** The masked address; a value without {@code @} is fully masked as {@code ***}. */
  public static String mask(String email) {
    if (email == null) {
      return "***";
    }
    String value = email.strip();
    int at = value.lastIndexOf('@');
    if (at < 1 || at == value.length() - 1) {
      return "***";
    }
    return value.charAt(0) + "***" + value.substring(at);
  }
}
