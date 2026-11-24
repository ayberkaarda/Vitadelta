package com.vitadelta.api.catalog.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.catalog.AvailabilityStatus;
import com.vitadelta.api.catalog.AvailabilityValues;
import com.vitadelta.api.catalog.FormCodes;
import com.vitadelta.api.catalog.OfferValues;
import com.vitadelta.api.catalog.ProductImport;
import com.vitadelta.api.catalog.ProductNutrientValues;
import com.vitadelta.api.catalog.ReferenceValues.CountryRow;
import com.vitadelta.api.catalog.ReferenceValues.NutrientRow;
import com.vitadelta.api.catalog.ReferenceValues.UpperLimitRow;
import com.vitadelta.api.catalog.unit.Unit;
import com.vitadelta.api.catalog.unit.UnitConversionTable;
import com.vitadelta.api.imports.CatalogRegistry;
import com.vitadelta.api.imports.OffersCsvReader;
import com.vitadelta.api.imports.ProductsDocumentReader;
import com.vitadelta.api.support.TestData;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Integrity of {@code data/fixtures} (fixture dataset rules, project specification section 5.8) and
 * of the real label snapshot in {@code data/real}. Read-only over the data directory; no database
 * is needed.
 */
class FixtureIntegrityTest {

  /** The fixture timestamps are relative to this instant. */
  static final Instant NOW = Instant.parse("2026-11-24T00:00:00Z");

  static final Set<String> FICTIONAL_BRANDS =
      Set.of(
          "Fixture Labs",
          "Northline Nutrition",
          "Meridian Vitals",
          "Bosphorus Health",
          "Redwood Daily");

  static final Set<String> MARKETS = Set.of("TR", "DE", "GB", "US");

  static Path dataDir;
  static ReferenceData reference;
  static CatalogRegistry registry;
  static UnitConversionTable conversions;
  static List<ProductImport> synthetic;
  static List<ProductImport> real;
  static List<OfferValues> offers;
  static List<OfferValues> priceDropOffers;
  static List<OfferValues> realOffers;

  @BeforeAll
  static void load() throws IOException {
    dataDir = TestData.dataDir();
    // The full seed validation must pass first; it reports every row-level problem.
    SeedDataReader.read(dataDir, code -> false, NOW);
    reference = SeedDataReader.readReference(dataDir);
    registry = reference.registry();
    conversions = registry.conversions();
    synthetic = products("fixtures/products.synthetic.json");
    real = products("real/products.dsld.json");
    Set<String> syntheticCodes = codes(synthetic);
    Set<String> realCodes = codes(real);
    offers = offers("fixtures/offers.synthetic.csv", syntheticCodes::contains);
    priceDropOffers = offers("fixtures/offers.price-drop.synthetic.csv", syntheticCodes::contains);
    realOffers = offers("real/offers.us-demo.synthetic.csv", realCodes::contains);
  }

  private static List<ProductImport> products(String file) throws IOException {
    ProductsDocumentReader.Result result =
        ProductsDocumentReader.read(Files.readAllBytes(dataDir.resolve(file)), registry, NOW);
    assertThat(result.problems()).as(file).isEmpty();
    return result.products();
  }

  private static List<OfferValues> offers(String file, Predicate<String> productExists)
      throws IOException {
    OffersCsvReader.Result result =
        OffersCsvReader.read(
            Files.readAllBytes(dataDir.resolve(file)), productExists, registry, NOW);
    assertThat(result.problems()).as(file).isEmpty();
    return result.offers();
  }

  private static Set<String> codes(List<ProductImport> products) {
    return products.stream().map(p -> p.details().code()).collect(Collectors.toSet());
  }

  private static Set<String> availableIn(ProductImport product) {
    return product.availability().stream()
        .filter(a -> a.status() == AvailabilityStatus.AVAILABLE)
        .map(AvailabilityValues::countryCode)
        .collect(Collectors.toSet());
  }

  private static ProductImport product(String code) {
    return synthetic.stream()
        .filter(p -> p.details().code().equals(code))
        .findFirst()
        .orElseThrow(() -> new AssertionError("missing fixture product " + code));
  }

  private static List<OfferValues> offersOf(String code, String country) {
    return offers.stream()
        .filter(o -> o.productCode().equals(code) && o.countryCode().equals(country))
        .toList();
  }

  private static Optional<BigDecimal> bestInStockPrice(String code, String country) {
    return offersOf(code, country).stream()
        .filter(OfferValues::inStock)
        .map(OfferValues::price)
        .min(Comparator.naturalOrder());
  }

  private static boolean inTrMarket(ProductImport p) {
    return availableIn(p).contains("TR");
  }

