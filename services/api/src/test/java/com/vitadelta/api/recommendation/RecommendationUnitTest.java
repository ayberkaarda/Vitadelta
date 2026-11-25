package com.vitadelta.api.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitadelta.api.catalog.NutrientCategory;
import com.vitadelta.api.catalog.price.StockFilter;
import com.vitadelta.api.catalog.unit.Unit;
import com.vitadelta.api.common.problem.ProblemException;
import com.vitadelta.api.config.properties.RecommendationProperties;
import com.vitadelta.api.config.properties.ScoringProperties;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

/** Pure parts of the recommendation module: cache key, retry predicate, error mapping, settings. */
class RecommendationUnitTest {

  private static RankingQuery.Weights weights(String vitamin, Map<String, String> overrides) {
    Map<NutrientCategory, BigDecimal> categories = new EnumMap<>(NutrientCategory.class);
    categories.put(NutrientCategory.VITAMIN, new BigDecimal(vitamin));
    categories.put(NutrientCategory.MINERAL, new BigDecimal("1.0"));
    categories.put(NutrientCategory.COENZYME, new BigDecimal("0.8"));
    categories.put(NutrientCategory.OTHER, new BigDecimal("0.5"));
    TreeMap<String, BigDecimal> values = new TreeMap<>();
    overrides.forEach((k, v) -> values.put(k, new BigDecimal(v)));
    return new RankingQuery.Weights(categories, values);
  }

  private static RankingQuery query(
      RankingMode mode,
      BigDecimal lambda,
      RankingQuery.Weights weights,
      List<RankingQuery.RequiredNutrient> required) {
    return new RankingQuery(
        "TR", StockFilter.IN_STOCK_ONLY, mode, lambda, weights, required, false);
  }

  @Test
  void theCacheKeyIsTheSha256OfTheCanonicalQueryAndTheCatalogVersion() {
    RankingQuery base = query(RankingMode.VALUE, null, weights("1.0", Map.of()), List.of());
    assertThat(base.cacheKey(7)).matches("^[0-9a-f]{64}$").isEqualTo(base.cacheKey(7));
    assertThat(base.cacheKey(8)).isNotEqualTo(base.cacheKey(7));
    assertThat(query(RankingMode.VALUE, null, weights("1.00", Map.of()), List.of()).cacheKey(7))
        .isEqualTo(base.cacheKey(7));
    assertThat(query(RankingMode.VALUE, null, weights("1.5", Map.of()), List.of()).cacheKey(7))
        .isNotEqualTo(base.cacheKey(7));
    assertThat(query(RankingMode.PRICE, null, weights("1.0", Map.of()), List.of()).cacheKey(7))
        .isNotEqualTo(base.cacheKey(7));
    assertThat(
            new RankingQuery(
                    "DE",
                    StockFilter.IN_STOCK_ONLY,
                    RankingMode.VALUE,
                    null,
                    weights("1.0", Map.of()),
                    List.of(),
                    false)
                .cacheKey(7))
        .isNotEqualTo(base.cacheKey(7));
    assertThat(
            new RankingQuery(
                    "TR",
                    StockFilter.INCLUDE_OUT_OF_STOCK,
                    RankingMode.VALUE,
                    null,
                    weights("1.0", Map.of()),
                    List.of(),
                    false)
                .cacheKey(7))
        .isNotEqualTo(base.cacheKey(7));
    assertThat(
            new RankingQuery(
                    "TR",
                    StockFilter.IN_STOCK_ONLY,
                    RankingMode.VALUE,
                    null,
                    weights("1.0", Map.of()),
                    List.of(),
                    true)
                .cacheKey(7))
        .isNotEqualTo(base.cacheKey(7));
  }

  @Test
  void lambdaOverridesAndRequiredNutrientsArePartOfTheKeyInCanonicalOrder() {
    RankingQuery customHalf =
        query(RankingMode.CUSTOM, new BigDecimal("0.5"), weights("1.0", Map.of()), List.of());
    assertThat(
            query(RankingMode.CUSTOM, new BigDecimal("0.50"), weights("1.0", Map.of()), List.of())
                .cacheKey(1))
        .isEqualTo(customHalf.cacheKey(1));
    assertThat(
            query(RankingMode.CUSTOM, new BigDecimal("0.7"), weights("1.0", Map.of()), List.of())
                .cacheKey(1))
        .isNotEqualTo(customHalf.cacheKey(1));

    RankingQuery overrides =
        query(
            RankingMode.VALUE, null, weights("1.0", Map.of("VIT_C", "2", "ZINC", "0")), List.of());
    assertThat(
            query(
                    RankingMode.VALUE,
                    null,
                    weights("1.0", Map.of("ZINC", "0.0", "VIT_C", "2.0")),
                    List.of())
                .cacheKey(1))
        .isEqualTo(overrides.cacheKey(1));

    RankingQuery.RequiredNutrient zinc = new RankingQuery.RequiredNutrient("ZINC", null, Unit.MG);
    RankingQuery.RequiredNutrient vitD =
        new RankingQuery.RequiredNutrient("VIT_D", new BigDecimal("10.000"), Unit.MCG);
    RankingQuery required =
        query(RankingMode.VALUE, null, weights("1.0", Map.of()), List.of(zinc, vitD));
    assertThat(
            query(RankingMode.VALUE, null, weights("1.0", Map.of()), List.of(vitD, zinc))
                .cacheKey(1))
        .isEqualTo(required.cacheKey(1));
    assertThat(
            query(
                    RankingMode.VALUE,
                    null,
                    weights("1.0", Map.of()),
                    List.of(
                        zinc,
                        new RankingQuery.RequiredNutrient("VIT_D", new BigDecimal("11"), Unit.MCG)))
                .cacheKey(1))
        .isNotEqualTo(required.cacheKey(1));
  }

