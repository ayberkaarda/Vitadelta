package com.vitadelta.api.imports;

import java.util.ArrayList;
import java.util.List;

/**
 * The errors of one import file, bounded: the first {@code maxErrors} errors are kept, later ones
 * are only counted. Once more were found, {@link #overflowed()} tells the reader to stop
 * validating, and {@link #toList()} ends with one {@code too_many_errors} error at the location of
 * the first error that was not kept. {@link #count()} counts every reported error, kept or not, so
 * "did this row add an error" checks stay correct after the bound is reached.
 */
final class ImportProblems {

  /** Code of the summary error that replaces the errors beyond the bound. */
  static final String TOO_MANY_ERRORS = "too_many_errors";

  private final int maxErrors;
  private final List<ImportProblem> kept = new ArrayList<>();
  private int count;
  private String firstOmittedLocation;

  ImportProblems(int maxErrors) {
    this.maxErrors = maxErrors;
  }

  void add(ImportProblem problem) {
    count++;
    if (kept.size() < maxErrors) {
      kept.add(problem);
    } else if (firstOmittedLocation == null) {
      firstOmittedLocation = problem.location();
    }
  }

  void addAll(List<ImportProblem> problems) {
    problems.forEach(this::add);
  }

  /** Every error reported so far, including the ones beyond the bound. */
  int count() {
    return count;
  }

  boolean isEmpty() {
    return count == 0;
  }

  /** Whether more errors were reported than are kept; validation should stop. */
  boolean overflowed() {
    return firstOmittedLocation != null;
  }

  /** The kept errors, followed by the summary error when some were omitted. */
  List<ImportProblem> toList() {
    List<ImportProblem> result = new ArrayList<>(kept);
    if (overflowed()) {
      result.add(
          new ImportProblem(
              firstOmittedLocation,
              TOO_MANY_ERRORS,
              "validation stopped after "
                  + maxErrors
                  + " errors; the remaining errors of the file are not listed"));
    }
    return result;
  }
}
