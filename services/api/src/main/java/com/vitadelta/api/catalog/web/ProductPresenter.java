package com.vitadelta.api.catalog.web;

import com.vitadelta.api.catalog.AvailabilityStatus;
import com.vitadelta.api.catalog.DosageForm;
import com.vitadelta.api.catalog.NutrientCategory;
import com.vitadelta.api.catalog.price.EffectivePrice;
import com.vitadelta.api.catalog.price.EffectivePriceResolver;
import com.vitadelta.api.catalog.price.OfferSnapshot;
import com.vitadelta.api.catalog.price.StockFilter;
import com.vitadelta.api.catalog.read.CatalogViews.CatalogProduct;
import com.vitadelta.api.catalog.read.CatalogViews.ProductInCountry;
import com.vitadelta.api.catalog.read.CatalogViews.ProductNutrientView;
import com.vitadelta.api.catalog.read.CatalogViews.ProvenanceView;
import com.vitadelta.api.common.numeric.Rounding;
import com.vitadelta.api.entitlement.PriceConversion;
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

  /** Contract schema {@code ProductNutrient}. */
  public record Nutrient(
      String code,
      NutrientCategory category,
      BigDecimal amountPerServing,
      BigDecimal amountPerDay,
      String unit,
      BigDecimal ulAmountPerServing,
      String formCode,
      String formLabel,
      boolean conversionAssumed) {}

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
   * Contract schema {@code ProductDetail}. {@code saved} and {@code alerts} are {@code false} and
   * empty until saved products and alerts exist (Phase 5).
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
      List<Object> alerts) {}

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
        product.nutrients().stream().map(n -> nutrient(n, product.servingsPerDay())).toList();
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

  private static Nutrient nutrient(ProductNutrientView n, BigDecimal servingsPerDay) {
    return new Nutrient(
        n.code(),
        n.category(),
        amount4(n.amountPerServing()),
        amount4(n.amountPerDay(servingsPerDay)),
        n.unit().symbol(),
        n.ulAmountPerServing() == null ? null : amount4(n.ulAmountPerServing()),
        n.formCode(),
        n.formLabel(),
        n.conversionAssumed());
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
