package com.vitadelta.api.common.csv;

/** The text has more records than the caller accepts; parsing stopped at the first extra one. */
public class CsvLimitException extends CsvFormatException {

  private static final long serialVersionUID = 1L;

  private final int maxRecords;

  public CsvLimitException(int line, int maxRecords) {
    super(line, "more than " + maxRecords + " records");
    this.maxRecords = maxRecords;
  }

  public int maxRecords() {
    return maxRecords;
  }
}
