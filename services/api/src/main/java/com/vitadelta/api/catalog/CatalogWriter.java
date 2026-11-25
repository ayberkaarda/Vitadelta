package com.vitadelta.api.catalog;

import com.vitadelta.api.catalog.ReferenceValues.CountryRow;
import com.vitadelta.api.catalog.ReferenceValues.FormRow;
import com.vitadelta.api.catalog.ReferenceValues.NutrientRow;
import com.vitadelta.api.catalog.ReferenceValues.ReferenceValueRow;
import com.vitadelta.api.catalog.ReferenceValues.SetRow;
import com.vitadelta.api.catalog.ReferenceValues.UpperLimitRow;
import com.vitadelta.api.catalog.unit.ConversionRule;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Upserts catalog and reference rows by their business keys (nutrient_form included). Every method
 * must run inside {@link CatalogWriteTransaction#execute}; a call outside it is rejected, so no
 * catalog write can skip the catalog version bump.
 *
 * <p>Semantics: registry tables (nutrient, reference_set, ul_set, country) are upserted and never
 * deleted (other rows reference them). Reference rows without dependents (reference_value,
 * upper_limit, unit_conversion) are replaced: rows absent from the input are deleted. Products are
 * upserted by code with their nutrient set replaced and availability upserted per country. Offers
 * are upserted by the natural key (product, country, retailer) with replace-set per (product,
 * country) pair present in the input. Unchanged rows are not written. Before a product's nutrient
 * or offer set is read, the product row is locked ({@code SELECT ... FOR UPDATE}), so concurrent
 * replace-set writes of the same product serialise instead of merging.
 */
@Component
public class CatalogWriter {

  private final EntityManager entityManager;
  private final NutrientRepository nutrients;
  private final NutrientFormRepository forms;
  private final ReferenceSetRepository referenceSets;
  private final UlSetRepository ulSets;
  private final ReferenceValueRepository referenceValues;
  private final UpperLimitRepository upperLimits;
  private final UnitConversionRepository unitConversions;
  private final CountryRepository countries;
  private final ProductRepository products;
  private final ProductNutrientRepository productNutrients;
  private final ProductAvailabilityRepository availabilities;
  private final OfferRepository offers;

  public CatalogWriter(
      EntityManager entityManager,
      NutrientRepository nutrients,
      NutrientFormRepository forms,
      ReferenceSetRepository referenceSets,
      UlSetRepository ulSets,
      ReferenceValueRepository referenceValues,
      UpperLimitRepository upperLimits,
      UnitConversionRepository unitConversions,
      CountryRepository countries,
      ProductRepository products,
      ProductNutrientRepository productNutrients,
      ProductAvailabilityRepository availabilities,
      OfferRepository offers) {
    this.entityManager = entityManager;
    this.nutrients = nutrients;
    this.forms = forms;
    this.referenceSets = referenceSets;
    this.ulSets = ulSets;
    this.referenceValues = referenceValues;
    this.upperLimits = upperLimits;
    this.unitConversions = unitConversions;
    this.countries = countries;
    this.products = products;
    this.productNutrients = productNutrients;
    this.availabilities = availabilities;
    this.offers = offers;
  }

  /** Upserts nutrients by code. */
  public void upsertNutrients(List<NutrientRow> rows, WriteReport report) {
    CatalogWriteTransaction.requireActive();
    Map<String, Nutrient> existing = index(nutrients.findAll(), Nutrient::getCode);
    WriteReport.TableCounts counts = report.table("nutrient");
    for (NutrientRow row : rows) {
      Nutrient current = existing.get(row.code());
      if (current == null) {
        entityManager.persist(
            new Nutrient(
                row.code(),
                row.nameEn(),
                row.category(),
                row.canonicalUnit(),
                row.presenceFloorPerDay(),
                row.sortOrder()));
        counts.upserted(true, false);
      } else {
        counts.upserted(
            false,
            current.update(
                row.nameEn(),
                row.category(),
                row.canonicalUnit(),
                row.presenceFloorPerDay(),
                row.sortOrder()));
      }
    }
    entityManager.flush();
  }

  /**
   * Upserts the form registry by code. Forms are never deleted: product, upper-limit and
   * unit-conversion rows reference them.
   */
  public void upsertForms(List<FormRow> rows, WriteReport report) {
    CatalogWriteTransaction.requireActive();
    Map<String, NutrientForm> existing = index(forms.findAll(), NutrientForm::getCode);
    WriteReport.TableCounts counts = report.table("nutrient_form");
    for (FormRow row : rows) {
      NutrientForm current = existing.get(row.code());
      if (current == null) {
        entityManager.persist(new NutrientForm(row.code(), row.nutrientCode(), row.nameEn()));
        counts.upserted(true, false);
      } else {
        counts.upserted(false, current.update(row.nutrientCode(), row.nameEn()));
      }
    }
    entityManager.flush();
  }

  /** Upserts reference sets by code. */
  public void upsertReferenceSets(List<SetRow> rows, WriteReport report) {
    CatalogWriteTransaction.requireActive();
    Map<String, ReferenceSet> existing = index(referenceSets.findAll(), ReferenceSet::getCode);
    WriteReport.TableCounts counts = report.table("reference_set");
    for (SetRow row : rows) {
      ReferenceSet current = existing.get(row.code());
      if (current == null) {
        entityManager.persist(new ReferenceSet(row.code(), row.name(), row.sourceCitation()));
        counts.upserted(true, false);
      } else {
        counts.upserted(false, current.update(row.name(), row.sourceCitation()));
      }
    }
    entityManager.flush();
  }

  /** Upserts UL sets by code. */
  public void upsertUlSets(List<SetRow> rows, WriteReport report) {
    CatalogWriteTransaction.requireActive();
    Map<String, UlSet> existing = index(ulSets.findAll(), UlSet::getCode);
    WriteReport.TableCounts counts = report.table("ul_set");
    for (SetRow row : rows) {
      UlSet current = existing.get(row.code());
      if (current == null) {
        entityManager.persist(new UlSet(row.code(), row.name(), row.sourceCitation()));
        counts.upserted(true, false);
      } else {
        counts.upserted(false, current.update(row.name(), row.sourceCitation()));
      }
    }
    entityManager.flush();
  }

  /** Replaces the reference values with {@code rows}. */
  public void replaceReferenceValues(List<ReferenceValueRow> rows, WriteReport report) {
    CatalogWriteTransaction.requireActive();
    Map<ReferenceValueId, ReferenceValue> existing =
        index(referenceValues.findAll(), ReferenceValue::getId);
    WriteReport.TableCounts counts = report.table("reference_value");
    for (ReferenceValueRow row : rows) {
      ReferenceValueId id = new ReferenceValueId(row.referenceSetCode(), row.nutrientCode());
      ReferenceValue current = existing.remove(id);
      if (current == null) {
        entityManager.persist(new ReferenceValue(id, row.amount(), row.unit()));
        counts.upserted(true, false);
      } else {
        counts.upserted(false, current.update(row.amount(), row.unit()));
      }
    }
    existing.values().forEach(entityManager::remove);
    counts.deleted(existing.size());
    entityManager.flush();
  }

  /** Replaces the upper limits with {@code rows}. */
  public void replaceUpperLimits(List<UpperLimitRow> rows, WriteReport report) {
    CatalogWriteTransaction.requireActive();
    Map<UpperLimitId, UpperLimit> existing = index(upperLimits.findAll(), UpperLimit::getId);
    WriteReport.TableCounts counts = report.table("upper_limit");
    for (UpperLimitRow row : rows) {
      UpperLimitId id = new UpperLimitId(row.ulSetCode(), row.nutrientCode(), row.formCode());
      UpperLimit current = existing.remove(id);
      if (current == null) {
        entityManager.persist(
            new UpperLimit(id, row.amount(), row.unit(), row.basis(), row.note()));
        counts.upserted(true, false);
      } else {
        counts.upserted(false, current.update(row.amount(), row.unit(), row.basis(), row.note()));
      }
    }
    existing.values().forEach(entityManager::remove);
    counts.deleted(existing.size());
    entityManager.flush();
  }

  /** Replaces the unit conversion rules with {@code rules}. */
  public void replaceUnitConversions(List<ConversionRule> rules, WriteReport report) {
    CatalogWriteTransaction.requireActive();
    Map<UnitConversionId, UnitConversion> existing =
        index(unitConversions.findAll(), UnitConversion::getId);
    WriteReport.TableCounts counts = report.table("unit_conversion");
    for (ConversionRule rule : rules) {
      UnitConversionId id =
          new UnitConversionId(rule.nutrientCode(), rule.from(), rule.to(), rule.formCode());
      UnitConversion current = existing.remove(id);
      if (current == null) {
        entityManager.persist(new UnitConversion(id, rule.factor(), rule.note()));
        counts.upserted(true, false);
      } else {
        counts.upserted(false, current.update(rule.factor(), rule.note()));
      }
    }
    existing.values().forEach(entityManager::remove);
    counts.deleted(existing.size());
    entityManager.flush();
  }

  /** Upserts countries by code. */
  public void upsertCountries(List<CountryRow> rows, WriteReport report) {
    CatalogWriteTransaction.requireActive();
    Map<String, Country> existing = index(countries.findAll(), Country::getCode);
    WriteReport.TableCounts counts = report.table("country");
    for (CountryRow row : rows) {
      Country current = existing.get(row.code());
      if (current == null) {
        entityManager.persist(
            new Country(
                row.code(),
                row.nameEn(),
                row.currency(),
                row.referenceSetCode(),
                row.ulSetCode(),
                row.defaultLocale()));
        counts.upserted(true, false);
      } else {
        counts.upserted(
            false,
            current.update(
                row.nameEn(),
                row.currency(),
                row.referenceSetCode(),
                row.ulSetCode(),
                row.defaultLocale()));
      }
    }
    entityManager.flush();
  }

  /** Upserts products by code, then their nutrients, availability and offers. */
  public void applyProducts(List<ProductImport> imports, Instant now, WriteReport report) {
    CatalogWriteTransaction.requireActive();
    lockProductsByCode(imports.stream().map(i -> i.details().code()).toList());
    Map<String, Product> existing = index(products.findAll(), Product::getCode);
    WriteReport.TableCounts counts = report.table("product");
    Map<String, UUID> ids = new LinkedHashMap<>();
    for (ProductImport item : imports) {
      Product current = existing.get(item.details().code());
      if (current == null) {
        Product created = new Product(item.details(), now);
        entityManager.persist(created);
        ids.put(created.getCode(), created.getId());
        counts.upserted(true, false);
      } else {
        ids.put(current.getCode(), current.getId());
        counts.upserted(false, current.update(item.details(), now));
      }
    }
    entityManager.flush();
    List<OfferValues> productOffers = new ArrayList<>();
    for (ProductImport item : imports) {
      UUID productId = ids.get(item.details().code());
      replaceProductNutrients(productId, item.nutrients(), report.table("product_nutrient"));
      upsertAvailability(productId, item.availability(), report.table("product_availability"));
      productOffers.addAll(item.offers());
    }
    entityManager.flush();
    applyOffers(productOffers, ids::get, report);
  }

  /**
   * Upserts offers by natural key with replace-set per (product, country) pair present in {@code
   * rows}; pairs not present are untouched.
   *
   * @throws IllegalArgumentException when a product code is unknown
   */
  public void applyOffers(List<OfferValues> rows, WriteReport report) {
    CatalogWriteTransaction.requireActive();
    Map<String, UUID> ids =
        products.findAll().stream().collect(Collectors.toMap(Product::getCode, Product::getId));
    applyOffers(rows, ids::get, report);
  }

  private void applyOffers(
      List<OfferValues> rows, Function<String, UUID> productIds, WriteReport report) {
    WriteReport.TableCounts counts = report.table("offer");
    Map<Pair, List<OfferValues>> byPair = new LinkedHashMap<>();
    for (OfferValues row : rows) {
      UUID productId = productIds.apply(row.productCode());
      if (productId == null) {
        throw new IllegalArgumentException("unknown product code " + row.productCode());
      }
      byPair
          .computeIfAbsent(new Pair(productId, row.countryCode()), p -> new ArrayList<>())
          .add(row);
    }
    lockProducts(byPair.keySet().stream().map(Pair::productId).toList());
    for (Map.Entry<Pair, List<OfferValues>> entry : byPair.entrySet()) {
      Pair pair = entry.getKey();
      Map<String, Offer> existing =
          index(
              offers.findByProductIdAndCountryCode(pair.productId(), pair.countryCode()),
              Offer::getRetailer);
      for (OfferValues row : entry.getValue()) {
        Offer current = existing.remove(row.retailer());
        if (current == null) {
          entityManager.persist(new Offer(pair.productId(), row));
          counts.upserted(true, false);
        } else {
          counts.upserted(false, current.update(row));
        }
      }
      existing.values().forEach(entityManager::remove);
      counts.deleted(existing.size());
    }
    entityManager.flush();
  }

  private void replaceProductNutrients(
      UUID productId, List<ProductNutrientValues> rows, WriteReport.TableCounts counts) {
    Map<String, ProductNutrient> existing =
        index(productNutrients.findByIdProductId(productId), n -> n.getId().getNutrientCode());
    for (ProductNutrientValues row : rows) {
      ProductNutrient current = existing.remove(row.nutrientCode());
      if (current == null) {
        entityManager.persist(
            new ProductNutrient(new ProductNutrientId(productId, row.nutrientCode()), row));
        counts.upserted(true, false);
      } else {
        counts.upserted(false, current.update(row));
      }
    }
    existing.values().forEach(entityManager::remove);
    counts.deleted(existing.size());
  }

  private void upsertAvailability(
      UUID productId, List<AvailabilityValues> rows, WriteReport.TableCounts counts) {
    Map<String, ProductAvailability> existing =
        index(availabilities.findByIdProductId(productId), a -> a.getId().getCountryCode());
    for (AvailabilityValues row : rows) {
      ProductAvailability current = existing.get(row.countryCode());
      if (current == null) {
        entityManager.persist(
            new ProductAvailability(
                new ProductAvailabilityId(productId, row.countryCode()), row.status(), row.note()));
        counts.upserted(true, false);
      } else {
        counts.upserted(false, current.update(row.status(), row.note()));
      }
    }
  }

  /**
   * Locks the rows of the given products ({@code SELECT ... FOR UPDATE}, in id order to avoid
   * deadlocks) before their nutrient or offer sets are read, so that concurrent replace-set writes
   * of the same product serialise instead of merging.
   */
  public void lockProducts(Collection<UUID> productIds) {
    CatalogWriteTransaction.requireActive();
    if (productIds.isEmpty()) {
      return;
    }
    entityManager
        .createNativeQuery("SELECT id FROM product WHERE id IN (:ids) ORDER BY id FOR UPDATE")
        .setParameter("ids", new TreeSet<>(productIds))
        .getResultList();
  }

  /** Locks the rows of the products with the given codes, like {@link #lockProducts}. */
  public void lockProductsByCode(Collection<String> codes) {
    CatalogWriteTransaction.requireActive();
    if (codes.isEmpty()) {
      return;
    }
    entityManager
        .createNativeQuery("SELECT id FROM product WHERE code IN (:codes) ORDER BY id FOR UPDATE")
        .setParameter("codes", new TreeSet<>(codes))
        .getResultList();
  }

  /** Codes of every stored product. */
  public Set<String> productCodes() {
    return products.findAll().stream().map(Product::getCode).collect(Collectors.toSet());
  }

  private static <K, V> Map<K, V> index(List<V> values, Function<V, K> key) {
    Map<K, V> map = new HashMap<>();
    for (V value : values) {
      map.put(key.apply(value), value);
    }
    return map;
  }

  private record Pair(UUID productId, String countryCode) {}
}
