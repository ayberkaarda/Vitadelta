package com.vitadelta.api.fx;

import com.vitadelta.api.entitlement.DisplayPriceConverter;
import com.vitadelta.api.entitlement.PriceConversion;
import com.vitadelta.api.fx.FxRateRepository.StoredRate;
import java.math.RoundingMode;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The FX module's {@link DisplayPriceConverter} over the stored rates (section 7.7). Declaring it
 * replaces the entitlement package's native-only default, so {@code GET /plans}, the product
 * endpoints and the recommendation query and compare responses all convert through it.
 *
 * <ul>
 *   <li>identity pair: rate 1 (no date, no source);
 *   <li>stored rate: {@code status = OK}, the newest row's rate at its stored 8 decimals, its date
 *       and source, {@code stale} when the date is older than {@code fx_stale_after_days} (the
 *       presenters then add {@code FX_STALE} to every money-bearing item);
 *   <li>no row: money stays native with {@code status = UNAVAILABLE} (presenters add {@code
 *       FX_UNAVAILABLE}).
 * </ul>
 *
 * The callers have already checked that the display currency is supported ({@code 422
 * fx_unsupported_currency} otherwise). Converted values are computed by {@link
 * PriceConversion#convert} from the unrounded native value times this rate, rounded once.
 */
@Component
public class FxDisplayPriceConverter implements DisplayPriceConverter {

  private final FxRateLookup lookup;

  public FxDisplayPriceConverter(FxRateLookup lookup) {
    this.lookup = lookup;
  }

  @Override
  public PriceConversion conversion(String nativeCurrency, String displayCurrency) {
    if (nativeCurrency.equals(displayCurrency)) {
      return PriceConversion.identity(nativeCurrency);
    }
    Optional<StoredRate> stored = lookup.newest(nativeCurrency, displayCurrency);
    if (stored.isEmpty()) {
      return PriceConversion.unavailable(nativeCurrency, displayCurrency);
    }
    StoredRate rate = stored.get();
    return new PriceConversion(
        nativeCurrency,
        displayCurrency,
        new PriceConversion.Fx(
            nativeCurrency,
            displayCurrency,
            rate.rate().setScale(FrankfurterFxRateProvider.RATE_SCALE, RoundingMode.UNNECESSARY),
            rate.asOf(),
            rate.source(),
            lookup.isStale(rate.asOf()),
            PriceConversion.OK));
  }
}
