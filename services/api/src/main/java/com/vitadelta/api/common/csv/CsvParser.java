package com.vitadelta.api.common.csv;

import java.util.ArrayList;
import java.util.List;

/**
 * RFC 4180 reader with the dialect of the project's CSV files: comma delimiter, {@code CRLF} or
 * {@code LF} line endings, optional final line break, double-quote quoting with {@code ""} as the
 * escaped quote, a leading byte order mark ignored, unquoted leading and trailing spaces and tabs
 * trimmed, and completely empty lines skipped (they are not counted as records).
 */
public final class CsvParser {

  private static final char BOM = '﻿';

  private CsvParser() {}

  /**
   * Parses the whole text.
   *
   * @return records in file order, numbered from 1 (the header is record 1)
   * @throws CsvFormatException on an unterminated quoted field or text after a closing quote
   */
  public static List<CsvRecord> parse(String text) {
    String input = !text.isEmpty() && text.charAt(0) == BOM ? text.substring(1) : text;
    List<CsvRecord> records = new ArrayList<>();
    List<String> fields = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean quoted = false;
    boolean fieldWasQuoted = false;
    boolean afterQuote = false;
    boolean lineHasContent = false;
    int line = 1;
    int recordStartLine = 1;
    int i = 0;
    int length = input.length();
    while (i < length) {
      char c = input.charAt(i);
      if (quoted) {
        if (c == '"') {
          if (i + 1 < length && input.charAt(i + 1) == '"') {
            field.append('"');
            i += 2;
            continue;
          }
          quoted = false;
          afterQuote = true;
        } else {
          if (c == '\n') {
            line++;
          }
          field.append(c);
        }
        i++;
        continue;
      }
      if (c == ',') {
        fields.add(finish(field, fieldWasQuoted));
        field.setLength(0);
        fieldWasQuoted = false;
        afterQuote = false;
        lineHasContent = true;
      } else if (c == '\r' || c == '\n') {
        if (c == '\r' && i + 1 < length && input.charAt(i + 1) == '\n') {
          i++;
        }
        if (lineHasContent || fieldWasQuoted || !field.isEmpty()) {
          fields.add(finish(field, fieldWasQuoted));
          records.add(new CsvRecord(records.size() + 1, recordStartLine, List.copyOf(fields)));
        }
        fields.clear();
        field.setLength(0);
        fieldWasQuoted = false;
        afterQuote = false;
        lineHasContent = false;
        line++;
        recordStartLine = line;
      } else if (c == '"') {
        if (afterQuote || !field.toString().isBlank()) {
          throw new CsvFormatException(line, "unexpected double quote");
        }
        field.setLength(0);
        quoted = true;
        fieldWasQuoted = true;
        lineHasContent = true;
      } else if (afterQuote) {
        if (c != ' ' && c != '\t') {
          throw new CsvFormatException(line, "text after closing quote");
        }
      } else {
        field.append(c);
        lineHasContent = true;
      }
      i++;
    }
    if (quoted) {
      throw new CsvFormatException(recordStartLine, "unterminated quoted field");
    }
    if (lineHasContent || fieldWasQuoted || !field.isEmpty()) {
      fields.add(finish(field, fieldWasQuoted));
      records.add(new CsvRecord(records.size() + 1, recordStartLine, List.copyOf(fields)));
    }
    return records;
  }

  private static String finish(StringBuilder field, boolean wasQuoted) {
    String value = field.toString();
    return wasQuoted ? value : trimSpacesAndTabs(value);
  }

  private static String trimSpacesAndTabs(String value) {
    int start = 0;
    int end = value.length();
    while (start < end && isBlankChar(value.charAt(start))) {
      start++;
    }
    while (end > start && isBlankChar(value.charAt(end - 1))) {
      end--;
    }
    return value.substring(start, end);
  }

  private static boolean isBlankChar(char c) {
    return c == ' ' || c == '\t';
  }
}
