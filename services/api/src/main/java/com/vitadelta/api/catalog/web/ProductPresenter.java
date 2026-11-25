package com.vitadelta.api.catalog.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.vitadelta.api.catalog.AvailabilityStatus;
import com.vitadelta.api.catalog.DosageForm;
import com.vitadelta.api.catalog.NutrientCategory;
import com.vitadelta.api.catalog.UlBasis;
import com.vitadelta.api.catalog.price.EffectivePrice;
import com.vitadelta.api.catalog.price.EffectivePriceResolver;
import com.vitadelta.api.catalog.price.OfferSnapshot;
import com.vitadelta.api.catalog.price.StockFilter;
import com.vitadelta.api.catalog.read.CatalogViews.CatalogProduct;
import com.vitadelta.api.catalog.read.CatalogViews.NutrientFacts;
import com.vitadelta.api.catalog.read.CatalogViews.ProductInCountry;
import com.vitadelta.api.catalog.read.CatalogViews.ProductNutrientView;
import com.vitadelta.api.catalog.read.CatalogViews.ProvenanceView;
import com.vitadelta.api.catalog.unit.Unit;
import com.vitadelta.api.common.numeric.Rounding;
import com.vitadelta.api.common.watch.WatchState;
import com.vitadelta.api.common.watch.WatchedAlert;
import com.vitadelta.api.entitlement.PriceConversion;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Builds the product responses of {@code GET /products} and {@code GET /products/{id}} (contract
 * schemas {@code ProductSummary}, {@code ProductDetail}). The cost uses the effective price with
 * out-of-stock offers included (section 7.4); money is rounded once to 4 decimals from the exact
 * native value (section 6.12) and converted with the request's {@link PriceConversion}. Flags are
 * the ones the API derives itself, in contract order: {@code CONVERSION_ASSUMED}, {@code
 * PRICE_STALE}, {@code OUT_OF_STOCK}, {@code FX_STALE}, {@code FX_UNAVAILABLE}.
 */
@Component
public class ProductPresenter {

  /** {@code NutrientReference.source}: product detail shows regulatory values only. */
  static final String REGULATORY = "REGULATORY";

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  /** Contract schema {@code ProductFlag}. */
  public record Flag(String code, List<String> nutrientCodes) {

    public Flag {
      nutrientCodes = List.copyOf(nutrientCodes);
    }
  }

  /** Contract schema {@code ProductProvenance}. */
  public record Provenance(
      boolean synthetic, String source, String sourceRef, LocalDate sourceEntryDate) {}

  /** Contract schema {@code Cost}. */
  public record Cost(
      BigDecimal effectivePrice,
      BigDecimal effectivePriceNative,
      String currency,
      BigDecimal servingsPerContainer,
      BigDecimal daysSupply,
      BigDecimal costPerDay,
      BigDecimal costPerDayNative,
      boolean priceStale,
      boolean outOfStock) {}

  /** Contract schema {@code ProductSummary}. */
  public record ProductSummary(
      UUID id,
      String code,
      String brand,
      String name,
      DosageForm dosageForm,
      int unitsPerContainer,
      int unitsPerServing,
      BigDecimal servingsPerDay,
      Provenance provenance,
      Cost cost,
      List<Flag> flags) {}

  /** Contract schema {@code ProductAvailability}. */
  public record Availability(AvailabilityStatus status, String note) {}

  /**
   * Contract schema {@code ProductNutrient}. {@code reference} and {@code upperLimit} are always
   * written ({@code null} when no value applies) but optional in the contract for compatibility.
   */
  public record Nutrient(
      @Schema(requiredMode = REQUIRED) String code,
      @Schema(requiredMode = REQUIRED) NutrientCategory category,
      @Schema(requiredMode = REQUIRED) BigDecimal amountPerServing,
      @Schema(requiredMode = REQUIRED) BigDecimal amountPerDay,
      @Schema(
              requiredMode = REQUIRED,
              allowableValues = {"mg", "mcg"})
          String unit,
      @Schema(requiredMode = REQUIRED) BigDecimal ulAmountPerServing,
      @Schema(requiredMode = REQUIRED) String formCode,
      @Schema(requiredMode = REQUIRED) String formLabel,
      @Schema(requiredMode = REQUIRED) boolean conversionAssumed,
      @Schema(requiredMode = NOT_REQUIRED) NutrientReference reference,
      @Schema(requiredMode = NOT_REQUIRED) NutrientUpperLimit upperLimit) {}

  /**
   * Contract schema {@code NutrientReference}: the reference value of the country's reference set
   * and the product's daily amount as a percentage of it.
   */
  public record NutrientReference(
      BigDecimal amount,
      @Schema(allowableValues = {"mg", "mcg"}) String unit,
      String setCode,
      // The contract narrows ReferenceSource to REGULATORY with a const.
      @Schema(allowableValues = {"REGULATORY", "MARKET_MEDIAN", "PRESENCE_ONLY"}) String source,
      BigDecimal percentOfReference) {}

