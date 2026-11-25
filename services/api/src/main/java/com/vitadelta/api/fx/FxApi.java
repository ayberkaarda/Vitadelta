package com.vitadelta.api.fx;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Response bodies of the FX endpoints (contract schemas of the same names). */
public final class FxApi {

  private FxApi() {}

  /**
   * Contract schema {@code FxCurrency}.
   *
   * @param asOf newest stored rate date with this currency as base or quote, {@code null} without
   *     rates
   * @param stale whether {@code asOf} is older than {@code fx_stale_after_days}; {@code false}
   *     without rates
   */
  public record FxCurrency(String code, LocalDate asOf, boolean stale) {}

  /** Contract schema {@code FxCurrencyList}. */
  public record FxCurrencyList(List<FxCurrency> items) {}

  /**
   * Contract schema {@code FxRate}.
   *
   * @param rate stored rate with its 8 decimals, unrounded
   */
  public record FxRate(
      String quote,
      BigDecimal rate,
      LocalDate asOf,
      @Schema(allowableValues = FrankfurterFxRateProvider.SOURCE) String source,
      boolean stale) {}

  /** Contract schema {@code FxRateList}. */
  public record FxRateList(String base, List<FxRate> rates) {}

  /**
   * Contract schema {@code FxRefreshResult}.
   *
   * @param upsertedRows rows inserted or updated by the run
   * @param failedBases bases whose provider call failed, ascending; unsupported bases excluded
   * @param unsupportedBases bases the provider rejected with the verified {@code invalid currency:
   *     <base>} answer, ascending
   */
  public record FxRefreshResult(
      int upsertedRows, List<String> failedBases, List<String> unsupportedBases) {}
}
