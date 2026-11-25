package com.vitadelta.api.recommendation;

/** Ranking modes of a recommendation query (section 6.7, contract schema {@code RankingMode}). */
public enum RankingMode {
  /** Cost per day ascending. */
  PRICE,
  /** Content score descending. */
  RICHNESS,
  /** Value score descending (default). */
  VALUE,
  /** Weighted mix of content and price ({@code lambda}). */
  CUSTOM
}
