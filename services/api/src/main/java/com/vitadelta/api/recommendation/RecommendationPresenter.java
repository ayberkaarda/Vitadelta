package com.vitadelta.api.recommendation;

import com.vitadelta.api.catalog.NutrientCategory;
import com.vitadelta.api.catalog.price.OfferSnapshot;
import com.vitadelta.api.catalog.read.CatalogViews.ProvenanceView;
import com.vitadelta.api.catalog.web.ProductPresenter.Cost;
import com.vitadelta.api.catalog.web.ProductPresenter.Provenance;
import com.vitadelta.api.catalog.web.ProductPresenter.SelectedOffer;
import com.vitadelta.api.common.numeric.Rounding;
import com.vitadelta.api.entitlement.PriceConversion;
import com.vitadelta.api.recommendation.NativeRanking.Item;
import com.vitadelta.api.recommendation.NativeRanking.NativeCost;
import com.vitadelta.api.recommendation.NativeRanking.NativeFlag;
import com.vitadelta.api.recommendation.NativeRanking.NativeRow;
import com.vitadelta.api.recommendation.NativeRanking.ProductMeta;
import com.vitadelta.api.recommendation.RecommendationApi.BreakdownRow;
import com.vitadelta.api.recommendation.RecommendationApi.ComparedProduct;
import com.vitadelta.api.recommendation.RecommendationApi.DeltaRow;
import com.vitadelta.api.recommendation.RecommendationApi.Explanation;
import com.vitadelta.api.recommendation.RecommendationApi.ExplanationParams;
import com.vitadelta.api.recommendation.RecommendationApi.Flag;
import com.vitadelta.api.recommendation.RecommendationApi.RankedProduct;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Response assembly of the recommendation endpoints: the single rounding step of section 6.12
 * (values taken from the scoring service as unrounded doubles, {@link
 * BigDecimal#BigDecimal(double)} of the exact value, half-even, 4 decimals) and the
 * display-currency conversion of section 7.7 (every money field is the unrounded native value times
 * the unrounded rate, rounded once; {@code *Native} fields are the rounded native values; value
 * scores are never converted). Flags are the scoring flags in scoring order followed by {@code
 * FX_STALE} and {@code FX_UNAVAILABLE}.
 */
final class RecommendationPresenter {

  private RecommendationPresenter() {}

  static RankedProduct ranked(Item item, PriceConversion conversion) {
    ProductMeta product = item.product();
    return new RankedProduct(
        product.id(),
        product.code(),
        item.rank(),
        product.brand(),
        product.name(),
        product.dosageForm(),
        provenance(product.provenance()),
        cost(item.cost(), conversion),
        Rounding.score(item.contentScore()),
        Rounding.score(item.contentScoreDisplay()),
        Rounding.score(item.valueScore()),
        Rounding.score(item.valueScoreDisplay()),
        item.customScore() == null ? null : Rounding.score(item.customScore()),
        flags(item.flags(), conversion),
        item.breakdown().stream().map(RecommendationPresenter::row).toList(),
        item.topContributors().stream().map(RecommendationPresenter::row).toList(),
        selectedOffer(product.selectedOffer()),
        false,
        List.of());
  }

  static ComparedProduct compared(
      ProductMeta product,
      NativeCost cost,
      double contentScore,
      double valueScore,
      List<NativeFlag> flags,
      PriceConversion conversion) {
    return new ComparedProduct(
        product.id(),
        product.code(),
        product.brand(),
        product.name(),
        product.dosageForm(),
        provenance(product.provenance()),
        cost(cost, conversion),
        Rounding.score(contentScore),
        Rounding.score(valueScore),
        flags(flags, conversion),
        selectedOffer(product.selectedOffer()));
  }

  static DeltaRow deltaRow(
      String code,
      NutrientCategory category,
      double amountA,
      double amountB,
      double deltaAmount,
      String unit,
      Double rA,
      Double rB,
      double uA,
      double uB,
      double weight,
      double deltaContribution,
      List<String> flags) {
    return new DeltaRow(
        code,
        category,
        Rounding.ratio(amountA),
        Rounding.ratio(amountB),
        Rounding.ratio(deltaAmount),
        unit,
        nullableRatio(rA),
        nullableRatio(rB),
        Rounding.score(uA),
        Rounding.score(uB),
        Rounding.score(weight),
        Rounding.score(deltaContribution),
        List.copyOf(flags));
  }

  /**
   * The explanation with money-typed parameters converted and {@code currency} rewritten to the
   * display currency; absent parameters stay absent.
   */
  static Explanation explanation(
      String key,
      Double deltaCostPerDay,
      Double deltaContent,
      Double marginalRate,
      Double mRef,
      Double ratio,
      PriceConversion conversion) {
    return new Explanation(
        key,
        new ExplanationParams(
            conversion.currency(),
            money(deltaCostPerDay, conversion),
            nullableRatio(deltaContent),
            money(marginalRate, conversion),
            money(mRef, conversion),
            nullableRatio(ratio)));
  }

  /** A money value in the display currency, or {@code null}. */
  static BigDecimal money(Double nativeValue, PriceConversion conversion) {
    return nativeValue == null ? null : converted(nativeValue, conversion);
  }

  /** {@code nativeValue} x rate, rounded once to 4 decimals. */
  static BigDecimal converted(double nativeValue, PriceConversion conversion) {
    if (!Double.isFinite(nativeValue)) {
      throw new IllegalArgumentException("money value is not finite");
    }
    BigDecimal value = conversion.convert(new BigDecimal(nativeValue));
    return value.signum() == 0 ? BigDecimal.ZERO.setScale(Rounding.SCALE) : value;
  }

  static Cost cost(NativeCost cost, PriceConversion conversion) {
    return new Cost(
        converted(cost.effectivePrice(), conversion),
        Rounding.money(cost.effectivePrice()),
        conversion.currency(),
        Rounding.ratio(cost.servingsPerContainer()),
        Rounding.ratio(cost.daysSupply()),
        converted(cost.costPerDay(), conversion),
        Rounding.money(cost.costPerDay()),
        cost.priceStale(),
        cost.outOfStock());
  }

  static List<Flag> flags(List<NativeFlag> scoring, PriceConversion conversion) {
    List<Flag> flags = new ArrayList<>();
    for (NativeFlag flag : scoring) {
      flags.add(new Flag(flag.code(), flag.nutrientCodes()));
    }
    if (conversion.fx().stale()) {
      flags.add(new Flag("FX_STALE", List.of()));
    }
    if (PriceConversion.UNAVAILABLE.equals(conversion.fx().status())) {
      flags.add(new Flag("FX_UNAVAILABLE", List.of()));
    }
    return List.copyOf(flags);
  }

  static BigDecimal nullableRatio(Double value) {
    return value == null ? null : Rounding.ratio(value);
  }

  private static BreakdownRow row(NativeRow row) {
    return new BreakdownRow(
        row.code(),
        row.category(),
        Rounding.ratio(row.amountPerDay()),
        Rounding.ratio(row.ulAmountPerDay()),
        row.unit(),
        nullableRatio(row.reference()),
        row.referenceSource(),
        nullableRatio(row.coverageRatio()),
        Rounding.score(row.utility()),
        Rounding.score(row.weight()),
        Rounding.score(row.contribution()),
        row.cappedAtUl(),
        row.cappedAtUMax(),
        row.ulBelowReference(),
        row.belowPresenceFloor());
  }

  private static Provenance provenance(ProvenanceView view) {
    return new Provenance(
        view.synthetic(), view.source(), view.sourceRef(), view.sourceEntryDate());
  }

  private static SelectedOffer selectedOffer(OfferSnapshot offer) {
    return new SelectedOffer(offer.retailer(), offer.url(), offer.checkedAt());
  }
}