  /** Daily amount in the nutrient's canonical unit. */
  private static BigDecimal perDay(ProductImport p, ProductNutrientValues n, boolean ulRelevant) {
    BigDecimal perServing =
        ulRelevant && n.ulAmountPerServing() != null
            ? n.ulAmountPerServing()
            : n.amountPerServing();
    Unit canonical = registry.canonicalUnit(n.nutrientCode()).orElseThrow();
    BigDecimal converted =
        conversions
            .convert(n.nutrientCode(), perServing, n.unit(), n.formCode(), canonical)
            .amount();
    return converted.multiply(p.details().servingsPerDay());
  }

  /** The UL matching rule of section 5.7; empty when no UL applies (including exempt rows). */
  private static Optional<UpperLimitRow> upperLimit(String ulSet, ProductNutrientValues n) {
    Map<String, UpperLimitRow> rows = new HashMap<>();
    for (UpperLimitRow row : reference.upperLimits()) {
      if (row.ulSetCode().equals(ulSet) && row.nutrientCode().equals(n.nutrientCode())) {
        rows.put(row.formCode(), row);
      }
    }
    UpperLimitRow match = null;
    if (!FormCodes.UNKNOWN.equals(n.formCode())) {
      match = rows.get(n.formCode());
    }
    if (match == null) {
      match = rows.get(FormCodes.ANY);
    }
    return Optional.ofNullable(match).filter(r -> r.amount() != null);
  }

  private static String ulSetOf(String country) {
    return reference.countries().stream()
        .filter(c -> c.code().equals(country))
        .map(CountryRow::ulSetCode)
        .findFirst()
        .orElseThrow();
  }

  /** Nutrients of a product above the UL set of a country. */
  private static List<String> exceedances(ProductImport p, String country) {
    List<String> result = new ArrayList<>();
    for (ProductNutrientValues n : p.nutrients()) {
      Optional<UpperLimitRow> ul = upperLimit(ulSetOf(country), n);
      if (ul.isPresent() && perDay(p, n, true).compareTo(ul.get().amount()) > 0) {
        result.add(n.nutrientCode() + " " + perDay(p, n, true) + " > " + ul.get().amount());
      }
    }
    return result;
  }

  // Codes, brands and registry references

  @Test
  void productCodesAreUniqueAcrossAllFiles() {
    List<String> all = new ArrayList<>();
    synthetic.forEach(p -> all.add(p.details().code()));
    real.forEach(p -> all.add(p.details().code()));
    assertThat(all).doesNotHaveDuplicates();
    assertThat(synthetic).hasSizeGreaterThanOrEqualTo(14);
  }

  @Test
  void syntheticProductsAreMarkedAndUseFictionalBrands() {
    assertThat(synthetic)
        .allSatisfy(p -> {
          assertThat(p.details().synthetic()).as(p.details().code()).isTrue();
          assertThat(FICTIONAL_BRANDS).as(p.details().code()).contains(p.details().brand());
        });
  }

  @Test
  void everyNutrientCodeIsKnown() {
    Set<String> known =
        reference.nutrients().stream().map(NutrientRow::code).collect(Collectors.toSet());
    List<ProductImport> all = new ArrayList<>(synthetic);
    all.addAll(real);
    assertThat(all)
        .allSatisfy(p ->
            assertThat(p.nutrients())
                .allSatisfy(n -> assertThat(known).contains(n.nutrientCode())));
  }

  @Test
  void formSentinelRuleHolds() {
    List<ProductImport> all = new ArrayList<>(synthetic);
    all.addAll(real);
    assertThat(all)
        .flatExtracting(ProductImport::nutrients)
        .allSatisfy(n -> {
          assertThat(n.formCode()).isNotNull().isNotEqualTo(FormCodes.ANY);
          if (!FormCodes.UNKNOWN.equals(n.formCode())) {
            assertThat(reference.forms()).containsEntry(n.formCode(), n.nutrientCode());
          }
        });
    assertThat(reference.upperLimits())
        .allSatisfy(u -> assertThat(u.formCode()).isNotNull().isNotEqualTo(FormCodes.UNKNOWN));
    assertThat(reference.conversions())
        .allSatisfy(c -> assertThat(c.formCode()).isNotNull().isNotEqualTo(FormCodes.UNKNOWN));
  }

  @Test
  void everyProductExceptZn40HasAtLeastFiveNutrients() {
    assertThat(synthetic)
        .filteredOn(p -> !p.details().code().equals("ZN40"))
        .allSatisfy(
            p -> assertThat(p.nutrients()).as(p.details().code()).hasSizeGreaterThanOrEqualTo(5));
  }

  // Availability