  @Test
  void weightsNeedEveryCategory() {
    assertThatThrownBy(() ->
            new RankingQuery.Weights(
                Map.of(NutrientCategory.VITAMIN, BigDecimal.ONE), new TreeMap<>()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void onlyConnectionErrorsAreRetried() {
    ConnectionErrors retryable = new ConnectionErrors();
    assertThat(retryable.test(new ResourceAccessException("x", new ConnectException("refused"))))
        .isTrue();
    assertThat(
            retryable.test(
                new ResourceAccessException("x", new HttpConnectTimeoutException("connect"))))
        .isTrue();
    assertThat(
            retryable.test(new ResourceAccessException("x", new UnknownHostException("scoring"))))
        .isTrue();
    assertThat(
            retryable.test(new ResourceAccessException("x", new NoRouteToHostException("route"))))
        .isTrue();
    assertThat(retryable.test(new ResourceAccessException("x", new HttpTimeoutException("read"))))
        .isFalse();
    assertThat(retryable.test(new ResourceAccessException("x"))).isFalse();
    assertThat(retryable.test(new IllegalStateException("x"))).isFalse();
  }

  @Test
  void scoringFieldsMapToPublicPathsOnlyForClientSuppliedValues() {
    assertThat(RecommendationService.publicField("weights")).contains("weights");
    assertThat(RecommendationService.publicField("ranking.lambda")).contains("ranking.lambda");
    assertThat(RecommendationService.publicField("weights.category_weights.OTHER"))
        .contains("weights.categories.OTHER");
    assertThat(RecommendationService.publicField("weights.overrides.VIT_B12"))
        .contains("weights.overrides.VIT_B12");
    assertThat(RecommendationService.publicField("required_nutrients[3].min_amount_per_day"))
        .contains("requiredNutrients[3].minAmountPerDay");
    assertThat(RecommendationService.publicField("required_nutrients[0].unit"))
        .isEqualTo(Optional.empty());
    assertThat(RecommendationService.publicField("candidates[0].effective_price")).isEmpty();
    assertThat(RecommendationService.publicField("baseline.product_id")).isEmpty();
  }

  @Test
  void scoringFailuresTranslateToTheContractOutcomes() {
    RuntimeException unavailable =
        RecommendationService.translate(new ScoringException.Unavailable("down", null));
    assertThat(unavailable).isInstanceOf(ProblemException.class);
    assertThat(((ProblemException) unavailable).code().code()).isEqualTo("scoring_unavailable");
    assertThat(RecommendationService.translate(new ScoringException.Failed("x", null)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(RecommendationService.translate(new ScoringException.Rejected(List.of(), null)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            RecommendationService.translate(
                new ScoringException.Rejected(
                    List.of(new ScoringException.FieldError("weights", "Not A Code")), null)))
        .isInstanceOf(IllegalStateException.class);
    RuntimeException other = new IllegalArgumentException("x");
    assertThat(RecommendationService.translate(other)).isSameAs(other);
  }

  @Test
  void settingsAreValidated() {
    ScoringProperties scoring =
        new ScoringProperties(
            " http://scoring:8000/ ", Duration.ofMillis(500), Duration.ofSeconds(2), 16);
    assertThat(scoring.apiUrl()).isEqualTo("http://scoring:8000/internal/v1");
    for (String invalid :
        List.of("", "scoring:8000", "ftp://scoring", "http://scoring?x=1", "http://[bad")) {
      assertThatThrownBy(() ->
              new ScoringProperties(invalid, Duration.ofMillis(500), Duration.ofSeconds(2), 16))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () -> new ScoringProperties(null, Duration.ofMillis(500), Duration.ofSeconds(2), 16))
        .hasMessageContaining("VITADELTA_SCORING_BASE_URL");
    assertThatThrownBy(
            () -> new ScoringProperties("http://s", Duration.ZERO, Duration.ofSeconds(2), 16))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            new ScoringProperties("http://s", Duration.ofMillis(500), Duration.ofSeconds(2), 0))
        .hasMessageContaining("max-concurrent-calls");
    assertThatThrownBy(
            () -> new RecommendationProperties(Duration.ofMinutes(10), 0, 100, 10, 30, 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecommendationProperties(Duration.ZERO, 500, 100_000, 10, 30, 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new RecommendationProperties(Duration.ofMinutes(10), 500, 499, 10, 30, 2))
        .hasMessageContaining("cache-max-ranked-items");
    assertThatThrownBy(
            () -> new RecommendationProperties(Duration.ofMinutes(10), 500, 500, 0, 30, 2))
        .hasMessageContaining("max-products-per-country");
    assertThat(
            new RecommendationProperties(Duration.ofMinutes(10), 500, 100_000, 10, 30, 2)
                .minimumRankingWeight())
        .isEqualTo(200);
  }
}
