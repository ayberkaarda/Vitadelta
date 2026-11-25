package com.vitadelta.api.alerts;

import com.vitadelta.api.catalog.web.DisplayCurrencyResolver;
import com.vitadelta.api.common.problem.FieldViolation;
import com.vitadelta.api.common.problem.ProblemCode;
import com.vitadelta.api.common.problem.ProblemException;
import com.vitadelta.api.common.watch.WatchedAlert;
import com.vitadelta.api.entitlement.PriceConversion;
import com.vitadelta.api.identity.CurrentUser;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Saved products and alert subscriptions of the signed-in caller (sections 8.5 and 8.6). Bodies are
 * parsed field by field ({@link JsonFields}) so that the checks run in the contract's order. Every
 * {@code {id}} route is scoped to the caller: an unknown, foreign or non-UUID id answers the same
 * {@code 404} ({@code about:blank}).
 */
@RestController
@RequestMapping(path = "/api/v1/me", produces = MediaType.APPLICATION_JSON_VALUE)
public class WatchlistController {

  private final WatchlistService service;
  private final WatchlistQueries queries;
  private final WatchlistRepository watchlist;
  private final DisplayCurrencyResolver currencies;

  public WatchlistController(
      WatchlistService service,
      WatchlistQueries queries,
      WatchlistRepository watchlist,
      DisplayCurrencyResolver currencies) {
    this.service = service;
    this.queries = queries;
    this.watchlist = watchlist;
    this.currencies = currencies;
  }

  // ---------- Saved products ----------

  @GetMapping("/saved-products")
  public AlertViews.SavedProductList savedProducts(
      // Validated here (422 with field errors); documented as required like the contract.
      @Parameter(required = true) @RequestParam(required = false) String country,
      @RequestParam(required = false) String displayCurrency,
      Authentication authentication) {
    UUID userId = CurrentUser.require(authentication);
    List<FieldViolation> errors = new ArrayList<>();
    JsonFields.checkCountryParameter(country, errors);
    JsonFields.checkCurrencyParameter(displayCurrency, errors);
    JsonFields.failIfInvalid(errors);
    String nativeCurrency = requireCountry(country);
    PriceConversion conversion =
        currencies.resolve(nativeCurrency, displayCurrency, authentication);
    return new AlertViews.SavedProductList(
        new AlertViews.SavedProductListMeta(
            country, nativeCurrency, conversion.currency(), conversion.fx()),
        queries.savedProducts(userId, country, conversion));
  }