  @Test
  void availabilityCoverage() {
    long trOnly = synthetic.stream().filter(p -> availableIn(p).equals(Set.of("TR"))).count();
    long usOnly = synthetic.stream().filter(p -> availableIn(p).equals(Set.of("US"))).count();
    long deAndGb =
        synthetic.stream().filter(p -> availableIn(p).containsAll(Set.of("DE", "GB"))).count();
    long allFour = synthetic.stream().filter(p -> availableIn(p).containsAll(MARKETS)).count();
    long notSoldTr =
        synthetic.stream()
            .filter(p ->
                p.availability().stream()
                    .anyMatch(a ->
                        a.countryCode().equals("TR") && a.status() == AvailabilityStatus.NOT_SOLD))
            .count();
    assertThat(trOnly).isGreaterThanOrEqualTo(3);
    assertThat(usOnly).isGreaterThanOrEqualTo(3);
    assertThat(deAndGb).isGreaterThanOrEqualTo(2);
    assertThat(allFour).isGreaterThanOrEqualTo(2);
    assertThat(notSoldTr).isGreaterThanOrEqualTo(2);
  }

  // Offers

  @Test
  void offerCurrenciesEqualTheCountryCurrency() {
    Map<String, String> currencies =
        reference.countries().stream()
            .collect(Collectors.toMap(CountryRow::code, CountryRow::currency));
    List<OfferValues> all = new ArrayList<>(offers);
    all.addAll(priceDropOffers);
    all.addAll(realOffers);
    assertThat(all)
        .allSatisfy(o -> assertThat(o.currency()).isEqualTo(currencies.get(o.countryCode())));
  }

  @Test
  void offersOnlyExistWhereTheProductIsListed() {
    assertThat(offers)
        .allSatisfy(o ->
            assertThat(product(o.productCode()).availability())
                .extracting(AvailabilityValues::countryCode)
                .contains(o.countryCode()));
  }

  @Test
  void goldenPairIsInTrWithInStockOffersAt800And900() {
    assertThat(inTrMarket(product("GOLD-A"))).isTrue();
    assertThat(inTrMarket(product("GOLD-B"))).isTrue();
    assertThat(bestInStockPrice("GOLD-A", "TR"))
        .hasValueSatisfying(p -> assertThat(p).isEqualByComparingTo("800.00"));
    assertThat(bestInStockPrice("GOLD-B", "TR"))
        .hasValueSatisfying(p -> assertThat(p).isEqualByComparingTo("900.00"));
  }

  @Test
  void atLeastTwoTrProductsHaveOnlyOutOfStockOffers() {
    long count =
        synthetic.stream()
            .filter(FixtureIntegrityTest::inTrMarket)
            .filter(p -> {
              List<OfferValues> tr = offersOf(p.details().code(), "TR");
              return !tr.isEmpty() && tr.stream().noneMatch(OfferValues::inStock);
            })
            .count();
    assertThat(count).isGreaterThanOrEqualTo(2);
  }

  @Test
  void atLeastOneInStockOfferIsOlderThan30Days() {
    Instant staleBefore = NOW.minus(Duration.ofDays(30));
    assertThat(offers)
        .anySatisfy(o -> {
          assertThat(o.inStock()).isTrue();
          assertThat(o.checkedAt()).isBefore(staleBefore);
        });
  }

  // Scenario products

  @Test
  void zn40ExceedsTheEfsaZincLimitInTr() {
    ProductImport zn40 = product("ZN40");
    assertThat(zn40.details().servingsPerDay()).isEqualByComparingTo("1");
    assertThat(zn40.nutrients())
        .singleElement()
        .satisfies(n -> {
          assertThat(n.nutrientCode()).isEqualTo("ZINC");
          assertThat(perDay(zn40, n, false)).isEqualByComparingTo("40");
        });
    assertThat(inTrMarket(zn40)).isTrue();
    assertThat(bestInStockPrice("ZN40", "TR")).isPresent();
    assertThat(exceedances(zn40, "TR")).singleElement().asString().startsWith("ZINC");
  }

  @Test
  void noOtherSyntheticFixtureExceedsAnApplicableUpperLimit() {
    for (ProductImport p : synthetic) {
      if (p.details().code().equals("ZN40")) {
        continue;
      }
      for (String country : availableIn(p)) {
        assertThat(exceedances(p, country)).as(p.details().code() + " in " + country).isEmpty();
      }
    }
  }

  @Test
  void trCoenzymeQ10MarketHasExactlyThreeProductsAt30100And200MgPerDay() {
    List<ProductImport> withCoq10 =
        synthetic.stream()
            .filter(FixtureIntegrityTest::inTrMarket)
            .filter(p -> p.nutrients().stream().anyMatch(n -> n.nutrientCode().equals("COQ10")))
            .toList();
    assertThat(withCoq10).hasSize(3);
    assertThat(withCoq10)
        .extracting(p ->
            perDay(
                    p,
                    p.nutrients().stream()
                        .filter(n -> n.nutrientCode().equals("COQ10"))
                        .findFirst()
                        .orElseThrow(),
                    false)
                .stripTrailingZeros()
                .toPlainString())
        .containsExactlyInAnyOrder("30", "100", "200");
    assertThat(withCoq10)
        .allSatisfy(p -> assertThat(bestInStockPrice(p.details().code(), "TR")).isPresent())
        .anySatisfy(
            p -> assertThat(p.nutrients()).anyMatch(n -> n.nutrientCode().equals("LUTEIN")));
  }

