package com.vitadelta.api.common.csv;

/** The text is not valid CSV in the supported dialect. */
public class CsvFormatException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int line;

  public CsvFormatException(int line, String message) {
    super("line " + line + ": " + message);
    this.line = line;
  }

  public int line() {
    return line;
  }
}
