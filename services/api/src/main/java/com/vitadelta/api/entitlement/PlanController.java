package com.vitadelta.api.entitlement;

import com.vitadelta.api.common.problem.ProblemCode;
import com.vitadelta.api.common.problem.ProblemException;
import com.vitadelta.api.identity.CurrentUser;
import com.vitadelta.api.identity.DisplayCurrencyPolicy;
import com.vitadelta.api.identity.UserAccount;
import com.vitadelta.api.identity.UserRepository;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/plans} (public, section 8.3): both plans with limits ({@code null} =
 * unlimited) and the PRO price in the display currency. Display currency: the {@code
 * displayCurrency} parameter, then the signed-in user's preference, then the native currency of the
 * price; a currency outside the supported set is {@code 422 fx_unsupported_currency}. The amount is
 * converted by the {@link DisplayPriceConverter} seam (native only until the FX module exists).
 */
@RestController
public class PlanController {

  /**
   * Native currency reported when no plan has a price (the currency of the seeded PRO list price).
   */
  static final String FALLBACK_CURRENCY = "TRY";

  private final PlanService plans;
  private final DisplayPriceConverter converter;
  private final DisplayCurrencyPolicy currencies;
  private final UserRepository users;

  public PlanController(
      PlanService plans,
      DisplayPriceConverter converter,
      DisplayCurrencyPolicy currencies,
      UserRepository users) {
    this.plans = plans;
    this.converter = converter;
    this.currencies = currencies;
    this.users = users;
  }

  /** Contract schema {@code PlanList}. */
  public record PlanList(PriceConversion meta, List<PlanView> items) {}

  /** Contract schema {@code Plan}; {@code price} is {@code null} for FREE. */
  public record PlanView(
      @Schema(allowableValues = {"FREE", "PRO"}) String code,
      String name,
      Integer savedProductsLimit,
      Integer alertSubscriptionsLimit,
      Integer notificationsPerMonthLimit,
      PlanPrice price) {}

  /** Contract schema {@code PlanPrice}. */
  public record PlanPrice(
      BigDecimal amount,
      BigDecimal amountNative,
      String currency,
      String nativeCurrency,
      @Schema(allowableValues = "MONTHLY") String billingPeriod) {}

  @GetMapping("/api/v1/plans")
  PlanList list(
      @RequestParam(required = false) @Pattern(regexp = "^[A-Z]{3}$") String displayCurrency,
      Authentication authentication) {
    List<Plan> all = plans.all();
    String requested =
        displayCurrency != null
            ? displayCurrency
            : CurrentUser.id(authentication)
                .flatMap(users::findById)
                .map(UserAccount::displayCurrency)
                .orElse(null);
    String nativeCurrency =
        all.stream()
            .filter(Plan::priced)
            .map(Plan::priceCurrency)
            .findFirst()
            .orElse(requested != null ? requested : FALLBACK_CURRENCY);
    String display = requested != null ? requested : nativeCurrency;
    if (!display.equals(nativeCurrency) && !currencies.isSupported(display)) {
      throw ProblemException.of(
          ProblemCode.FX_UNSUPPORTED_CURRENCY, "The display currency is not supported.");
    }
    return PriceConversion.present(
        converter.conversion(nativeCurrency, display),
        shown -> new PlanList(shown, all.stream().map(plan -> view(plan, shown)).toList()));
  }

  private PlanView view(Plan plan, PriceConversion conversion) {
    PlanPrice price = null;
    if (plan.priced()) {
      PriceConversion planConversion =
          plan.priceCurrency().equals(conversion.nativeCurrency())
              ? conversion
              : converter.conversion(plan.priceCurrency(), conversion.fx().quote());
      price =
          new PlanPrice(
              planConversion.convert(plan.priceAmount()),
              plan.priceAmount().setScale(4, RoundingMode.HALF_EVEN),
              planConversion.currency(),
              plan.priceCurrency(),
              plan.billingPeriod());
    }
    return new PlanView(
        plan.code().name(),
        plan.name(),
        plan.savedProductsLimit(),
        plan.alertSubscriptionsLimit(),
        plan.notificationsPerMonthLimit(),
        price);
  }
}
