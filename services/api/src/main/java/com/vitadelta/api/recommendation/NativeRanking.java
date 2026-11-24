package com.vitadelta.api.recommendation;

import com.vitadelta.api.catalog.DosageForm;
import com.vitadelta.api.catalog.NutrientCategory;
import com.vitadelta.api.catalog.price.OfferSnapshot;
import com.vitadelta.api.catalog.read.CatalogViews.CatalogProduct;
import com.vitadelta.api.catalog.read.CatalogViews.ProvenanceView;
import com.vitadelta.api.recommendation.scoring.model.BreakdownRow;
import com.vitadelta.api.recommendation.scoring.model.Cost;
import com.vitadelta.api.recommendation.scoring.model.Flag;
import com.vitadelta.api.recommendation.scoring.model.ScoreResponse;
import com.vitadelta.api.recommendation.scoring.model.ScoredProduct;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The full ranking of one query in the country's native currency, exactly as the scoring service
 * returned it (unrounded doubles) plus the product metadata the API attaches (section 7.5). This is
 * the cached value: locking, currency conversion, rounding and paging are applied to it per
 * request. Immutable.
 *
 * @param marketSize scored candidates before the required-nutrient and UL filters
 * @param mRef market rate per content point in native currency, {@code null} when undefined
 * @param noStock in-market products excluded before scoring because none of their offers is in
 *     stock ({@code IN_STOCK_ONLY})
 * @param noOffer in-market products excluded before scoring because they have no offer
 * @param items the surviving products in rank order
 */
public record NativeRanking(
    long catalogVersion,
    String country,
    String nativeCurrency,
    int marketSize,
    Double mRef,
    int noStock,
    int noOffer,
    int excludedRequiredNutrients,
    int excludedExceedsUl,
    List<Item> items) {

  public NativeRanking {
    items = List.copyOf(items);
  }

  /** Number of ranked products. */
  public int total() {
    return items.size();
  }

  /** Product metadata attached by the API. */
  public record ProductMeta(
      UUID id,
      String code,
      String brand,
      String name,
      DosageForm dosageForm,
      ProvenanceView provenance,
      OfferSnapshot selectedOffer) {

    static ProductMeta of(CatalogProduct product, OfferSnapshot selectedOffer) {
      return new ProductMeta(
          product.id(),
          product.code(),
          product.brand(),
          product.name(),
          product.dosageForm(),
          product.provenance(),
          selectedOffer);
    }
  }

  /** One scored product. */
  public record Item(
      ProductMeta product,
      int rank,
      NativeCost cost,
      double contentScore,
      double contentScoreDisplay,
      double valueScore,
      double valueScoreDisplay,
      Double customScore,
      List<NativeFlag> flags,
      List<NativeRow> breakdown,
      List<NativeRow> topContributors) {

    public Item {
      flags = List.copyOf(flags);
      breakdown = List.copyOf(breakdown);
      topContributors = List.copyOf(topContributors);
    }
  }

  /** Cost of a scored product (scoring {@code Cost}), native currency. */
  public record NativeCost(
      double effectivePrice,
      double servingsPerContainer,
      double daysSupply,
      double costPerDay,
      boolean priceStale,
      boolean outOfStock) {

    static NativeCost of(Cost cost) {
      return new NativeCost(
          number(cost.getEffectivePrice()),
          number(cost.getServingsPerContainer()),
          number(cost.getDaysSupply()),
          number(cost.getCostPerDay()),
          Boolean.TRUE.equals(cost.getPriceStale()),
          Boolean.TRUE.equals(cost.getOutOfStock()));
    }
  }

  /** A product flag of the scoring service. */
  public record NativeFlag(String code, List<String> nutrientCodes) {

    public NativeFlag {
      nutrientCodes = List.copyOf(nutrientCodes);
    }

    static NativeFlag of(Flag flag) {
      return new NativeFlag(
          flag.getCode().getValue(),
          flag.getNutrientCodes() == null ? List.of() : List.copyOf(flag.getNutrientCodes()));
    }
  }

  /** A breakdown row of the scoring service. */
  public record NativeRow(
      String code,
      NutrientCategory category,
      double amountPerDay,
      double ulAmountPerDay,
      String unit,
      Double reference,
      String referenceSource,
      Double coverageRatio,
      double utility,
      double weight,
      double contribution,
      boolean cappedAtUl,
      boolean cappedAtUMax,
      boolean ulBelowReference,
      boolean belowPresenceFloor) {

    static NativeRow of(BreakdownRow row) {
      return new NativeRow(
          row.getCode(),
          NutrientCategory.valueOf(row.getCategory().getValue()),
          number(row.getAmountPerDay()),
          number(row.getUlAmountPerDay()),
          row.getUnit().getValue(),
          nullable(row.getReference()),
          row.getReferenceSource().getValue(),
          nullable(row.getCoverageRatio()),
          number(row.getUtility()),
          number(row.getWeight()),
          number(row.getContribution()),
          Boolean.TRUE.equals(row.getCappedAtUl()),
          Boolean.TRUE.equals(row.getCappedAtUMax()),
          Boolean.TRUE.equals(row.getUlBelowReference()),
          Boolean.TRUE.equals(row.getBelowPresenceFloor()));
    }
  }

  /**
   * Builds the ranking from a scoring answer.
   *
   * @param products metadata of every candidate sent, by id
   * @throws ScoringException.Failed when the answer names a product that was not sent
   */
  static NativeRanking of(
      long catalogVersion,
      String country,
      String nativeCurrency,
      int noStock,
      int noOffer,
      ScoreResponse response,
      Map<UUID, ProductMeta> products) {
    List<Item> items = response.getItems().stream().map(scored -> item(scored, products)).toList();
    return new NativeRanking(
        catalogVersion,
        country,
        nativeCurrency,
        response.getMeta().getMarketSize(),
        nullable(response.getMeta().getmRef()),
        noStock,
        noOffer,
        response.getMeta().getExcluded().getRequiredNutrients(),
        response.getMeta().getExcluded().getExceedsUl(),
        items);
  }

  private static Item item(ScoredProduct scored, Map<UUID, ProductMeta> products) {
    ProductMeta meta = products.get(productId(scored.getProductId()));
    if (meta == null) {
      throw new ScoringException.Failed(
          "scoring service returned a product that was not a candidate", null);
    }
    return new Item(
        meta,
        scored.getRank(),
        NativeCost.of(scored.getCost()),
        number(scored.getContentScore()),
        number(scored.getContentScoreDisplay()),
        number(scored.getValueScore()),
        number(scored.getValueScoreDisplay()),
        nullable(scored.getCustomScore()),
        scored.getFlags().stream().map(NativeFlag::of).toList(),
        scored.getBreakdown().stream().map(NativeRow::of).toList(),
        scored.getTopContributors().stream().map(NativeRow::of).toList());
  }

  /** The product id of a scoring answer; an id that is not a UUID was never sent. */
  static UUID productId(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      throw new ScoringException.Failed("scoring service returned an unknown product id", e);
    }
  }

  /** The double value of a scoring number (the JSON text parses back to the same double). */
  static double number(BigDecimal value) {
    if (value == null) {
      throw new ScoringException.Failed("scoring service omitted a required number", null);
    }
    return value.doubleValue();
  }

  static Double nullable(BigDecimal value) {
    return value == null ? null : value.doubleValue();
  }
}
