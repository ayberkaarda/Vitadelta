package com.vitadelta.api.recommendation;

import com.vitadelta.api.catalog.NutrientCategory;
import com.vitadelta.api.catalog.price.EffectivePriceResolver;
import com.vitadelta.api.catalog.price.PriceResolution;
import com.vitadelta.api.catalog.price.StockFilter;
import com.vitadelta.api.catalog.read.CatalogReadService;
import com.vitadelta.api.catalog.read.CatalogViews.CatalogProduct;
import com.vitadelta.api.catalog.read.CatalogViews.CountryView;
import com.vitadelta.api.catalog.read.CatalogViews.ProductInCountry;
import com.vitadelta.api.catalog.read.CountryCatalog;
import com.vitadelta.api.catalog.read.MarketProfile;
import com.vitadelta.api.catalog.unit.UnitConversionService;
import com.vitadelta.api.catalog.web.DisplayCurrencyResolver;
import com.vitadelta.api.common.problem.FieldViolation;
import com.vitadelta.api.common.problem.ProblemCode;
import com.vitadelta.api.common.problem.ProblemException;
import com.vitadelta.api.common.ratelimit.RateLimitPolicy;
import com.vitadelta.api.common.ratelimit.RateLimiter;
import com.vitadelta.api.common.web.ClientIpResolver;
import com.vitadelta.api.entitlement.EntitlementService;
import com.vitadelta.api.entitlement.PriceConversion;
import com.vitadelta.api.identity.AccessTokenService;
import com.vitadelta.api.identity.AnonymousIdentityService;
import com.vitadelta.api.identity.AnonymousIdentityService.AnonymousIdentity;
import com.vitadelta.api.identity.CurrentUser;
import com.vitadelta.api.recommendation.NativeRanking.NativeCost;
import com.vitadelta.api.recommendation.NativeRanking.NativeFlag;
import com.vitadelta.api.recommendation.NativeRanking.ProductMeta;
import com.vitadelta.api.recommendation.RecommendationApi.AnonymousPrincipal;
import com.vitadelta.api.recommendation.RecommendationApi.CompareMeta;
import com.vitadelta.api.recommendation.RecommendationApi.CompareRequest;
import com.vitadelta.api.recommendation.RecommendationApi.CompareResponse;
import com.vitadelta.api.recommendation.RecommendationApi.DeltaRow;
import com.vitadelta.api.recommendation.RecommendationApi.ExcludedCounts;
import com.vitadelta.api.recommendation.RecommendationApi.Principal;
import com.vitadelta.api.recommendation.RecommendationApi.QueryMeta;
import com.vitadelta.api.recommendation.RecommendationApi.QueryRequest;
import com.vitadelta.api.recommendation.RecommendationApi.QueryResponse;
import com.vitadelta.api.recommendation.RecommendationApi.RankedProduct;
import com.vitadelta.api.recommendation.RecommendationApi.UserPrincipal;
import com.vitadelta.api.recommendation.RecommendationApi.Visibility;
import com.vitadelta.api.recommendation.RequestValidation.ParsedCompare;
import com.vitadelta.api.recommendation.RequestValidation.ParsedQuery;
import com.vitadelta.api.recommendation.ScoringInputs.CandidateSet;
import com.vitadelta.api.recommendation.scoring.model.Candidate;
import com.vitadelta.api.recommendation.scoring.model.ComparedProduct;
import com.vitadelta.api.recommendation.scoring.model.ExplanationParams;
import com.vitadelta.api.recommendation.scoring.model.NutrientDeltaRow;
import com.vitadelta.api.recommendation.scoring.model.ScoreRequest;
import com.vitadelta.api.recommendation.scoring.model.ScoreResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

/**
 * Ranked recommendations and pairwise comparison (sections 4.3, 4.4, 6.8, 7.4 to 7.7, 8.4).
 *
 * <p><b>Query</b>, in this order: per-IP limit and anonymous identity (anonymous callers only);
 * request validation (syntax, country, registry rules, display currency); for an anonymous caller
 * the atomic quota reservation in its own short transaction ({@code 403 quota_exceeded}); then the
 * ranking from the cache or, on a miss, from the catalog snapshot (a read-only transaction that is
 * closed before the scoring call) and the scoring service; finally locking, paging, conversion and
 * rounding. Any failure after the reservation refunds it in a second short transaction, so only a
 * {@code 200} consumes quota. Rankings are cached in native currency before locking; cache hits are
 * served while the scoring service is unavailable.
 *
 * <p><b>Compare</b> is open to every principal and never touches the quota or the anonymous
 * identity.
 *
 * <p>This class is deliberately not transactional: no database connection or transaction is held
 * while the scoring service is called (section 7.6).
 */
