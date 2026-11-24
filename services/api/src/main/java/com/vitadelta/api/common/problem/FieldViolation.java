package com.vitadelta.api.common.problem;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One entry of {@code errors[]} in a {@code validation_failed} problem.
 *
 * @param field JSON path of the offending field, for example {@code requiredNutrients[0].unit}
 * @param code machine-readable reason ({@code ^[a-z][a-z0-9_]*$}), for example {@code required},
 *     {@code out_of_range}, {@code invalid_format}, {@code weights_all_zero}
 * @param message human-readable explanation
 */
public record FieldViolation(String field, String code, String message) {

  private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9_]*$");

  public FieldViolation {
    Objects.requireNonNull(field, "field");
    Objects.requireNonNull(message, "message");
    if (code == null || !CODE.matcher(code).matches()) {
      throw new IllegalArgumentException("invalid field error code: " + code);
    }
  }
}
