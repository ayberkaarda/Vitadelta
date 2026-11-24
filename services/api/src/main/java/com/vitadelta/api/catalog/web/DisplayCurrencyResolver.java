package com.vitadelta.api.catalog.web;

import com.vitadelta.api.common.problem.ProblemCode;
import com.vitadelta.api.common.problem.ProblemException;
import com.vitadelta.api.entitlement.DisplayPriceConverter;
import com.vitadelta.api.entitlement.PriceConversion;
import com.vitadelta.api.identity.CurrentUser;
import com.vitadelta.api.identity.DisplayCurrencyPolicy;
import com.vitadelta.api.identity.UserAccount;
import com.vitadelta.api.identity.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Display currency of catalog and recommendation responses (section 7.7). Resolution: the request
 * parameter, then the signed-in user's stored preference, then the country's native currency. A
 * requested currency outside the supported set ({@link DisplayCurrencyPolicy}) is {@code 422
 * fx_unsupported_currency}.
 *
 * <p>The conversion itself comes from the {@link DisplayPriceConverter} seam of the entitlement
 * package (also used by {@code GET /plans}). Until the FX module exists, its default converts only
 * the identity pair and answers every other supported currency with native money and {@code
 * fx.status = UNAVAILABLE}; the FX module replaces the converter bean and every catalog response
 * follows without change here.
 */
@Component
public class DisplayCurrencyResolver {

  private final DisplayPriceConverter converter;
  private final DisplayCurrencyPolicy policy;
  private final UserRepository users;

  public DisplayCurrencyResolver(
      DisplayPriceConverter converter, DisplayCurrencyPolicy policy, UserRepository users) {
    this.converter = converter;
    this.policy = policy;
    this.users = users;
  }

  /**
   * The conversion from {@code nativeCurrency} for this request.
   *
   * @param requested the well-formed {@code displayCurrency} parameter, or {@code null}
   * @throws ProblemException {@code fx_unsupported_currency} for a requested currency that is not
   *     supported
   */
  public PriceConversion resolve(
      String nativeCurrency, String requested, Authentication authentication) {
    if (requested != null) {
      if (!requested.equals(nativeCurrency) && !policy.isSupported(requested)) {
        throw ProblemException.of(
            ProblemCode.FX_UNSUPPORTED_CURRENCY, "The display currency is not supported.");
      }
      return converter.conversion(nativeCurrency, requested);
    }
    String preferred =
        CurrentUser.id(authentication)
            .flatMap(users::findById)
            .map(UserAccount::displayCurrency)
            .filter(c -> c.equals(nativeCurrency) || policy.isSupported(c))
            .orElse(nativeCurrency);
    return converter.conversion(nativeCurrency, preferred);
  }
}