  /**
   * Contract schema {@code NutrientUpperLimit}: the upper limit of the country's UL set that
   * applies to the nutrient and form, and whether the product's UL-relevant daily amount exceeds
   * it.
   */
  public record NutrientUpperLimit(
      BigDecimal amount,
      @Schema(allowableValues = {"mg", "mcg"}) String unit,
      UlBasis basis,
      String setCode,
      String formCode,
      String note,
      BigDecimal ulAmountPerDay,
      boolean exceeded) {}

  /** Contract schema {@code Offer}. */
  public record Offer(
      String retailer,
      BigDecimal price,
      BigDecimal priceNative,
      String currency,
      boolean inStock,
      String url,
      Instant checkedAt,
      boolean priceStale) {}

  /** Contract schema {@code SelectedOffer}. */
  public record SelectedOffer(String retailer, String url, Instant checkedAt) {}

  /**
   * Contract schema {@code ProductDetail}. {@code saved} and {@code alerts} come from the {@link
   * com.vitadelta.api.common.watch.WatchStateReader} for a signed-in caller ({@link
   * #withWatchState}); they are {@code false} and empty for anonymous callers.
   */
  public record ProductDetail(
      UUID id,
      String code,
      String brand,
      String name,
      DosageForm dosageForm,
      int unitsPerContainer,
      int unitsPerServing,
      BigDecimal servingsPerDay,
      String gtin,
      boolean synthetic,
      Provenance provenance,
      Availability availability,
      List<Nutrient> nutrients,
      List<Offer> offers,
      Cost cost,
      SelectedOffer selectedOffer,
      List<Flag> flags,
      boolean saved,
      List<WatchedAlert> alerts) {

    /** This detail with the caller's {@code saved} and {@code alerts}. */
    public ProductDetail withWatchState(WatchState state) {
      return new ProductDetail(
          id,
          code,
          brand,
          name,
          dosageForm,
          unitsPerContainer,
          unitsPerServing,
          servingsPerDay,
          gtin,
          synthetic,
          provenance,
          availability,
          nutrients,
          offers,
          cost,
          selectedOffer,
          flags,
          state.saved(),
          state.alerts());
    }
  }

  private final EffectivePriceResolver resolver;

  public ProductPresenter(EffectivePriceResolver resolver) {
    this.resolver = resolver;
  }

  /** The summary of an in-market product. */
  public ProductSummary summary(CatalogProduct product, PriceConversion conversion, Instant now) {
    EffectivePrice price = effective(product, now);
    Cost cost = price == null ? null : cost(product, price, conversion);
    return new ProductSummary(
        product.id(),
        product.code(),
        product.brand(),
        product.name(),
        product.dosageForm(),
        product.unitsPerContainer(),
        product.unitsPerServing(),
        product.servingsPerDay(),
        provenance(product.provenance()),
        cost,
        flags(product, cost, conversion));
  }

  /** The detail of a product in market in the country. */
  public ProductDetail detail(ProductInCountry found, PriceConversion conversion, Instant now) {
    CatalogProduct product = found.product();
    EffectivePrice price = effective(product, now);
    Cost cost = price == null ? null : cost(product, price, conversion);
    List<Offer> offers = new ArrayList<>();
    for (OfferSnapshot offer : product.offers()) {
      offers.add(
          new Offer(
              offer.retailer(),
              conversion.convert(offer.price()),
              native4(offer.price()),
              conversion.currency(),
              offer.inStock(),
              offer.url(),
              offer.checkedAt(),
              resolver.isStale(offer, now)));
    }
    List<Nutrient> nutrients =
        product.nutrients().stream()
            .map(n -> nutrient(n, product.servingsPerDay(), found.facts()))
            .toList();
    SelectedOffer selected =
        price == null
            ? null
            : new SelectedOffer(
                price.selected().retailer(), price.selected().url(), price.selected().checkedAt());
    return new ProductDetail(
        product.id(),
        product.code(),
        product.brand(),
        product.name(),
        product.dosageForm(),
        product.unitsPerContainer(),
        product.unitsPerServing(),
        product.servingsPerDay(),
        product.gtin(),
        product.provenance().synthetic(),
        provenance(product.provenance()),
        new Availability(found.availabilityStatus(), found.availabilityNote()),
        nutrients,
        offers,
        cost,
        selected,
        flags(product, cost, conversion),
        false,
        List.of());
  }

  private EffectivePrice effective(CatalogProduct product, Instant now) {
    return resolver
        .resolve(product.offers(), StockFilter.INCLUDE_OUT_OF_STOCK, now)
        .price()
        .orElse(null);
  }