  @Test
  void unitAndServingScenariosArePresent() {
    List<ProductNutrientValues> all =
        synthetic.stream().flatMap(p -> p.nutrients().stream()).toList();
    assertThat(all).anyMatch(n -> n.nutrientCode().equals("VIT_D") && n.unit() == Unit.IU);
    assertThat(all)
        .anyMatch(n ->
            n.nutrientCode().equals("VIT_E")
                && n.unit() == Unit.IU
                && n.formCode().equals("D_ALPHA_TOCOPHEROL"));
    assertThat(synthetic)
        .anyMatch(p ->
            p.details().unitsPerServing() == 2
                && p.details().servingsPerDay().compareTo(new BigDecimal("2")) == 0);
  }

  @Test
  void priceDropFileDiffersOnlyInTheTwoIntendedPairs() {
    assertThat(priceDropOffers).hasSameSizeAs(offers);
    Map<String, OfferValues> before = byKey(offers);
    Map<String, OfferValues> after = byKey(priceDropOffers);
    assertThat(after.keySet()).isEqualTo(before.keySet());
    List<String> changed =
        before.keySet().stream().filter(k -> !same(before.get(k), after.get(k))).sorted().toList();
    assertThat(changed).hasSize(2);

    OfferValues goldBefore =
        before.values().stream()
            .filter(o -> o.productCode().equals("GOLD-B") && o.inStock())
            .min(Comparator.comparing(OfferValues::price))
            .orElseThrow();
    OfferValues goldAfter = after.get(key(goldBefore));
    assertThat(changed).contains(key(goldBefore));
    assertThat(goldBefore.price()).isEqualByComparingTo("900.00");
    assertThat(goldAfter.price()).isEqualByComparingTo("846.00");
    assertThat(goldAfter.price()).isGreaterThan(new BigDecimal("800.00"));
    assertThat(goldAfter.inStock()).isTrue();

    String restockKey =
        changed.stream().filter(k -> !k.equals(key(goldBefore))).findFirst().orElseThrow();
    OfferValues restockBefore = before.get(restockKey);
    OfferValues restockAfter = after.get(restockKey);
    assertThat(restockBefore.countryCode()).isEqualTo("TR");
    assertThat(restockBefore.inStock()).isFalse();
    assertThat(restockAfter.inStock()).isTrue();
    assertThat(offersOf(restockBefore.productCode(), "TR")).noneMatch(OfferValues::inStock);
    assertThat(restockAfter.price()).isEqualByComparingTo(restockBefore.price());
  }

  private static Map<String, OfferValues> byKey(List<OfferValues> list) {
    return list.stream().collect(Collectors.toMap(FixtureIntegrityTest::key, o -> o));
  }

  private static String key(OfferValues o) {
    return o.productCode() + "|" + o.countryCode() + "|" + o.retailer();
  }

  private static boolean same(OfferValues a, OfferValues b) {
    return a.price().compareTo(b.price()) == 0
        && a.currency().equals(b.currency())
        && a.inStock() == b.inStock()
        && a.url().equals(b.url())
        && a.checkedAt().equals(b.checkedAt());
  }

  // Real label snapshot

  @Test
  void realProductsCarryProvenanceAndAreUsOnly() {
    assertThat(real).isNotEmpty();
    assertThat(real)
        .allSatisfy(p -> {
          String code = p.details().code();
          assertThat(p.details().synthetic()).as(code).isFalse();
          assertThat(p.details().source()).as(code).isEqualTo("DSLD");
          assertThat(p.details().sourceRef()).as(code).isNotBlank();
          assertThat(p.details().sourceEntryDate()).as(code).isNotNull();
          assertThat(p.availability())
              .as(code)
              .singleElement()
              .satisfies(a -> {
                assertThat(a.countryCode()).isEqualTo("US");
                assertThat(a.status()).isEqualTo(AvailabilityStatus.AVAILABLE);
              });
        });
  }

  @Test
  void realOffersAreUsdForRealProducts() {
    Set<String> realCodes = codes(real);
    assertThat(realOffers).isNotEmpty();
    assertThat(realOffers)
        .allSatisfy(o -> {
          assertThat(realCodes).contains(o.productCode());
          assertThat(o.countryCode()).isEqualTo("US");
          assertThat(o.currency()).isEqualTo("USD");
        });
  }
}