@Service
public class RecommendationService {

  /** Counter of anonymous queries rejected because the trial quota is used up. */
  public static final String QUOTA_REJECTED_METRIC = "vitadelta.recommendation.quota.rejected";

  private static final Logger LOG = LoggerFactory.getLogger(RecommendationService.class);

  private static final Pattern REQUIRED_MINIMUM =
      Pattern.compile("^required_nutrients\\[(\\d+)]\\.min_amount_per_day$");
  private static final Pattern CATEGORY_WEIGHT =
      Pattern.compile("^weights\\.category_weights\\.([A-Z]+)$");
  private static final Pattern OVERRIDE = Pattern.compile("^weights\\.overrides\\.([A-Z0-9_]+)$");

  private final CatalogReadService catalog;
  private final UnitConversionService conversions;
  private final EffectivePriceResolver prices;
  private final DisplayCurrencyResolver currencies;
  private final EntitlementService entitlements;
  private final AnonymousIdentityService anonymousIdentities;
  private final RateLimiter rateLimiter;
  private final ClientIpResolver clientIps;
  private final ScoringGateway scoring;
  private final RecommendationCaches caches;
  private final Counter quotaRejections;

  public RecommendationService(
      CatalogReadService catalog,
      UnitConversionService conversions,
      EffectivePriceResolver prices,
      DisplayCurrencyResolver currencies,
      EntitlementService entitlements,
      AnonymousIdentityService anonymousIdentities,
      RateLimiter rateLimiter,
      ClientIpResolver clientIps,
      ScoringGateway scoring,
      RecommendationCaches caches,
      MeterRegistry meters) {
    this.catalog = catalog;
    this.conversions = conversions;
    this.prices = prices;
    this.currencies = currencies;
    this.entitlements = entitlements;
    this.anonymousIdentities = anonymousIdentities;
    this.rateLimiter = rateLimiter;
    this.clientIps = clientIps;
    this.scoring = scoring;
    this.caches = caches;
    this.quotaRejections =
        Counter.builder(QUOTA_REJECTED_METRIC)
            .description("Anonymous recommendation queries rejected with quota_exceeded")
            .register(meters);
  }

  // ---------- Query ----------

  /** {@code POST /recommendations/query}. */
  public QueryResponse query(
      QueryRequest body,
      Authentication authentication,
      HttpServletRequest request,
      HttpServletResponse response) {
    Optional<UUID> userId = CurrentUser.id(authentication);
    AnonymousIdentity anonymous = null;
    if (userId.isEmpty()) {
      rateLimiter.check(RateLimitPolicy.ANONYMOUS_QUERY_PER_IP, clientIps.resolve(request));
      anonymous = anonymousIdentities.resolve(request, response);
    }
    ParsedQuery parsed = RequestValidation.parseQuery(body);
    CountryView country = requireCountry(parsed.country());
    long version = catalog.catalogVersion();
    RankingQuery query = RequestValidation.resolveQuery(parsed, reference(version));
    PriceConversion conversion =
        currencies.resolve(country.currency(), parsed.displayCurrency(), authentication);
    Principal principal;
    if (anonymous == null) {
      principal = userPrincipal(userId.get(), authentication);
    } else {
      principal = new AnonymousPrincipal(reserve(anonymous), entitlements.anonymousQueryLimit());
    }
    try {
      NativeRanking ranking = ranking(query, country, version);
      return present(ranking, parsed, conversion, principal, anonymous != null);
    } catch (RuntimeException e) {
      if (anonymous != null) {
        entitlements.refundAnonymousQuery(anonymous.idHash());
      }
      throw translate(e);
    }
  }

  private int reserve(AnonymousIdentity anonymous) {
    try {
      return entitlements.reserveAnonymousQuery(anonymous.idHash());
    } catch (ProblemException e) {
      if (e.code() == ProblemCode.QUOTA_EXCEEDED) {
        quotaRejections.increment();
      }
      throw e;
    }
  }