  /**
   * Cost per day as the scoring service computes it (section 6.2), in double precision: servings
   * per container = units per container / units per serving, days supply = servings per container /
   * servings per day, cost per day = price / days supply.
   */
  static Cost cost(CatalogProduct product, EffectivePrice price, PriceConversion conversion) {
    double servingsPerContainer =
        (double) product.unitsPerContainer() / (double) product.unitsPerServing();
    double daysSupply = servingsPerContainer / product.servingsPerDay().doubleValue();
    double costPerDay = price.price().doubleValue() / daysSupply;
    return new Cost(
        conversion.convert(price.price()),
        native4(price.price()),
        conversion.currency(),
        Rounding.ratio(servingsPerContainer),
        Rounding.ratio(daysSupply),
        conversion.convert(new BigDecimal(costPerDay)),
        Rounding.money(costPerDay),
        price.priceStale(),
        price.outOfStock());
  }

  private static List<Flag> flags(CatalogProduct product, Cost cost, PriceConversion conversion) {
    List<Flag> flags = new ArrayList<>();
    List<String> assumed = product.conversionAssumedCodes();
    if (!assumed.isEmpty()) {
      flags.add(new Flag("CONVERSION_ASSUMED", assumed));
    }
    if (cost != null) {
      if (cost.priceStale()) {
        flags.add(new Flag("PRICE_STALE", List.of()));
      }
      if (cost.outOfStock()) {
        flags.add(new Flag("OUT_OF_STOCK", List.of()));
      }
      if (conversion.fx().stale()) {
        flags.add(new Flag("FX_STALE", List.of()));
      }
      if (PriceConversion.UNAVAILABLE.equals(conversion.fx().status())) {
        flags.add(new Flag("FX_UNAVAILABLE", List.of()));
      }
    }
    return List.copyOf(flags);
  }

  /**
   * One nutrient row with the regulatory facts of the requested country. Every value is computed
   * from the exact (unrounded) amounts and rounded once to 4 decimals, half-even (section 6.12);
   * {@code exceeded} compares the exact values. A pure function of catalog data: no scoring call,
   * no clock.
   *
   * @throws IllegalStateException when a reference or UL row is not stored in the nutrient's
   *     canonical unit (a data error; amounts are never converted here)
   */
  static Nutrient nutrient(ProductNutrientView n, BigDecimal servingsPerDay, NutrientFacts facts) {
    BigDecimal amountPerDay = n.amountPerDay(servingsPerDay);
    NutrientReference reference =
        facts
            .reference(n.code())
            .map(r -> {
              requireCanonical(n, r.unit(), "reference value of " + facts.referenceSetCode());
              return new NutrientReference(
                  amount4(r.amount()),
                  r.unit().symbol(),
                  facts.referenceSetCode(),
                  REGULATORY,
                  amountPerDay
                      .multiply(HUNDRED)
                      .divide(r.amount(), Rounding.SCALE, RoundingMode.HALF_EVEN));
            })
            .orElse(null);
    NutrientUpperLimit upperLimit =
        facts
            .upperLimit(n.code(), n.formCode())
            .map(u -> {
              requireCanonical(n, u.unit(), "upper limit of " + facts.ulSetCode());
              BigDecimal ulAmountPerDay =
                  n.ulAmountPerServing() == null
                      ? amountPerDay
                      : n.ulAmountPerServing().multiply(servingsPerDay);
              return new NutrientUpperLimit(
                  amount4(u.amount()),
                  u.unit().symbol(),
                  u.basis(),
                  facts.ulSetCode(),
                  u.formCode(),
                  u.note() == null || u.note().isEmpty() ? null : u.note(),
                  amount4(ulAmountPerDay),
                  ulAmountPerDay.compareTo(u.amount()) > 0);
            })
            .orElse(null);
    return new Nutrient(
        n.code(),
        n.category(),
        amount4(n.amountPerServing()),
        amount4(amountPerDay),
        n.unit().symbol(),
        n.ulAmountPerServing() == null ? null : amount4(n.ulAmountPerServing()),
        n.formCode(),
        n.formLabel(),
        n.conversionAssumed(),
        reference,
        upperLimit);
  }

  private static void requireCanonical(ProductNutrientView n, Unit stored, String what) {
    if (stored != n.unit()) {
      throw new IllegalStateException(
          "the "
              + what
              + " for "
              + n.code()
              + " is stored in "
              + stored.symbol()
              + ", not in the canonical unit "
              + n.unit().symbol());
    }
  }

  private static Provenance provenance(ProvenanceView view) {
    return new Provenance(
        view.synthetic(), view.source(), view.sourceRef(), view.sourceEntryDate());
  }

  private static BigDecimal native4(BigDecimal amount) {
    return amount.setScale(Rounding.SCALE, RoundingMode.HALF_EVEN);
  }

  private static BigDecimal amount4(BigDecimal amount) {
    return amount.setScale(Rounding.SCALE, RoundingMode.HALF_EVEN);
  }
}
