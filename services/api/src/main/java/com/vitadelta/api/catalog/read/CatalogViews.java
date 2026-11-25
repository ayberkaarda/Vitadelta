package com.vitadelta.api.catalog.read;

import com.vitadelta.api.catalog.AvailabilityStatus;
import com.vitadelta.api.catalog.DosageForm;
import com.vitadelta.api.catalog.FormCodes;
import com.vitadelta.api.catalog.NutrientCategory;
import com.vitadelta.api.catalog.UlBasis;
import com.vitadelta.api.catalog.price.OfferSnapshot;
import com.vitadelta.api.catalog.unit.Unit;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Plain, detached read models of the catalog. They carry no persistence state, so they can be used
 * after the read-only transaction that produced them has been closed (for example across the
 * scoring call).
 */
public final class CatalogViews {

  private CatalogViews() {}

  /** A country of the registry. */
  public record CountryView(
      String code,
      String nameEn,
      String currency,
      String referenceSetCode,
      String ulSetCode,
      String defaultLocale) {}

  /** A registered form of a nutrient. */
  public record FormView(String code, String nameEn) {}

  /**
   * A nutrient registry entry.
   *
   * @param presenceFloorPerDay fallback daily amount in the canonical unit, {@code null} when
   *     unknown
   * @param referenceSetCodes reference sets that define a value for the nutrient, ascending
   * @param forms registered forms of the nutrient ordered by code
   */
  public record NutrientView(
      String code,
      String nameEn,
      NutrientCategory category,
      Unit canonicalUnit,
      BigDecimal presenceFloorPerDay,
      int sortOrder,
      List<String> referenceSetCodes,
      List<FormView> forms) {

    public NutrientView {
      referenceSetCodes = List.copyOf(referenceSetCodes);
      forms = List.copyOf(forms);
    }
  }

  /** A regulatory reference value of one reference set, in the nutrient's canonical unit. */
  public record ReferenceAmount(String nutrientCode, BigDecimal amount, Unit unit) {}

  /**
   * An upper-limit row of one UL set as the scoring contract wants it. An exempt row (a form
   * explicitly without a UL) has {@code amount}, {@code unit} and {@code basis} all {@code null}.
   *
   * @param formCode a registered form or {@code ANY}
   * @param note citation note of the row (not sent to the scoring service)
   */
  public record UpperLimitView(
      String nutrientCode,
      String formCode,
      BigDecimal amount,
      Unit unit,
      UlBasis basis,
      String note) {

    public boolean exempt() {
      return amount == null;
    }
  }

  /**
   * Where a product's label data comes from.
   *
   * @param sourceEntryDate label entry date of a real label snapshot, {@code null} otherwise
   */
  public record ProvenanceView(
      boolean synthetic, String source, String sourceRef, LocalDate sourceEntryDate) {}

  /**
   * One nutrient of a product converted once into the canonical unit (section 5.5).
   *
   * @param amountPerServing amount per serving in {@code unit} (exact, not rounded)
   * @param ulAmountPerServing UL-relevant amount per serving in {@code unit}, {@code null} when the
   *     full amount is UL-relevant
   * @param formCode the stored form code, {@code UNKNOWN} when not registered or not stated
   * @param conversionAssumed whether an IU conversion assumed a form ({@code CONVERSION_ASSUMED})
   */
  public record ProductNutrientView(
      String code,
      NutrientCategory category,
      BigDecimal amountPerServing,
      Unit unit,
      BigDecimal ulAmountPerServing,
      String formCode,
      String formLabel,
      boolean conversionAssumed) {

    /** {@code amountPerServing x servingsPerDay}, exact. */
    public BigDecimal amountPerDay(BigDecimal servingsPerDay) {
      return amountPerServing.multiply(servingsPerDay);
    }
  }

  /**
   * One product with its canonical nutrients and its offers in one country.
   *
   * @param nutrients ordered by nutrient code
   * @param offers every offer of the product in the country (in and out of stock), in display order
   */
  public record CatalogProduct(
      UUID id,
      String code,
      String brand,
      String name,
      DosageForm dosageForm,
      int unitsPerContainer,
      int unitsPerServing,
      BigDecimal servingsPerDay,
      String gtin,
      ProvenanceView provenance,
      List<ProductNutrientView> nutrients,
      List<OfferSnapshot> offers) {

    public CatalogProduct {
      nutrients = List.copyOf(nutrients);
      offers = List.copyOf(offers);
    }

    /** Codes of the nutrients converted with an assumed form, ascending. */
    public List<String> conversionAssumedCodes() {
      return nutrients.stream()
          .filter(ProductNutrientView::conversionAssumed)
          .map(ProductNutrientView::code)
          .sorted()
          .toList();
    }
  }

  /**
   * The regulatory facts of one country for a set of nutrients: the values of the country's
   * reference set and the rows of its UL set (exempt rows included), read in the same snapshot as
   * the product they describe.
   *
   * @param referenceSetCode the country's reference set
   * @param ulSetCode the country's UL set
   * @param referenceValues reference values of {@code referenceSetCode}, by nutrient code
   * @param upperLimits rows of {@code ulSetCode}, by nutrient code then form code
   */
  public record NutrientFacts(
      String referenceSetCode,
      String ulSetCode,
      List<ReferenceAmount> referenceValues,
      List<UpperLimitView> upperLimits) {

    public NutrientFacts {
      referenceValues = List.copyOf(referenceValues);
      upperLimits = List.copyOf(upperLimits);
    }

    /** The reference value of {@code nutrientCode}; empty when the set defines none. */
    public Optional<ReferenceAmount> reference(String nutrientCode) {
      return referenceValues.stream()
          .filter(r -> r.nutrientCode().equals(nutrientCode))
          .findFirst();
    }

    /**
     * The upper limit that applies to {@code nutrientCode} in {@code formCode} under the UL
     * matching rule (section 5.7, the same rule as the scoring service): the row of the form itself
     * unless the form is {@code UNKNOWN}, otherwise or when there is none the {@code ANY} row.
     * Empty when no row matches (no UL, or a form-dependent UL for an unstated form) or the matched
     * row is exempt.
     */
    public Optional<UpperLimitView> upperLimit(String nutrientCode, String formCode) {
      Optional<UpperLimitView> match = Optional.empty();
      if (!FormCodes.UNKNOWN.equals(formCode)) {
        match = row(nutrientCode, formCode);
      }
      if (match.isEmpty()) {
        match = row(nutrientCode, FormCodes.ANY);
      }
      return match.filter(row -> !row.exempt());
    }

    private Optional<UpperLimitView> row(String nutrientCode, String formCode) {
      return upperLimits.stream()
          .filter(u -> u.nutrientCode().equals(nutrientCode) && u.formCode().equals(formCode))
          .findFirst();
    }
  }

  /**
   * A product in one country for the detail page.
   *
   * @param availabilityStatus the availability row of the country, {@code null} when it has none
   * @param facts the country's reference values and upper limits of the product's nutrients
   */
  public record ProductInCountry(
      CatalogProduct product,
      AvailabilityStatus availabilityStatus,
      String availabilityNote,
      NutrientFacts facts) {

    /** In market (section 7.2): only {@code AVAILABLE} counts. */
    public boolean inMarket() {
      return availabilityStatus == AvailabilityStatus.AVAILABLE;
    }
  }

  /** A page of in-market products of a country; {@code total} counts every page. */
  public record ProductSearchResult(List<CatalogProduct> items, long total) {

    public ProductSearchResult {
      items = List.copyOf(items);
    }
  }
}