  /** The full native ranking from the cache or, on a miss, from the scoring service. */
  private NativeRanking ranking(RankingQuery query, CountryView country, long version) {
    Optional<NativeRanking> cached = caches.ranking(query.cacheKey(version));
    if (cached.isPresent()) {
      return cached.get();
    }
    CountryCatalog snapshot = loadCountry(country.code());
    String key = query.cacheKey(snapshot.catalogVersion());
    if (snapshot.catalogVersion() != version) {
      cached = caches.ranking(key);
      if (cached.isPresent()) {
        return cached.get();
      }
    }
    CandidateSet candidates = candidates(snapshot, query.stockFilter());
    MarketProfile profile =
        caches.marketProfile(country.code(), snapshot.catalogVersion(), snapshot::marketProfile);
    ScoreRequest request =
        new ScoreRequest()
            .nutrients(ScoringInputs.registry(snapshot.nutrients()))
            .referenceValues(ScoringInputs.referenceValues(snapshot.referenceValues()))
            .upperLimits(ScoringInputs.upperLimits(snapshot.upperLimits()))
            .weights(ScoringInputs.weights(query.weights()))
            .ranking(ScoringInputs.ranking(query))
            .requiredNutrients(ScoringInputs.requiredNutrients(query))
            .excludeExceedingUl(query.excludeExceedingUl())
            .marketProfile(ScoringInputs.marketProfile(profile))
            .candidates(candidates.candidates());
    ScoreResponse answer = scoring.score(request);
    NativeRanking ranking =
        NativeRanking.of(
            snapshot.catalogVersion(),
            country.code(),
            country.currency(),
            candidates.noStock(),
            candidates.noOffer(),
            answer,
            candidates.products());
    caches.putRanking(key, ranking);
    return ranking;
  }

  private QueryResponse present(
      NativeRanking ranking,
      ParsedQuery query,
      PriceConversion conversion,
      Principal principal,
      boolean anonymous) {
    int total = ranking.total();
    List<NativeRanking.Item> page;
    Visibility visibility;
    if (anonymous) {
      int shown = Math.min(entitlements.anonymousVisibleResults(), total);
      page = query.page().page() == 1 ? ranking.items().subList(0, shown) : List.of();
      int locked = total - shown;
      visibility =
          new Visibility(shown, total, locked, locked > 0 ? Visibility.SIGN_UP_REQUIRED : null);
    } else {
      long from = Math.min(query.page().offset(), total);
      int to = (int) Math.min(from + query.page().pageSize(), total);
      page = ranking.items().subList((int) from, to);
      visibility = new Visibility(page.size(), total, 0, null);
    }
    List<RankedProduct> items =
        page.stream().map(item -> RecommendationPresenter.ranked(item, conversion)).toList();
    QueryMeta meta =
        new QueryMeta(
            ranking.country(),
            ranking.nativeCurrency(),
            conversion.currency(),
            conversion.fx(),
            ranking.catalogVersion(),
            ranking.marketSize(),
            RecommendationPresenter.money(ranking.mRef(), conversion),
            new ExcludedCounts(
                ranking.noStock(),
                ranking.noOffer(),
                ranking.excludedRequiredNutrients(),
                ranking.excludedExceedsUl()),
            visibility,
            principal,
            query.page().page(),
            query.page().pageSize(),
            total);
    return new QueryResponse(meta, items);
  }

  private Principal userPrincipal(UUID userId, Authentication authentication) {
    String role =
        authentication instanceof JwtAuthenticationToken token
            ? token.getToken().getClaimAsString(AccessTokenService.ROLE)
            : null;
    return new UserPrincipal(
        "ADMIN".equals(role) ? "ADMIN" : "USER", entitlements.currentPlan(userId).code());
  }

  // ---------- Compare ----------

  /** {@code POST /recommendations/compare}. */
  public CompareResponse compare(
      CompareRequest body, Authentication authentication, HttpServletRequest request) {
    if (CurrentUser.id(authentication).isEmpty()) {
      rateLimiter.check(RateLimitPolicy.ANONYMOUS_BROWSE_PER_IP, clientIps.resolve(request));
    }
    ParsedCompare parsed = RequestValidation.parseCompare(body);
    CountryView country = requireCountry(parsed.country());
    long version = catalog.catalogVersion();
    List<FieldViolation> errors = new ArrayList<>();
    RankingQuery.Weights weights =
        RequestValidation.resolveWeights(parsed.weights(), reference(version), errors);
    RequestValidation.failIfAny(errors);
    PriceConversion conversion =
        currencies.resolve(country.currency(), parsed.displayCurrency(), authentication);
    requireInMarket(parsed.baseline(), country);
    requireInMarket(parsed.candidate(), country);
    CountryCatalog snapshot = loadCountry(country.code());
    CandidateSet market = candidates(snapshot, parsed.stockFilter());
    Candidate baseline = requirePriced(parsed.baseline(), "baselineProductId", snapshot, market);
    Candidate candidate = requirePriced(parsed.candidate(), "candidateProductId", snapshot, market);
    MarketProfile profile =
        caches.marketProfile(country.code(), snapshot.catalogVersion(), snapshot::marketProfile);
    com.vitadelta.api.recommendation.scoring.model.CompareRequest scoringRequest =
        new com.vitadelta.api.recommendation.scoring.model.CompareRequest()
            .baseline(baseline)
            .candidate(candidate)
            .market(market.candidates())
            .marketProfile(ScoringInputs.marketProfile(profile))
            .nutrients(ScoringInputs.registry(snapshot.nutrients()))
            .referenceValues(ScoringInputs.referenceValues(snapshot.referenceValues()))
            .upperLimits(ScoringInputs.upperLimits(snapshot.upperLimits()))
            .weights(ScoringInputs.weights(weights));
    com.vitadelta.api.recommendation.scoring.model.CompareResponse answer;
    try {
      answer = scoring.compare(scoringRequest);
      return presentCompare(answer, snapshot, country, market, conversion);
    } catch (RuntimeException e) {
      throw translate(e);
    }
  }

