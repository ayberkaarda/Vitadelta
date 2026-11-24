package com.vitadelta.api.catalog.seed;

import com.vitadelta.api.imports.ImportProblem;

/**
 * One validation error found while reading the seed data.
 *
 * @param file path of the file relative to the data directory
 */
public record SeedProblem(String file, ImportProblem problem) {

  /** Message in the form {@code file location: code: message}. */
  public String describe() {
    return file + " " + problem.location() + ": " + problem.code() + ": " + problem.message();
  }
}
