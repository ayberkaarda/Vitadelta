package com.vitadelta.api.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vitadelta.api.catalog.NutrientCategory;
import com.vitadelta.api.catalog.price.EffectivePriceResolver;
import com.vitadelta.api.catalog.read.CatalogReadService;
import com.vitadelta.api.catalog.read.CatalogTooLargeException;
import com.vitadelta.api.catalog.read.CatalogViews.CountryView;
import com.vitadelta.api.catalog.read.CatalogViews.NutrientView;
import com.vitadelta.api.catalog.unit.Unit;
import com.vitadelta.api.catalog.unit.UnitConversionService;
import com.vitadelta.api.catalog.web.DisplayCurrencyResolver;
import com.vitadelta.api.common.problem.FieldViolation;
import com.vitadelta.api.common.problem.ProblemException;
import com.vitadelta.api.common.ratelimit.RateLimiter;
import com.vitadelta.api.common.web.ClientIpResolver;
import com.vitadelta.api.config.properties.RecommendationProperties;
import com.vitadelta.api.config.properties.ScoringProperties;
import com.vitadelta.api.entitlement.EntitlementService;
import com.vitadelta.api.identity.AnonymousIdentityService;
import com.vitadelta.api.identity.AnonymousIdentityService.AnonymousIdentity;
import com.vitadelta.api.recommendation.RecommendationApi.QueryRequest;
import com.vitadelta.api.recommendation.RecommendationApi.RankingInput;
import com.vitadelta.api.recommendation.RecommendationApi.RequiredNutrientInput;
import com.vitadelta.api.recommendation.RecommendationApi.WeightsInput;
import com.vitadelta.api.recommendation.scoring.ApiClient;
import com.vitadelta.api.recommendation.scoring.api.ComparisonApi;
import com.vitadelta.api.recommendation.scoring.api.ScoringApi;
import com.vitadelta.api.recommendation.scoring.model.ScoreRequest;
import com.vitadelta.api.recommendation.scoring.model.ScoreResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

/**
 * Hardening of the recommendation module: bounded request numbers and cache keys, a refund on every
 * failed outcome, no scoring text in public errors, body-read failures as unavailability, the
 * bulkhead, the catalog ceiling and the weighted ranking cache.
 */
class RecommendationHardeningTest {

  private static QueryRequest query(
      Map<String, BigDecimal> categories,
      Map<String, BigDecimal> overrides,
      RankingInput ranking,
      List<RequiredNutrientInput> required) {
    return new QueryRequest(
        "TR",
        null,
        ranking,
        new WeightsInput(categories, overrides),
        required,
        null,
        null,
        null,
        null);
  }

  private static List<String> rejectedFields(QueryRequest request) {
    try {
      RequestValidation.parseQuery(request);
    } catch (ProblemException e) {
      return e.errors().stream().map(v -> v.field() + ":" + v.code()).toList();
    }
    return List.of();
  }

  // ---------- A: request numbers ----------

