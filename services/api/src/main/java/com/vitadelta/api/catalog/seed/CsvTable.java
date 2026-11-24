package com.vitadelta.api.catalog.seed;

import com.vitadelta.api.common.csv.CsvFormatException;
import com.vitadelta.api.common.csv.CsvParser;
import com.vitadelta.api.common.csv.CsvRecord;
import com.vitadelta.api.imports.ImportProblem;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** A reference CSV file with a fixed header, read into rows addressed by column name. */
final class CsvTable {

  /** One data row; {@link #number()} is the record number (the header is row 1). */
  record Row(int number, List<String> header, List<String> values) {

    String get(String column) {
      int index = header.indexOf(column);
      if (index < 0) {
        throw new IllegalArgumentException("unknown column " + column);
      }
      return values.get(index);
    }

    /** The value, or null when it is empty. */
    String optional(String column) {
      String value = get(column);
      return value.isEmpty() ? null : value;
    }
  }

  private CsvTable() {}

  /**
   * Parses {@code text} and checks the header; returns the data rows of the expected width.
   * Problems (header, parse errors, wrong field counts) are reported through {@code problems}.
   */
  static List<Row> parse(String text, List<String> header, Consumer<ImportProblem> problems) {
    List<CsvRecord> records;
    try {
      records = CsvParser.parse(text);
    } catch (CsvFormatException e) {
      problems.accept(ImportProblem.atRow(1, "invalid_csv", e.getMessage()));
      return List.of();
    }
    if (records.isEmpty() || !records.getFirst().fields().equals(header)) {
      problems.accept(
          ImportProblem.atRow(1, "invalid_header", "header must be " + String.join(",", header)));
      return List.of();
    }
    List<Row> rows = new ArrayList<>();
    for (CsvRecord record : records.subList(1, records.size())) {
      if (record.size() != header.size()) {
        problems.accept(
            ImportProblem.atRow(
                record.number(),
                "wrong_column_count",
                "expected " + header.size() + " fields, found " + record.size()));
      } else {
        rows.add(new Row(record.number(), header, record.fields()));
      }
    }
    return rows;
  }
}
