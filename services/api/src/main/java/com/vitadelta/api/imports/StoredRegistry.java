package com.vitadelta.api.imports;

import com.vitadelta.api.catalog.Country;
import com.vitadelta.api.catalog.CountryRepository;
import com.vitadelta.api.catalog.Nutrient;
import com.vitadelta.api.catalog.NutrientForm;
import com.vitadelta.api.catalog.NutrientFormRepository;
import com.vitadelta.api.catalog.NutrientRepository;
import com.vitadelta.api.catalog.ProductRepository;
import com.vitadelta.api.catalog.unit.UnitConversionService;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads the registry that admin imports are validated against from the database: canonical units of
 * the nutrient registry, the form registry table ({@code nutrient_form}), country currencies, the
 * unit conversion table, and the codes of the stored products.
 */
@Component
public class StoredRegistry {

  /** The registry and the product codes, read in one snapshot. */
  public record Snapshot(CatalogRegistry registry, Set<String> productCodes) {

    public Snapshot {
      productCodes = Set.copyOf(productCodes);
    }
  }

  private final NutrientRepository nutrients;
  private final NutrientFormRepository forms;
  private final CountryRepository countries;
  private final ProductRepository products;
  private final UnitConversionService conversions;

  public StoredRegistry(
      NutrientRepository nutrients,
      NutrientFormRepository forms,
      CountryRepository countries,
      ProductRepository products,
      UnitConversionService conversions) {
    this.nutrients = nutrients;
    this.forms = forms;
    this.countries = countries;
    this.products = products;
    this.conversions = conversions;
  }

  /** Reads the current registry in a short read-only transaction. */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Snapshot load() {
    CatalogRegistry registry =
        new CatalogRegistry(
            nutrients.findAll().stream()
                .collect(Collectors.toMap(Nutrient::getCode, Nutrient::getCanonicalUnit)),
            forms.findAll().stream()
                .collect(Collectors.toMap(NutrientForm::getCode, NutrientForm::getNutrientCode)),
            countries.findAll().stream()
                .collect(Collectors.toMap(Country::getCode, Country::getCurrency)),
            conversions.currentTable());
    return new Snapshot(registry, Set.copyOf(products.findAllCodes()));
  }
}
