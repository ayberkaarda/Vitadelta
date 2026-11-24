package com.vitadelta.api.common.csv;

import java.util.List;

/**
 * One CSV record.
 *
 * @param number 1-based record number, header included, empty lines not counted
 * @param line 1-based physical line on which the record starts
 */
public record CsvRecord(int number, int line, List<String> fields) {

  public int size() {
    return fields.size();
  }

  public String get(int index) {
    return fields.get(index);
  }
}
