package com.vitadelta.api.catalog.web;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Read-only scoring-engine parameters of {@code GET /weights/defaults} (contract schema {@code
 * EngineParameters}): the {@code config} of the scoring service's {@code GET /internal/v1/health},
 * renamed to camelCase and copied unrounded. Display only.
 *
 * @param diminishingBeta scoring {@code diminishing_beta}
 * @param utilityCeiling scoring {@code utility_ceiling}
 * @param verdictWorthItMaxRatio scoring {@code verdict_worth_it_max_ratio}
 * @param verdictMarginalMaxRatio scoring {@code verdict_marginal_max_ratio}
 */
public record EngineParameters(
    BigDecimal diminishingBeta,
    BigDecimal utilityCeiling,
    BigDecimal verdictWorthItMaxRatio,
    BigDecimal verdictMarginalMaxRatio) {

  public EngineParameters {
    Objects.requireNonNull(diminishingBeta, "diminishingBeta");
    Objects.requireNonNull(utilityCeiling, "utilityCeiling");
    Objects.requireNonNull(verdictWorthItMaxRatio, "verdictWorthItMaxRatio");
    Objects.requireNonNull(verdictMarginalMaxRatio, "verdictMarginalMaxRatio");
  }
}
