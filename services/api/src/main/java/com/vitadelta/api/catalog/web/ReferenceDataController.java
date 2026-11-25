package com.vitadelta.api.catalog.web;

import com.vitadelta.api.catalog.NutrientCategory;
import com.vitadelta.api.catalog.WeightDefaults;
import com.vitadelta.api.catalog.read.CatalogReadService;
import com.vitadelta.api.catalog.read.CatalogViews.CountryView;
import com.vitadelta.api.catalog.read.CatalogViews.FormView;
import com.vitadelta.api.catalog.read.CatalogViews.NutrientView;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public reference data: countries, the nutrient registry and the default weights. */
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
      String defaultLocale) {}

  /** Contract schema {@code CountryList}. */
  public record CountryList(List<CountryItem> items) {}

  /** Contract schema {@code NutrientForm}. */
  public record FormItem(String code, String nameEn) {}

  /** Contract schema {@code Nutrient}. */
  public record NutrientItem(
      String code,
      String nameEn,
      NutrientCategory category,
      String canonicalUnit,
      BigDecimal presenceFloorPerDay,
      int sortOrder,
      List<String> referenceSetCodes,
      List<FormItem> forms) {}

  /** Contract schema {@code NutrientList}. */
  public record NutrientList(List<NutrientItem> items) {}

  /** Contract schema {@code NumberRange}. */
  public record NumberRange(double min, double max) {}

  /** Contract schema {@code DefaultWeights}. */
  public record DefaultWeights(
      Map<String, Double> categories,
      NumberRange categoryRange,
      double overrideDefault,
      NumberRange overrideRange,
      double lambdaDefault,
      NumberRange lambdaRange) {}

  private static final DefaultWeights DEFAULT_WEIGHTS = defaultWeights();

  private final CatalogReadService catalog;

  public ReferenceDataController(CatalogReadService catalog) {
    this.catalog = catalog;
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

  @GetMapping("/weights/defaults")
  public DefaultWeights weights() {
    return DEFAULT_WEIGHTS;
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

  private static DefaultWeights defaultWeights() {
    Map<String, Double> categories = new LinkedHashMap<>();
    WeightDefaults.categoryDefaults().forEach((category, w) -> categories.put(category.name(), w));
    return new DefaultWeights(
        Collections.unmodifiableMap(categories),
        new NumberRange(WeightDefaults.CATEGORY_MIN, WeightDefaults.CATEGORY_MAX),
        WeightDefaults.OVERRIDE_DEFAULT,
        new NumberRange(WeightDefaults.OVERRIDE_MIN, WeightDefaults.OVERRIDE_MAX),
        WeightDefaults.LAMBDA_DEFAULT,
        new NumberRange(WeightDefaults.LAMBDA_MIN, WeightDefaults.LAMBDA_MAX));
  }
}
