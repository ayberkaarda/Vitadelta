package com.vitadelta.api.recommendation;

import com.vitadelta.api.catalog.NutrientCategory;
import com.vitadelta.api.catalog.price.EffectivePrice;
import com.vitadelta.api.catalog.price.PriceResolution;
import com.vitadelta.api.catalog.price.StockFilter;
import com.vitadelta.api.catalog.read.CatalogViews.CatalogProduct;
import com.vitadelta.api.catalog.read.CatalogViews.NutrientView;
import com.vitadelta.api.catalog.read.CatalogViews.ProductNutrientView;
import com.vitadelta.api.catalog.read.CatalogViews.ReferenceAmount;
import com.vitadelta.api.catalog.read.CatalogViews.UpperLimitView;
import com.vitadelta.api.catalog.read.CountryCatalog;
import com.vitadelta.api.catalog.read.MarketProfile;
import com.vitadelta.api.recommendation.NativeRanking.ProductMeta;
import com.vitadelta.api.recommendation.scoring.model.Candidate;
import com.vitadelta.api.recommendation.scoring.model.CandidateNutrient;
import com.vitadelta.api.recommendation.scoring.model.CanonicalUnit;
import com.vitadelta.api.recommendation.scoring.model.CategoryWeights;
import com.vitadelta.api.recommendation.scoring.model.MarketProfileNutrient;
import com.vitadelta.api.recommendation.scoring.model.MarketProfileProduct;
import com.vitadelta.api.recommendation.scoring.model.Ranking;
import com.vitadelta.api.recommendation.scoring.model.ReferenceValue;
import com.vitadelta.api.recommendation.scoring.model.RegistryNutrient;
import com.vitadelta.api.recommendation.scoring.model.RequiredNutrient;
import com.vitadelta.api.recommendation.scoring.model.UlBasis;
import com.vitadelta.api.recommendation.scoring.model.UpperLimit;
import com.vitadelta.api.recommendation.scoring.model.Weights;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Translation of catalog data into the scoring contract (section 6.1, contract-to-request mapping
 * of the Phase 4 notes): the registry block, reference values and upper limits of the country, the
 * market profile, and the candidate set under a stock filter with the products that are excluded
 * before scoring ({@code noStock}, {@code noOffer}).
 */
final class ScoringInputs {

  private ScoringInputs() {}

  /**
   * The scoreable candidates of a country under a stock filter (section 7.4).
   *
   * @param candidates in catalog order (by id), with their scoring model
   * @param products metadata of every candidate, by id
   */
  record CandidateSet(
      List<Candidate> candidates,
      Map<UUID, ProductMeta> products,
      Map<UUID, Candidate> byId,
      int noStock,
      int noOffer) {}

  /** Resolves the effective price of every in-market product and keeps the priced ones. */
  static CandidateSet candidates(
      CountryCatalog catalog,
      StockFilter filter,
      Instant now,
      BiFunction<CatalogProduct, StockFilter, PriceResolution> resolve) {
    List<Candidate> candidates = new ArrayList<>();
    Map<UUID, ProductMeta> products = new LinkedHashMap<>();
    Map<UUID, Candidate> byId = new LinkedHashMap<>();
    int noStock = 0;
    int noOffer = 0;
    for (CatalogProduct product : catalog.products()) {
      PriceResolution resolution = resolve.apply(product, filter);
      if (!resolution.isPriced()) {
        if (resolution.exclusion() == PriceResolution.Exclusion.NO_STOCK) {
          noStock++;
        } else {
          noOffer++;
        }
        continue;
      }
      EffectivePrice price = resolution.effective();
      Candidate candidate = candidate(product, price);
      candidates.add(candidate);
      byId.put(product.id(), candidate);
      products.put(product.id(), ProductMeta.of(product, price.selected()));
    }
    return new CandidateSet(candidates, products, byId, noStock, noOffer);
  }

