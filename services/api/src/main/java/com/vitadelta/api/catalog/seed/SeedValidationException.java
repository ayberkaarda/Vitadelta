package com.vitadelta.api.catalog.seed;

import java.util.List;
import java.util.stream.Collectors;
import org.springframework.boot.ExitCodeGenerator;

/** The seed data failed validation; nothing was written. The process exits with code 2. */
public class SeedValidationException extends RuntimeException implements ExitCodeGenerator {

  private static final long serialVersionUID = 1L;

  /** Process exit code of a validation failure. */
  public static final int EXIT_CODE = 2;

  private final transient List<SeedProblem> problems;

  public SeedValidationException(List<SeedProblem> problems) {
    super(
        "seed data is invalid ("
            + problems.size()
            + " problem(s)):\n"
            + problems.stream().map(SeedProblem::describe).collect(Collectors.joining("\n")));
    this.problems = List.copyOf(problems);
  }

  public List<SeedProblem> problems() {
    return problems;
  }

  @Override
  public int getExitCode() {
    return EXIT_CODE;
  }
}