  private CompareResponse presentCompare(
      com.vitadelta.api.recommendation.scoring.model.CompareResponse answer,
      CountryCatalog snapshot,
      CountryView country,
      CandidateSet market,
      PriceConversion conversion) {
    List<DeltaRow> rows = new ArrayList<>();
    for (NutrientDeltaRow row : answer.getNutrientDeltas()) {
      rows.add(
          RecommendationPresenter.deltaRow(
              row.getCode(),
              NutrientCategory.valueOf(row.getCategory().getValue()),
              NativeRanking.number(row.getAmountA()),
              NativeRanking.number(row.getAmountB()),
              NativeRanking.number(row.getDeltaAmount()),
              row.getUnit().getValue(),
              NativeRanking.nullable(row.getrA()),
              NativeRanking.nullable(row.getrB()),
              NativeRanking.number(row.getuA()),
              NativeRanking.number(row.getuB()),
              NativeRanking.number(row.getWeight()),
              NativeRanking.number(row.getDeltaContribution()),
              row.getFlags() == null
                  ? List.of()
                  : row.getFlags().stream().map(f -> f.getValue()).toList()));
    }
    ExplanationParams params = answer.getExplanation().getParams();
    return new CompareResponse(
        new CompareMeta(
            country.code(),
            country.currency(),
            conversion.currency(),
            conversion.fx(),
            snapshot.catalogVersion(),
            answer.getMarketSize()),
        Boolean.TRUE.equals(answer.getSwapped()),
        answer.getVerdict().getValue(),
        compared(answer.getA(), market, conversion),
        compared(answer.getB(), market, conversion),
        RecommendationPresenter.converted(NativeRanking.number(answer.getDeltaCost()), conversion),
        com.vitadelta.api.common.numeric.Rounding.money(
            NativeRanking.number(answer.getDeltaCost())),
        com.vitadelta.api.common.numeric.Rounding.score(
            NativeRanking.number(answer.getDeltaContent())),
        RecommendationPresenter.money(NativeRanking.nullable(answer.getMarginalRate()), conversion),
        RecommendationPresenter.money(NativeRanking.nullable(answer.getmRef()), conversion),
        RecommendationPresenter.nullableRatio(NativeRanking.nullable(answer.getRatio())),
        rows,
        RecommendationPresenter.explanation(
            answer.getExplanation().getKey().getValue(),
            params == null ? null : NativeRanking.nullable(params.getDeltaCostPerDay()),
            params == null ? null : NativeRanking.nullable(params.getDeltaContent()),
            params == null ? null : NativeRanking.nullable(params.getMarginalRate()),
            params == null ? null : NativeRanking.nullable(params.getmRef()),
            params == null ? null : NativeRanking.nullable(params.getRatio()),
            conversion));
  }

  private static RecommendationApi.ComparedProduct compared(
      ComparedProduct side, CandidateSet market, PriceConversion conversion) {
    ProductMeta product = market.products().get(NativeRanking.productId(side.getProductId()));
    if (product == null) {
      throw new ScoringException.Failed(
          "scoring service compared a product that was not sent", null);
    }
    return RecommendationPresenter.compared(
        product,
        NativeCost.of(side.getCost()),
        NativeRanking.number(side.getContentScore()),
        NativeRanking.number(side.getValueScore()),
        side.getFlags().stream().map(NativeFlag::of).toList(),
        conversion);
  }

  private void requireInMarket(UUID productId, CountryView country) {
    ProductInCountry product =
        catalog
            .product(productId, country.code())
            .orElseThrow(() ->
                ProblemException.of(ProblemCode.PRODUCT_NOT_FOUND, "The product does not exist."));
    if (!product.inMarket()) {
      throw ProblemException.of(
          ProblemCode.PRODUCT_NOT_AVAILABLE_IN_COUNTRY,
          "The product is not sold in " + country.code() + ".");
    }
  }

