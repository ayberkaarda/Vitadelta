package com.vitadelta.api.catalog.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vitadelta.api.catalog.NutrientCategory;
import com.vitadelta.api.catalog.WeightDefaults;
import com.vitadelta.api.catalog.read.CatalogReadService;
import com.vitadelta.api.catalog.read.CatalogViews.CountryView;
import com.vitadelta.api.catalog.read.CatalogViews.FormView;
import com.vitadelta.api.catalog.read.CatalogViews.NutrientView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public reference data: countries, the nutrient registry and the default weights with the
 * scoring-engine parameters.
 */
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class ReferenceDataController {

  /** Contract schema {@code Country}. */
  public record CountryItem(
      String code,
      String nameEn,
      String currency,
      String referenceSetCode,
      String ulSetCode,
      @Schema(allowableValues = {"en", "tr"}) String defaultLocale) {}

  /** Contract schema {@code CountryList}. */
  public record CountryList(List<CountryItem> items) {}

  /** Contract schema {@code NutrientForm}. */
  public record FormItem(String code, String nameEn) {}

  /**
   * Contract schema {@code Nutrient}. {@code forms} is always written (empty without registered
   * forms) although the contract does not require it.
   */
  public record NutrientItem(
      @Schema(requiredMode = REQUIRED) String code,
      @Schema(requiredMode = REQUIRED) String nameEn,
      @Schema(requiredMode = REQUIRED) NutrientCategory category,
      @Schema(
              requiredMode = REQUIRED,
              allowableValues = {"mg", "mcg"})
          String canonicalUnit,
      @Schema(requiredMode = REQUIRED) BigDecimal presenceFloorPerDay,
      @Schema(requiredMode = REQUIRED) int sortOrder,
      @Schema(requiredMode = REQUIRED) List<String> referenceSetCodes,
      @Schema(requiredMode = NOT_REQUIRED) List<FormItem> forms) {}

  /** Contract schema {@code NutrientList}. */
  public record NutrientList(List<NutrientItem> items) {}

  /** Contract schema {@code NumberRange}. */
  public record NumberRange(double min, double max) {}

  /** Contract schema {@code DefaultCategoryWeights}: the default weight of every category. */
  public record DefaultCategoryWeights(
      @JsonProperty("VITAMIN") @Schema(requiredMode = REQUIRED) double vitamin,
      @JsonProperty("MINERAL") @Schema(requiredMode = REQUIRED) double mineral,
      @JsonProperty("COENZYME") @Schema(requiredMode = REQUIRED) double coenzyme,
      @JsonProperty("OTHER") @Schema(requiredMode = REQUIRED) double other) {}

  /**
   * Contract schema {@code DefaultWeights}. {@code engine} is {@code null} while the scoring
   * service has not been reached since start-up.
   */
  public record DefaultWeights(
      DefaultCategoryWeights categories,
      NumberRange categoryRange,
      double overrideDefault,
      NumberRange overrideRange,
      double lambdaDefault,
      NumberRange lambdaRange,
      EngineParameters engine) {}

  private final CatalogReadService catalog;
  private final EngineParametersSource engine;

  public ReferenceDataController(CatalogReadService catalog, EngineParametersSource engine) {
    this.catalog = catalog;
    this.engine = engine;
  }

  @GetMapping("/countries")
  public CountryList countries() {
    return new CountryList(
        catalog.countries().stream().map(ReferenceDataController::item).toList());
  }

  @GetMapping("/nutrients")
  public NutrientList nutrients() {
    return new NutrientList(
        catalog.nutrients().stream().map(ReferenceDataController::item).toList());
  }

  /** Never answers 503: without engine parameters {@code engine} is {@code null}. */
  @GetMapping("/weights/defaults")
  public DefaultWeights weights() {
    return defaultWeights(engine.current().orElse(null));
  }

  private static CountryItem item(CountryView c) {
    return new CountryItem(
        c.code(), c.nameEn(), c.currency(), c.referenceSetCode(), c.ulSetCode(), c.defaultLocale());
  }

  private static NutrientItem item(NutrientView n) {
    return new NutrientItem(
        n.code(),
        n.nameEn(),
        n.category(),
        n.canonicalUnit().symbol(),
        n.presenceFloorPerDay(),
        n.sortOrder(),
        n.referenceSetCodes(),
        n.forms().stream().map(ReferenceDataController::item).toList());
  }

  private static FormItem item(FormView f) {
    return new FormItem(f.code(), f.nameEn());
  }

  static DefaultWeights defaultWeights(EngineParameters engine) {
    return new DefaultWeights(
        new DefaultCategoryWeights(
            WeightDefaults.categoryDefault(NutrientCategory.VITAMIN),
            WeightDefaults.categoryDefault(NutrientCategory.MINERAL),
            WeightDefaults.categoryDefault(NutrientCategory.COENZYME),
            WeightDefaults.categoryDefault(NutrientCategory.OTHER)),
        new NumberRange(WeightDefaults.CATEGORY_MIN, WeightDefaults.CATEGORY_MAX),
        WeightDefaults.OVERRIDE_DEFAULT,
        new NumberRange(WeightDefaults.OVERRIDE_MIN, WeightDefaults.OVERRIDE_MAX),
        WeightDefaults.LAMBDA_DEFAULT,
        new NumberRange(WeightDefaults.LAMBDA_MIN, WeightDefaults.LAMBDA_MAX),
        engine);
  }
}