  @Test
  void numbersBeyondThePrecisionBoundAreRejectedAtValidationWithoutExpandingThem() {
    BigDecimal tiny = new BigDecimal("1e-1073741824");
    BigDecimal huge = new BigDecimal("1e+1073741824");
    long started = System.nanoTime();
    assertThat(rejectedFields(query(Map.of("VITAMIN", tiny), null, null, null)))
        .containsExactly("weights.categories.VITAMIN:out_of_range");
    assertThat(rejectedFields(query(null, Map.of("VIT_C", tiny), null, null)))
        .containsExactly("weights.overrides.VIT_C:out_of_range");
    assertThat(rejectedFields(query(null, null, new RankingInput("CUSTOM", tiny), null)))
        .containsExactly("ranking.lambda:out_of_range");
    assertThat(
            rejectedFields(
                query(null, null, null, List.of(new RequiredNutrientInput("VIT_C", huge, "mg")))))
        .containsExactly("requiredNutrients[0].minAmountPerDay:out_of_range");
    assertThat(
            rejectedFields(
                query(null, null, null, List.of(new RequiredNutrientInput("VIT_C", tiny, "mg")))))
        .containsExactly("requiredNutrients[0].minAmountPerDay:out_of_range");
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));

    assertThat(
            rejectedFields(
                query(Map.of("VITAMIN", new BigDecimal("1.123456789")), null, null, null)))
        .isEmpty();
    assertThat(
            rejectedFields(
                query(Map.of("VITAMIN", new BigDecimal("1.1234567891")), null, null, null)))
        .containsExactly("weights.categories.VITAMIN:out_of_range");
    assertThat(
            rejectedFields(
                query(Map.of("VITAMIN", new BigDecimal("0E-1073741824")), null, null, null)))
        .isEmpty();
    assertThat(
            rejectedFields(
                query(
                    null,
                    null,
                    null,
                    List.of(
                        new RequiredNutrientInput("VIT_C", new BigDecimal("999999999999"), "mg")))))
        .isEmpty();
    assertThat(
            rejectedFields(
                query(
                    null,
                    null,
                    null,
                    List.of(
                        new RequiredNutrientInput(
                            "VIT_C", new BigDecimal("1000000000000"), "mg")))))
        .containsExactly("requiredNutrients[0].minAmountPerDay:out_of_range");
  }

  @Test
  void theCanonicalNumberIsBoundedByTheDigitsWhateverTheExponent() {
    assertThat(RankingQuery.number(new BigDecimal("1e-1073741824"))).isEqualTo("1e-1073741824");
    assertThat(RankingQuery.number(new BigDecimal("1e+1073741824"))).isEqualTo("1e1073741824");
    assertThat(RankingQuery.number(new BigDecimal("1.50"))).isEqualTo("15e-1");
    assertThat(RankingQuery.number(new BigDecimal("2.000"))).isEqualTo("2");
    assertThat(RankingQuery.number(new BigDecimal("1E+3"))).isEqualTo("1e3");
    assertThat(RankingQuery.number(new BigDecimal("0E-99"))).isEqualTo("0");
    assertThat(RankingQuery.number(new BigDecimal("0.5")))
        .isEqualTo(RankingQuery.number(new BigDecimal("0.500")));
  }

  // ---------- A, G: refund and catalog ceiling ----------

  private record Harness(
      RecommendationService service, EntitlementService entitlements, CatalogReadService catalog) {}

  private static Harness harness() {
    CatalogReadService catalog = mock(CatalogReadService.class);
    EntitlementService entitlements = mock(EntitlementService.class);
    AnonymousIdentityService identities = mock(AnonymousIdentityService.class);
    when(identities.resolve(any(), any())).thenReturn(new AnonymousIdentity("id-hash", false));
    when(catalog.country("TR"))
        .thenReturn(Optional.of(new CountryView("TR", "Turkiye", "TRY", "EU_NRV", "EFSA", "tr")));
    when(catalog.catalogVersion()).thenReturn(1L);
    when(catalog.nutrients())
        .thenReturn(
            List.of(
                new NutrientView(
                    "VIT_C",
                    "Vitamin C",
                    NutrientCategory.VITAMIN,
                    Unit.MG,
                    null,
                    1,
                    List.of(),
                    List.of())));
    when(entitlements.reserveAnonymousQuery("id-hash")).thenReturn(1);
    when(entitlements.anonymousQueryLimit()).thenReturn(3);
    RecommendationProperties properties =
        new RecommendationProperties(Duration.ofMinutes(10), 500, 100_000, 5000);
    RecommendationService service =
        new RecommendationService(
            catalog,
            mock(UnitConversionService.class),
            mock(EffectivePriceResolver.class),
            mock(DisplayCurrencyResolver.class),
            entitlements,
            identities,
            mock(RateLimiter.class),
            mock(ClientIpResolver.class),
            mock(ScoringGateway.class),
            (userId, country, ids) -> java.util.Map.of(),
            new RecommendationCaches(properties, new SimpleMeterRegistry()),
            properties,
            new SimpleMeterRegistry());
    return new Harness(service, entitlements, catalog);
  }

  private static QueryRequest plainQuery() {
    return new QueryRequest("TR", null, null, null, null, null, null, null, null);
  }

  @Test
  void anErrorAfterTheReservationRefundsTheQueryExactlyOnce() {
    Harness h = harness();
    when(h.catalog().loadCountry(anyString(), anyInt())).thenThrow(new OutOfMemoryError("test"));
    when(h.catalog().loadCountry(anyString())).thenThrow(new OutOfMemoryError("test"));
    assertThatThrownBy(() ->
            h.service()
                .query(
                    plainQuery(),
                    null,
                    new MockHttpServletRequest(),
                    new MockHttpServletResponse()))
        .isInstanceOf(OutOfMemoryError.class);
    verify(h.entitlements(), times(1)).reserveAnonymousQuery("id-hash");
    verify(h.entitlements(), times(1)).refundAnonymousQuery("id-hash");
  }

  @Test
  void aCatalogAboveTheCeilingIsScoringUnavailableAndRefunded() {
    Harness h = harness();
    when(h.catalog().loadCountry("TR", 5000))
        .thenThrow(new CatalogTooLargeException("TR", 5001, 5000));
    assertThatThrownBy(() ->
            h.service()
                .query(
                    plainQuery(),
                    null,
                    new MockHttpServletRequest(),
                    new MockHttpServletResponse()))
        .isInstanceOfSatisfying(
            ProblemException.class,
            e -> assertThat(e.code().code()).isEqualTo("scoring_unavailable"));
    verify(h.entitlements(), times(1)).refundAnonymousQuery("id-hash");
  }

  // ---------- B: scoring rejections ----------

  @Test
  void aForwardedScoringRejectionCarriesOnlyTheCodeTheFieldAndAnApiMessage() {
    String hostile = "Traceback at com.vitadelta.scoring scoring-internal.svc:8000 secret@db";
    RuntimeException mapped =
        RecommendationService.translate(
            new ScoringException.Rejected(
                List.of(
                    new ScoringException.FieldError(
                        "weights.category_weights.VITAMIN", "out_of_range"),
                    new ScoringException.FieldError("ranking.lambda", "value_out_of_range")),
                null));
    assertThat(mapped).isInstanceOf(ProblemException.class);
    List<FieldViolation> errors = ((ProblemException) mapped).errors();
    assertThat(errors)
        .containsExactly(
            new FieldViolation(
                "weights.categories.VITAMIN",
                "out_of_range",
                "weights.categories.VITAMIN is outside the accepted range."),
            new FieldViolation(
                "ranking.lambda",
                "value_out_of_range",
                "ranking.lambda is outside the accepted range."));
    RuntimeException unknown =
        RecommendationService.translate(
            new ScoringException.Rejected(
                List.of(new ScoringException.FieldError("weights", "duplicate_product_id")), null));
    assertThat(unknown).isInstanceOf(IllegalStateException.class).hasNoCause();
    RuntimeException hostileCode =
        RecommendationService.translate(
            new ScoringException.Rejected(
                List.of(new ScoringException.FieldError("weights", hostile)), null));
    assertThat(hostileCode).isInstanceOf(IllegalStateException.class).hasNoCause();
    assertThat(hostileCode.getMessage()).doesNotContain("Traceback");
    assertThat(new ScoringException.FieldError("x".repeat(5000), "y".repeat(5000)).field())
        .hasSize(ScoringException.FieldError.MAX_TEXT);
  }

  // ---------- C: body-read failures ----------

  private static ScoringGateway gateway(ScoringApi api, int maxConcurrent) {
    CircuitBreakerRegistry breakers =
        CircuitBreakerRegistry.of(
            CircuitBreakerConfig.custom()
                .recordExceptions(ResourceAccessException.class, HttpServerErrorException.class)
                .ignoreExceptions(HttpClientErrorException.class)
                .build());
    RetryRegistry retries =
        RetryRegistry.of(
            RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(1))
                .retryOnException(new ConnectionErrors())
                .build());
    return new ScoringGateway(
        api,
        mock(ComparisonApi.class),
        mock(ApiClient.class),
        breakers,
        retries,
        new SimpleMeterRegistry(),
        new ScoringProperties(
            "http://scoring:8000", Duration.ofMillis(500), Duration.ofSeconds(2), maxConcurrent));
  }

  @Test
  void aFailureWhileReadingTheBodyIsUnavailableRecordedByTheBreakerAndNotRetried() {
    ScoringApi api = mock(ScoringApi.class);
    when(api.scoreCandidates(any(), any()))
        .thenThrow(
            new RestClientException("Error while extracting response", new IOException("closed")));
    ScoringGateway gateway = gateway(api, 4);
    assertThatThrownBy(() -> gateway.score(new ScoreRequest()))
        .isInstanceOf(ScoringException.Unavailable.class);
    verify(api, times(1)).scoreCandidates(any(), any());
    assertThat(gateway.circuitBreaker().getMetrics().getNumberOfFailedCalls()).isEqualTo(1);

    assertThatThrownBy(() ->
            ScoringGateway.classified(() -> {
              throw new RestClientException(
                  "x", new IllegalStateException(new SocketTimeoutException("read")));
            }))
        .isInstanceOf(ResourceAccessException.class);
    RestClientException parse = new RestClientException("not json");
    assertThatThrownBy(() ->
            ScoringGateway.classified(() -> {
              throw parse;
            }))
        .isSameAs(parse);
    HttpServerErrorException status =
        HttpServerErrorException.create(
            HttpStatus.BAD_GATEWAY,
            "x",
            new org.springframework.http.HttpHeaders(),
            new byte[0],
            null);
    assertThatThrownBy(() ->
            ScoringGateway.classified(() -> {
              throw status;
            }))
        .isSameAs(status);
  }

  // ---------- G: bulkhead and cache weight ----------

  @Test
  void callsBeyondTheBulkheadFailAtOnceAndThePermitIsReturned() throws Exception {
    ScoringApi api = mock(ScoringApi.class);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ScoreResponse answer = new ScoreResponse();
    when(api.scoreCandidates(any(), any()))
        .thenAnswer(invocation -> {
          entered.countDown();
          release.await(10, TimeUnit.SECONDS);
          return answer;
        });
    ScoringGateway gateway = gateway(api, 1);
    assertThat(gateway.maxConcurrentCalls()).isEqualTo(1);
    CompletableFuture<ScoreResponse> first =
        CompletableFuture.supplyAsync(() -> gateway.score(new ScoreRequest()));
    assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
    long started = System.nanoTime();
    assertThatThrownBy(() -> gateway.score(new ScoreRequest()))
        .isInstanceOf(ScoringException.Unavailable.class);
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
    verify(api, times(1)).scoreCandidates(any(), any());
    release.countDown();
    assertThat(first.get(10, TimeUnit.SECONDS)).isSameAs(answer);
    assertThat(gateway.score(new ScoreRequest())).isSameAs(answer);
  }

  private static NativeRanking ranking(int items) {
    return new NativeRanking(
        1L,
        "TR",
        "TRY",
        items,
        null,
        0,
        0,
        0,
        0,
        Collections.nCopies(items, mock(NativeRanking.Item.class)));
  }

  @Test
  void theRankingCacheIsBoundedByTheRankedProductsItHolds() {
    RecommendationCaches caches =
        new RecommendationCaches(
            new RecommendationProperties(Duration.ofMinutes(10), 2, 10, 100),
            new SimpleMeterRegistry());
    for (String key : List.of("a", "b", "c")) {
      caches.putRanking(key, ranking(8));
    }
    assertThat(caches.rankingWeight()).isLessThanOrEqualTo(10);
    long held =
        List.of("a", "b", "c").stream()
            .mapToLong(key -> caches.ranking(key).map(NativeRanking::total).orElse(0))
            .sum();
    assertThat(held).as("ranked products held").isLessThanOrEqualTo(10);
    assertThat(caches.rankingWeight()).isLessThanOrEqualTo(10);
    RecommendationCaches small =
        new RecommendationCaches(
            new RecommendationProperties(Duration.ofMinutes(10), 2, 10, 100),
            new SimpleMeterRegistry());
    for (String key : List.of("a", "b", "c", "d")) {
      small.putRanking(key, ranking(1));
    }
    assertThat(small.rankingCount()).as("entries still bounded").isLessThanOrEqualTo(2);
    assertThat(RecommendationCaches.weight(ranking(1), 5)).isEqualTo(5);
    assertThat(RecommendationCaches.weight(ranking(8), 5)).isEqualTo(8);
  }
}