  /** The candidate of a compared product, or {@code 422} {@code no_offer} / {@code no_stock}. */
  private Candidate requirePriced(
      UUID productId, String field, CountryCatalog snapshot, CandidateSet market) {
    Candidate candidate = market.byId().get(productId);
    if (candidate != null) {
      return candidate;
    }
    Optional<CatalogProduct> product =
        snapshot.products().stream().filter(p -> p.id().equals(productId)).findFirst();
    if (product.isEmpty()) {
      throw ProblemException.of(
          ProblemCode.PRODUCT_NOT_AVAILABLE_IN_COUNTRY,
          "The product is not sold in " + snapshot.country().code() + ".");
    }
    boolean hasOffers = !product.get().offers().isEmpty();
    throw ProblemException.validation(
        RequestValidation.DETAIL,
        List.of(
            hasOffers
                ? new FieldViolation(field, "no_stock", "The product has no offer in stock.")
                : new FieldViolation(
                    field, "no_offer", "The product has no offer in this country.")));
  }

  // ---------- Shared ----------

  private CandidateSet candidates(CountryCatalog snapshot, StockFilter filter) {
    Instant now = prices.now();
    return ScoringInputs.candidates(
        snapshot, filter, now, (CatalogProduct product, StockFilter f) -> resolve(product, f, now));
  }

  private PriceResolution resolve(CatalogProduct product, StockFilter filter, Instant now) {
    return prices.resolve(product.offers(), filter, now);
  }

  private CountryView requireCountry(String code) {
    return catalog
        .country(code)
        .orElseThrow(() ->
            ProblemException.of(ProblemCode.COUNTRY_NOT_FOUND, "The country is not supported."));
  }

  private CountryCatalog loadCountry(String code) {
    return catalog
        .loadCountry(code)
        .orElseThrow(() ->
            ProblemException.of(ProblemCode.COUNTRY_NOT_FOUND, "The country is not supported."));
  }

  private ReferenceSnapshot reference(long version) {
    return caches.reference(
        version,
        () -> new ReferenceSnapshot(version, catalog.nutrients(), conversions.currentTable()));
  }

  /**
   * The public outcome of a failure (contract scoring-error mapping): unavailability is {@code 503
   * scoring_unavailable}; a scoring {@code 422} whose errors all point at client-supplied values is
   * {@code 422 validation_failed} with the same codes and public field paths; every other scoring
   * rejection or failure is {@code 500 internal_error}.
   */
  static RuntimeException translate(RuntimeException e) {
    return switch (e) {
      case ScoringException.Unavailable u ->
          ProblemException.of(
              ProblemCode.SCORING_UNAVAILABLE, "The ranking service is temporarily unavailable.");
      case ScoringException.Rejected r -> rejected(r);
      case ScoringException.Failed f -> new IllegalStateException("scoring call failed", f);
      default -> e;
    };
  }

  private static RuntimeException rejected(ScoringException.Rejected rejection) {
    List<FieldViolation> errors = new ArrayList<>();
    for (ScoringException.FieldError error : rejection.errors()) {
      Optional<String> field = publicField(error.field());
      if (field.isEmpty() || !error.code().matches("^[a-z][a-z0-9_]*$")) {
        LOG.error("Scoring service rejected API-built input: {} {}", error.field(), error.code());
        return new IllegalStateException("scoring service rejected the request", rejection);
      }
      errors.add(new FieldViolation(field.get(), error.code(), error.message()));
    }
    if (errors.isEmpty()) {
      return new IllegalStateException("scoring service rejected the request", rejection);
    }
    return ProblemException.validation(RequestValidation.DETAIL, errors);
  }

  /** The public path of a client-derived scoring field, empty for API-built input. */
  static Optional<String> publicField(String field) {
    if (field.equals("weights") || field.equals("ranking.lambda")) {
      return Optional.of(field);
    }
    Matcher category = CATEGORY_WEIGHT.matcher(field);
    if (category.matches()) {
      return Optional.of("weights.categories." + category.group(1));
    }
    Matcher override = OVERRIDE.matcher(field);
    if (override.matches()) {
      return Optional.of("weights.overrides." + override.group(1));
    }
    Matcher minimum = REQUIRED_MINIMUM.matcher(field);
    if (minimum.matches()) {
      return Optional.of("requiredNutrients[" + minimum.group(1) + "].minAmountPerDay");
    }
    return Optional.empty();
  }
}