  @PostMapping(path = "/saved-products", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AlertViews.SavedProductRef> save(
      @io.swagger.v3.oas.annotations.parameters.RequestBody(
              required = true,
              content =
                  @Content(
                      mediaType = MediaType.APPLICATION_JSON_VALUE,
                      schema = @Schema(implementation = AlertViews.SaveProductRequest.class)))
          @RequestBody
          JsonNode body,
      Authentication authentication) {
    UUID userId = CurrentUser.require(authentication);
    JsonFields.requireObject(body);
    List<FieldViolation> errors = new ArrayList<>();
    UUID productId = JsonFields.uuid(body, "productId", errors);
    String countryCode = JsonFields.country(body, "countryCode", errors);
    JsonFields.failIfInvalid(errors);
    WatchlistService.SaveResult result = service.save(userId, productId, countryCode);
    return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(
            new AlertViews.SavedProductRef(
                result.row().productId(), result.row().countryCode(), result.row().savedAt()));
  }

  @DeleteMapping("/saved-products/{productId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void removeSaved(
      @PathVariable("productId") String productId,
      // Validated here (422 with field errors); documented as required like the contract.
      @Parameter(required = true) @RequestParam(required = false) String country,
      Authentication authentication) {
    UUID userId = CurrentUser.require(authentication);
    List<FieldViolation> errors = new ArrayList<>();
    JsonFields.checkCountryParameter(country, errors);
    JsonFields.failIfInvalid(errors);
    requireCountry(country);
    Optional<UUID> id = JsonFields.parseUuid(productId);
    if (id.isPresent()) {
      service.remove(userId, id.get(), country);
    }
  }

  // ---------- Alerts ----------

  @GetMapping("/alerts")
  public AlertViews.AlertList alerts(Authentication authentication) {
    return new AlertViews.AlertList(queries.alerts(CurrentUser.require(authentication)));
  }

  @PostMapping(path = "/alerts", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<WatchedAlert> createAlert(
      @io.swagger.v3.oas.annotations.parameters.RequestBody(
              required = true,
              content =
                  @Content(
                      mediaType = MediaType.APPLICATION_JSON_VALUE,
                      schema = @Schema(implementation = AlertViews.CreateAlertRequest.class)))
          @RequestBody
          JsonNode body,
      Authentication authentication) {
    UUID userId = CurrentUser.require(authentication);
    JsonFields.requireObject(body);
    List<FieldViolation> errors = new ArrayList<>();
    UUID productId = JsonFields.uuid(body, "productId", errors);
    String countryCode = JsonFields.country(body, "countryCode", errors);
    AlertTrigger trigger = JsonFields.trigger(body, errors);
    JsonNode thresholdNode = body.get("thresholdPercent");
    BigDecimal threshold = null;
    if (thresholdNode != null && !thresholdNode.isNull()) {
      threshold = JsonFields.threshold(thresholdNode, errors);
      if (trigger != null && trigger != AlertTrigger.PRICE_DROP) {
        errors.removeIf(e -> e.field().equals("thresholdPercent"));
        errors.add(
            new FieldViolation(
                "thresholdPercent",
                JsonFields.THRESHOLD_NOT_ALLOWED,
                "thresholdPercent applies to PRICE_DROP only."));
      }
    }
    JsonFields.failIfInvalid(errors);
    WatchlistService.AlertResult result =
        service.create(userId, productId, countryCode, trigger, threshold);
    return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(result.alert());
  }

  @PatchMapping(path = "/alerts/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
  public WatchedAlert updateAlert(
      @PathVariable("id") String id,
      @io.swagger.v3.oas.annotations.parameters.RequestBody(
              required = true,
              content =
                  @Content(
                      mediaType = MediaType.APPLICATION_JSON_VALUE,
                      schema = @Schema(implementation = AlertViews.UpdateAlertRequest.class)))
          @RequestBody
          JsonNode body,
      Authentication authentication) {
    UUID userId = CurrentUser.require(authentication);
    JsonFields.requireObject(body);
    List<FieldViolation> errors = new ArrayList<>();
    JsonNode activeNode = body.get("active");
    JsonNode thresholdNode = body.get("thresholdPercent");
    if (activeNode == null && thresholdNode == null) {
      errors.add(
          new FieldViolation(
              "body", JsonFields.REQUIRED, "Provide active and/or thresholdPercent."));
    }
    Boolean active = null;
    if (activeNode != null) {
      if (activeNode.isBoolean()) {
        active = activeNode.booleanValue();
      } else {
        errors.add(
            new FieldViolation("active", JsonFields.INVALID_FORMAT, "active must be a boolean."));
      }
    }
    BigDecimal threshold = null;
    if (thresholdNode != null) {
      threshold = JsonFields.threshold(thresholdNode, errors);
    }
    JsonFields.failIfInvalid(errors);
    UUID alertId = JsonFields.parseUuid(id).orElseThrow(WatchlistService::notFound);
    WatchlistRepository.AlertRow stored = service.requireAlert(userId, alertId);
    if (threshold != null && stored.trigger() != AlertTrigger.PRICE_DROP) {
      throw ProblemException.validation(
          "The request contains invalid fields.",
          List.of(
              new FieldViolation(
                  "thresholdPercent",
                  JsonFields.THRESHOLD_NOT_ALLOWED,
                  "thresholdPercent applies to PRICE_DROP only.")));
    }
    return service.update(userId, alertId, active, threshold);
  }

  @DeleteMapping("/alerts/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteAlert(@PathVariable("id") String id, Authentication authentication) {
    UUID userId = CurrentUser.require(authentication);
    UUID alertId = JsonFields.parseUuid(id).orElseThrow(WatchlistService::notFound);
    service.delete(userId, alertId);
  }

  private String requireCountry(String country) {
    return watchlist
        .countryCurrency(country)
        .orElseThrow(() ->
            ProblemException.of(ProblemCode.COUNTRY_NOT_FOUND, "The country is not supported."));
  }
}
