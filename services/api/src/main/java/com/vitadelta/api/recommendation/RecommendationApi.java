package com.vitadelta.api.recommendation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vitadelta.api.catalog.DosageForm;
import com.vitadelta.api.catalog.NutrientCategory;
import com.vitadelta.api.catalog.web.ProductPresenter.Cost;
import com.vitadelta.api.catalog.web.ProductPresenter.Provenance;
import com.vitadelta.api.catalog.web.ProductPresenter.SelectedOffer;
import com.vitadelta.api.common.watch.WatchState;
import com.vitadelta.api.common.watch.WatchedAlert;
import com.vitadelta.api.entitlement.PlanCode;
import com.vitadelta.api.entitlement.PriceConversion;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Request and response bodies of {@code POST /recommendations/query} and {@code POST
 * /recommendations/compare} (contract {@code contracts/public-api.openapi.yaml}). Requests are
 * bound leniently (every member optional, enumerations as text) so that every invalid value is
 * reported as a field error with its JSON path; responses always carry every member except the
 * explanation parameters, whose {@code null} values are omitted.
 */
public final class RecommendationApi {

  private RecommendationApi() {}

  // ---------- Requests ----------

  /** Contract schema {@code RecommendationQueryRequest}. */
  public record QueryRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String country,
      @Schema(allowableValues = {"IN_STOCK_ONLY", "INCLUDE_OUT_OF_STOCK"}) String stockFilter,
      RankingInput ranking,
      WeightsInput weights,
      List<RequiredNutrientInput> requiredNutrients,
      Boolean excludeExceedingUl,
      String displayCurrency,
      Integer page,
      Integer pageSize) {}

  /** Contract schema {@code Ranking}. */
  public record RankingInput(
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              allowableValues = {"PRICE", "RICHNESS", "VALUE", "CUSTOM"})
          String mode,
      BigDecimal lambda) {}

  /** Contract schema {@code Weights}. */
  public record WeightsInput(
      @Schema(
              implementation = CategoryWeightsInput.class,
              requiredMode = Schema.RequiredMode.NOT_REQUIRED)
          Map<String, BigDecimal> categories,
      @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) Map<String, BigDecimal> overrides) {}

  /** Contract schema {@code CategoryWeights} (documentation of the {@code categories} keys). */
  public record CategoryWeightsInput(
      @Schema(name = "VITAMIN", requiredMode = Schema.RequiredMode.NOT_REQUIRED) BigDecimal vitamin,
      @Schema(name = "MINERAL", requiredMode = Schema.RequiredMode.NOT_REQUIRED) BigDecimal mineral,
      @Schema(name = "COENZYME", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
          BigDecimal coenzyme,
      @Schema(name = "OTHER", requiredMode = Schema.RequiredMode.NOT_REQUIRED) BigDecimal other) {}

  /** Contract schema {@code RequiredNutrient}. */
  public record RequiredNutrientInput(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
      BigDecimal minAmountPerDay,
      @Schema(allowableValues = {"mg", "mcg", "g", "IU"}) String unit) {}

  /** Contract schema {@code CompareRequest}. */
  public record CompareRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String country,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String baselineProductId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String candidateProductId,
      @Schema(allowableValues = {"IN_STOCK_ONLY", "INCLUDE_OUT_OF_STOCK"}) String stockFilter,
      WeightsInput weights,
      String displayCurrency) {}

  // ---------- Query response ----------

  /** Contract schema {@code RecommendationQueryResponse}. */
  public record QueryResponse(QueryMeta meta, List<RankedProduct> items) {}

  /** Contract schema {@code QueryMeta}. */
  public record QueryMeta(
      String country,
      String nativeCurrency,
      String currency,
      PriceConversion.Fx fx,
      long catalogVersion,
      int marketSize,
      BigDecimal mRef,
      ExcludedCounts excluded,
      Visibility visibility,
      @Schema(oneOf = {AnonymousPrincipal.class, UserPrincipal.class}) Principal principal,
      int page,
      int pageSize,
      int total) {}

  /** Contract schema {@code ExcludedCounts}. */
  public record ExcludedCounts(int noStock, int noOffer, int requiredNutrients, int exceedsUl) {}

  /** Contract schema {@code Visibility}. */
  public record Visibility(
      int shown,
      int total,
      int locked,
      @Schema(
              allowableValues = {"SIGN_UP_REQUIRED"},
              types = {"string", "null"})
          String reason) {

    /** {@code reason} when locked items exist. */
    public static final String SIGN_UP_REQUIRED = "SIGN_UP_REQUIRED";
  }

  /** Contract schema {@code PrincipalSummary}. */
  public sealed interface Principal permits AnonymousPrincipal, UserPrincipal {}

  /** Contract schema {@code AnonymousPrincipal}. */
  public record AnonymousPrincipal(
      @Schema(allowableValues = {"ANONYMOUS"}) String type, int queriesUsed, int queriesLimit)
      implements Principal {

    public AnonymousPrincipal(int queriesUsed, int queriesLimit) {
      this("ANONYMOUS", queriesUsed, queriesLimit);
    }
  }

  /** Contract schema {@code UserPrincipal}. */
  public record UserPrincipal(
      @Schema(allowableValues = {"USER", "ADMIN"}) String type, PlanCode plan)
      implements Principal {}

  /** Contract schema {@code RankedProduct}. */
  public record RankedProduct(
      UUID productId,
      String code,
      int rank,
      String brand,
      String name,
      DosageForm dosageForm,
      Provenance provenance,
      Cost cost,
      BigDecimal contentScore,
      BigDecimal contentScoreDisplay,
      BigDecimal valueScore,
      BigDecimal valueScoreDisplay,
      BigDecimal customScore,
      List<Flag> flags,
      List<BreakdownRow> breakdown,
      List<BreakdownRow> topContributors,
      SelectedOffer selectedOffer,
      boolean saved,
      List<WatchedAlert> alerts) {

    /** This item with the caller's {@code saved} and {@code alerts}. */
    public RankedProduct withWatchState(WatchState state) {
      return new RankedProduct(
          productId,
          code,
          rank,
          brand,
          name,
          dosageForm,
          provenance,
          cost,
          contentScore,
          contentScoreDisplay,
          valueScore,
          valueScoreDisplay,
          customScore,
          flags,
          breakdown,
          topContributors,
          selectedOffer,
          state.saved(),
          state.alerts());
    }
  }

  /** Contract schema {@code BreakdownRow}. */
  public record BreakdownRow(
      String code,
      NutrientCategory category,
      BigDecimal amountPerDay,
      BigDecimal ulAmountPerDay,
      @Schema(allowableValues = {"mg", "mcg"}) String unit,
      BigDecimal reference,
      @Schema(allowableValues = {"REGULATORY", "MARKET_MEDIAN", "PRESENCE_ONLY"})
          String referenceSource,
      BigDecimal coverageRatio,
      BigDecimal utility,
      BigDecimal weight,
      BigDecimal contribution,
      boolean cappedAtUl,
      boolean cappedAtUMax,
      boolean ulBelowReference,
      boolean belowPresenceFloor) {}

  // ---------- Compare response ----------

  /** Contract schema {@code CompareResponse}. */
  public record CompareResponse(
      CompareMeta meta,
      boolean swapped,
      @Schema(
              allowableValues = {
                "WORTH_IT",
                "MARGINAL",
                "NOT_WORTH_IT",
                "DOMINATES",
                "EQUIVALENT",
                "INSUFFICIENT_DATA"
              })
          String verdict,
      ComparedProduct productA,
      ComparedProduct productB,
      BigDecimal deltaCost,
      BigDecimal deltaCostNative,
      BigDecimal deltaContent,
      BigDecimal marginalRate,
      BigDecimal mRef,
      BigDecimal ratio,
      List<DeltaRow> rows,
      Explanation explanation) {}

  /** Contract schema {@code CompareMeta}. */
  public record CompareMeta(
      String country,
      String nativeCurrency,
      String currency,
      PriceConversion.Fx fx,
      long catalogVersion,
      int marketSize) {}

  /** Contract schema {@code ComparedProduct}. */
  public record ComparedProduct(
      UUID productId,
      String code,
      String brand,
      String name,
      DosageForm dosageForm,
      Provenance provenance,
      Cost cost,
      BigDecimal contentScore,
      BigDecimal valueScore,
      List<Flag> flags,
      SelectedOffer selectedOffer) {}

  /** Contract schema {@code DeltaRow}. */
  public record DeltaRow(
      String code,
      NutrientCategory category,
      BigDecimal amountA,
      BigDecimal amountB,
      BigDecimal deltaAmount,
      @Schema(allowableValues = {"mg", "mcg"}) String unit,
      BigDecimal rA,
      BigDecimal rB,
      BigDecimal uA,
      BigDecimal uB,
      BigDecimal weight,
      BigDecimal deltaContribution,
      @ArraySchema(
              schema =
                  @Schema(
                      allowableValues = {
                        "NEW_IN_B",
                        "REMOVED_IN_B",
                        "DIMINISHING",
                        "ABOVE_CEILING_NO_CREDIT",
                        "ABOVE_UL_NO_CREDIT"
                      }))
          List<String> flags) {}

  /** Contract schema {@code Explanation}. */
  public record Explanation(
      @Schema(
              allowableValues = {
                "explain.verdict.worth_it",
                "explain.verdict.marginal",
                "explain.verdict.not_worth_it",
                "explain.verdict.dominates",
                "explain.verdict.equivalent",
                "explain.verdict.insufficient_data"
              })
          String key,
      ExplanationParams params) {}

  /**
   * Contract schema {@code ProductFlag}: scoring flags first, then {@code FX_STALE}, {@code
   * FX_UNAVAILABLE}.
   */
  public record Flag(
      @Schema(
              allowableValues = {
                "EXCEEDS_UL",
                "UL_FORM_DEPENDENT",
                "CONVERSION_ASSUMED",
                "PRICE_STALE",
                "OUT_OF_STOCK",
                "MARKET_MEDIAN_REFERENCE_USED",
                "PRESENCE_ONLY_NUTRIENTS",
                "BELOW_PRESENCE_FLOOR",
                "FX_STALE",
                "FX_UNAVAILABLE",
                "UNAVAILABLE_IN_COUNTRY"
              })
          String code,
      List<String> nutrientCodes) {

    public Flag {
      nutrientCodes = List.copyOf(nutrientCodes);
    }
  }

  /**
   * Contract schema {@code ExplanationParams}: {@code null} parameters are omitted; money-typed
   * parameters are in {@code currency}.
   */
  public record ExplanationParams(
      String currency,
      @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal deltaCostPerDay,
      @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal deltaContent,
      @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal marginalRate,
      @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal mRef,
      @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal ratio) {}
}
