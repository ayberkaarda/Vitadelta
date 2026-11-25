package com.vitadelta.api.fx;

import com.vitadelta.api.common.problem.ProblemCode;
import com.vitadelta.api.common.problem.ProblemException;
import com.vitadelta.api.fx.FxApi.FxCurrency;
import com.vitadelta.api.fx.FxApi.FxCurrencyList;
import com.vitadelta.api.fx.FxApi.FxRate;
import com.vitadelta.api.fx.FxApi.FxRateList;
import com.vitadelta.api.fx.FxApi.FxRefreshResult;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * FX endpoints (section 9.1): the public currency and rate lists and the ADMIN refresh. Rates are
 * returned at their stored 8 decimals; {@code stale} follows {@code fx_stale_after_days}. The rate
 * list reads through the same cached lookup as the display conversion, so it shows exactly the
 * rates the money fields of other responses are converted with.
 */
@RestController
public class FxController {

  private final SupportedCurrencies currencies;
  private final FxRateLookup lookup;
  private final FxRateRepository repository;
  private final FxRefreshJob job;

  public FxController(
      SupportedCurrencies currencies,
      FxRateLookup lookup,
      FxRateRepository repository,
      FxRefreshJob job) {
    this.currencies = currencies;
    this.lookup = lookup;
    this.repository = repository;
    this.job = job;
  }

  @GetMapping("/api/v1/fx/currencies")
  FxCurrencyList currencies() {
    Map<String, LocalDate> newest = repository.newestAsOfPerCurrency();
    List<FxCurrency> items = new ArrayList<>();
    for (String code : currencies.all()) {
      LocalDate asOf = newest.get(code);
      items.add(new FxCurrency(code, asOf, asOf != null && lookup.isStale(asOf)));
    }
    return new FxCurrencyList(items);
  }

  @GetMapping("/api/v1/fx/rates")
  FxRateList rates(@RequestParam @Pattern(regexp = "^[A-Z]{3}$") String base) {
    if (!currencies.isSupported(base)) {
      throw ProblemException.of(
          ProblemCode.FX_UNSUPPORTED_CURRENCY, "The currency is not supported.");
    }
    List<FxRate> rates = new ArrayList<>();
    for (String quote : currencies.all()) {
      if (quote.equals(base)) {
        continue;
      }
      lookup
          .newest(base, quote)
          .ifPresent(rate ->
              rates.add(
                  new FxRate(
                      quote,
                      rate.rate(),
                      rate.asOf(),
                      rate.source(),
                      lookup.isStale(rate.asOf()))));
    }
    return new FxRateList(base, rates);
  }

  @PostMapping("/api/v1/admin/fx/refresh")
  @PreAuthorize("hasRole('ADMIN')")
  FxRefreshResult refresh() {
    FxRefreshJob.Result result = job.run();
    if (!result.ran()) {
      throw ProblemException.of(
          ProblemCode.CONFLICT_RETRY, "The FX refresh is running at this moment; retry later.");
    }
    return new FxRefreshResult(
        result.upsertedRows(), result.failedBases(), result.unsupportedBases());
  }
}
