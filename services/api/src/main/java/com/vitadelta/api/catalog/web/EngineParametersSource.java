package com.vitadelta.api.catalog.web;

import java.util.Optional;

/**
 * Source of the scoring-engine parameters shown by {@code GET /weights/defaults}. The
 * implementation keeps the last answer obtained from the scoring service in memory; it never blocks
 * longer than the scoring client's timeouts and never throws.
 */
@FunctionalInterface
public interface EngineParametersSource {

  /** The parameters, or empty while none was obtained since start-up. */
  Optional<EngineParameters> current();
}
