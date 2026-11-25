package com.vitadelta.api.catalog.web;

import com.vitadelta.api.catalog.read.CatalogReadService;
import com.vitadelta.api.catalog.read.CatalogViews.CountryView;
import com.vitadelta.api.catalog.read.CatalogViews.ProductInCountry;
import com.vitadelta.api.catalog.read.CatalogViews.ProductSearchResult;
import com.vitadelta.api.catalog.web.ProductPresenter.ProductDetail;
import com.vitadelta.api.catalog.web.ProductPresenter.ProductSummary;
import com.vitadelta.api.common.paging.PageQuery;
import com.vitadelta.api.common.problem.FieldViolation;
import com.vitadelta.api.common.problem.ProblemCode;
import com.vitadelta.api.common.problem.ProblemException;
import com.vitadelta.api.common.ratelimit.RateLimitPolicy;
import com.vitadelta.api.common.ratelimit.RateLimiter;
import com.vitadelta.api.common.web.ClientIpResolver;
import com.vitadelta.api.entitlement.PriceConversion;
import com.vitadelta.api.identity.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public product search and detail within one country ({@code GET /products}, {@code GET
 * /products/{id}}). Only products in market (AVAILABLE, section 7.2) are returned; any other
 * availability is {@code 404 product_not_available_in_country} on the detail. Neither operation
 * calls the scoring service. Anonymous callers are limited to 120 requests per minute per client IP
 * (shared with {@code /recommendations/compare}); the limit is checked before anything else.
 */
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class ProductController {

  private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");
  private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
  private static final int MAX_QUERY_LENGTH = 200;

  private final CatalogReadService catalog;
  private final DisplayCurrencyResolver currencies;
  private final ProductPresenter presenter;
  private final RateLimiter rateLimiter;
  private final ClientIpResolver clientIps;
  private final Clock clock;

  public ProductController(
      CatalogReadService catalog,
      DisplayCurrencyResolver currencies,
      ProductPresenter presenter,
      RateLimiter rateLimiter,
      ClientIpResolver clientIps,
      Clock clock) {
    this.catalog = catalog;
    this.currencies = currencies;
    this.presenter = presenter;
    this.rateLimiter = rateLimiter;
    this.clientIps = clientIps;
    this.clock = clock;
  }

  /** Contract schema {@code ProductListMeta}. */
  public record ProductListMeta(
      String country,
      String nativeCurrency,
      String currency,
      PriceConversion.Fx fx,
      int page,
      int pageSize,
      long total) {}

  /** Contract schema {@code ProductPage}. */
  public record ProductPage(ProductListMeta meta, List<ProductSummary> items) {}

  /** Contract schema {@code ProductDetailMeta}. */
  public record ProductDetailMeta(
      String country, String nativeCurrency, String currency, PriceConversion.Fx fx) {}

  /** Contract schema {@code ProductDetailResponse}. */
  public record ProductDetailResponse(ProductDetailMeta meta, ProductDetail product) {}

  @GetMapping("/products")
  public ProductPage search(
      @RequestParam(required = false) String country,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer pageSize,
      @RequestParam(required = false) String displayCurrency,
      Authentication authentication,
      HttpServletRequest request) {
    limitAnonymous(authentication, request);
    List<FieldViolation> errors = new ArrayList<>();
    checkCountry(country, errors);
    checkCurrency(displayCurrency, errors);
    if (q != null
        && (q.isEmpty()
            || q.length() > MAX_QUERY_LENGTH
            || q.codePoints().anyMatch(Character::isISOControl))) {
      errors.add(
          new FieldViolation(
              "q",
              "invalid_format",
              "q must have 1 to " + MAX_QUERY_LENGTH + " characters without control characters."));
    }
    PageQuery paging = pageQuery(page, pageSize, errors);
    failIfInvalid(errors);
    CountryView found = requireCountry(country);
    PriceConversion conversion =
        currencies.resolve(found.currency(), displayCurrency, authentication);
    ProductSearchResult result = catalog.searchProducts(found.code(), q, paging);
    Instant now = clock.instant();
    List<ProductSummary> items =
        result.items().stream().map(p -> presenter.summary(p, conversion, now)).toList();
    return new ProductPage(
        new ProductListMeta(
            found.code(),
            found.currency(),
            conversion.currency(),
            conversion.fx(),
            paging.page(),
            paging.pageSize(),
            result.total()),
        items);
  }

  @GetMapping("/products/{id}")
  public ProductDetailResponse detail(
      @PathVariable("id") String id,
      @RequestParam(required = false) String country,
      @RequestParam(required = false) String displayCurrency,
      Authentication authentication,
      HttpServletRequest request) {
    limitAnonymous(authentication, request);
    List<FieldViolation> errors = new ArrayList<>();
    checkCountry(country, errors);
    checkCurrency(displayCurrency, errors);
    failIfInvalid(errors);
    CountryView found = requireCountry(country);
    PriceConversion conversion =
        currencies.resolve(found.currency(), displayCurrency, authentication);
    UUID productId = parseId(id).orElseThrow(ProductController::productNotFound);
    ProductInCountry product =
        catalog.product(productId, found.code()).orElseThrow(ProductController::productNotFound);
    if (!product.inMarket()) {
      throw ProblemException.of(
          ProblemCode.PRODUCT_NOT_AVAILABLE_IN_COUNTRY,
          "The product is not sold in " + found.code() + ".");
    }
    return new ProductDetailResponse(
        new ProductDetailMeta(
            found.code(), found.currency(), conversion.currency(), conversion.fx()),
        presenter.detail(product, conversion, clock.instant()));
  }

  private void limitAnonymous(Authentication authentication, HttpServletRequest request) {
    if (CurrentUser.id(authentication).isEmpty()) {
      rateLimiter.check(RateLimitPolicy.ANONYMOUS_BROWSE_PER_IP, clientIps.resolve(request));
    }
  }

  private CountryView requireCountry(String country) {
    return catalog
        .country(country)
        .orElseThrow(() ->
            ProblemException.of(ProblemCode.COUNTRY_NOT_FOUND, "The country is not supported."));
  }

  static void checkCountry(String country, List<FieldViolation> errors) {
    if (country == null) {
      errors.add(new FieldViolation("country", "required", "Value is required."));
    } else if (!COUNTRY.matcher(country).matches()) {
      errors.add(
          new FieldViolation("country", "invalid_format", "country must be two capital letters."));
    }
  }

  static void checkCurrency(String currency, List<FieldViolation> errors) {
    if (currency != null && !CURRENCY.matcher(currency).matches()) {
      errors.add(
          new FieldViolation(
              "displayCurrency",
              "invalid_format",
              "displayCurrency must be three capital letters."));
    }
  }

  private static PageQuery pageQuery(Integer page, Integer pageSize, List<FieldViolation> errors) {
    try {
      return PageQuery.of(page, pageSize);
    } catch (ProblemException e) {
      errors.addAll(e.errors());
      return null;
    }
  }

  private static void failIfInvalid(List<FieldViolation> errors) {
    if (!errors.isEmpty()) {
      throw ProblemException.validation("The request contains invalid fields.", errors);
    }
  }

  private static Optional<UUID> parseId(String id) {
    try {
      UUID parsed = UUID.fromString(id);
      return parsed.toString().equalsIgnoreCase(id) ? Optional.of(parsed) : Optional.empty();
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static ProblemException productNotFound() {
    return ProblemException.of(ProblemCode.PRODUCT_NOT_FOUND, "The product does not exist.");
  }
}