  static Candidate candidate(CatalogProduct product, EffectivePrice price) {
    Candidate candidate =
        new Candidate()
            .productId(product.id().toString())
            .unitsPerContainer(product.unitsPerContainer())
            .unitsPerServing(product.unitsPerServing())
            .servingsPerDay(product.servingsPerDay())
            .effectivePrice(price.price())
            .currency(price.currency())
            .priceStale(price.priceStale())
            .outOfStock(price.outOfStock());
    List<CandidateNutrient> nutrients = new ArrayList<>();
    for (ProductNutrientView nutrient : product.nutrients()) {
      nutrients.add(
          new CandidateNutrient()
              .code(nutrient.code())
              .amountPerServing(nutrient.amountPerServing())
              .unit(CanonicalUnit.fromValue(nutrient.unit().symbol()))
              .ulAmountPerServing(nutrient.ulAmountPerServing())
              .formCode(nutrient.formCode())
              .conversionAssumed(nutrient.conversionAssumed()));
    }
    return candidate.nutrients(nutrients);
  }

  /** The registry block: the whole registry (it covers every code of the request). */
  static List<RegistryNutrient> registry(List<NutrientView> nutrients) {
    return nutrients.stream()
        .map(n ->
            new RegistryNutrient()
                .code(n.code())
                .category(
                    com.vitadelta.api.recommendation.scoring.model.NutrientCategory.fromValue(
                        n.category().name()))
                .canonicalUnit(CanonicalUnit.fromValue(n.canonicalUnit().symbol()))
                .presenceFloorPerDay(n.presenceFloorPerDay()))
        .toList();
  }

  static List<ReferenceValue> referenceValues(List<ReferenceAmount> values) {
    return values.stream()
        .map(v ->
            new ReferenceValue()
                .nutrientCode(v.nutrientCode())
                .amount(v.amount())
                .unit(CanonicalUnit.fromValue(v.unit().symbol())))
        .toList();
  }

  /** Upper-limit rows including exempt rows; citation notes are not sent. */
  static List<UpperLimit> upperLimits(List<UpperLimitView> limits) {
    return limits.stream()
        .map(l ->
            new UpperLimit()
                .nutrientCode(l.nutrientCode())
                .formCode(l.formCode())
                .amount(l.amount())
                .unit(l.exempt() ? null : CanonicalUnit.fromValue(l.unit().symbol()))
                .basis(l.exempt() ? null : UlBasis.fromValue(l.basis().name())))
        .toList();
  }

  static com.vitadelta.api.recommendation.scoring.model.MarketProfile marketProfile(
      MarketProfile profile) {
    List<MarketProfileProduct> products = new ArrayList<>();
    for (MarketProfile.Product product : profile.products()) {
      products.add(
          new MarketProfileProduct()
              .productId(product.productId().toString())
              .nutrients(
                  product.nutrients().stream()
                      .map(n ->
                          new MarketProfileNutrient().code(n.code()).amountPerDay(n.amountPerDay()))
                      .toList()));
    }
    return new com.vitadelta.api.recommendation.scoring.model.MarketProfile()
        .catalogVersion(profile.catalogVersion())
        .products(products);
  }

  static Weights weights(RankingQuery.Weights weights) {
    Map<NutrientCategory, java.math.BigDecimal> categories = weights.categories();
    return new Weights()
        .categoryWeights(
            new CategoryWeights()
                .VITAMIN(categories.get(NutrientCategory.VITAMIN))
                .MINERAL(categories.get(NutrientCategory.MINERAL))
                .COENZYME(categories.get(NutrientCategory.COENZYME))
                .OTHER(categories.get(NutrientCategory.OTHER)))
        .overrides(new LinkedHashMap<>(weights.overrides()));
  }

  static Ranking ranking(RankingQuery query) {
    return new Ranking()
        .mode(
            com.vitadelta.api.recommendation.scoring.model.RankingMode.fromValue(
                query.mode().name()))
        .lambda(query.mode() == RankingMode.CUSTOM ? query.lambda() : null);
  }

  static List<RequiredNutrient> requiredNutrients(RankingQuery query) {
    return query.requiredNutrients().stream()
        .map(r ->
            new RequiredNutrient()
                .code(r.code())
                .minAmountPerDay(r.minAmountPerDay())
                .unit(CanonicalUnit.fromValue(r.unit().symbol())))
        .toList();
  }
}
