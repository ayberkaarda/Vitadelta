package com.vitadelta.api.common.logging;

/**
 * Makes untrusted text (for example a value taken from another service's answer) safe for a log
 * line: control characters are replaced by {@code ?} and the text is cut to {@link #MAX_LENGTH}
 * characters, so a hostile value can neither forge log lines nor flood the log.
 */
public final class LogText {

  /** Longest text written to a log line. */
  public static final int MAX_LENGTH = 64;

  private LogText() {}

  /** The printable, bounded form of {@code text}; {@code "null"} for {@code null}. */
  public static String printable(String text) {
    if (text == null) {
      return "null";
    }
    int length = Math.min(text.length(), MAX_LENGTH);
    StringBuilder result = new StringBuilder(length + 11);
    for (int i = 0; i < length; i++) {
      char c = text.charAt(i);
      result.append(Character.isISOControl(c) || Character.isSurrogate(c) ? '?' : c);
    }
    if (text.length() > MAX_LENGTH) {
      result.append("[truncated]");
    }
    return result.toString();
  }
}
