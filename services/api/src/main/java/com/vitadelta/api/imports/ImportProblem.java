package com.vitadelta.api.imports;

/**
 * One validation error of an imported document.
 *
 * @param location where the error is: {@code row N} for CSV records (header is row 1), a JSON path
 *     such as {@code products[2].nutrients[0].unit} for JSON documents
 * @param code lower-case machine-readable error code
 */
public record ImportProblem(String location, String code, String message) {

  /** Message in the form {@code code: message (location)}. */
  public String describe() {
    return code + ": " + message + " (" + location + ")";
  }

  public static ImportProblem atRow(int row, String code, String message) {
    return new ImportProblem("row " + row, code, message);
  }
}
